// Builds the WhatsApp Cloud API message bodies Lia sends.
// Every function returns the JSON body for POST /{phone-number-id}/messages, or null
// when Lia must stay silent. Used inside n8n Code nodes and tested with `npm test`.

const DEFAULT_CONFIG = {
  timeZone: 'America/La_Paz',
  currency: 'Bs',
  payBaseUrl: 'https://marilyncd12.app.n8n.cloud/webhook/lia-pay',
  qrImageBaseUrl: 'https://quickchart.io/qr',
};

function formatAmount(amount, config = DEFAULT_CONFIG) {
  const value = Number(amount).toFixed(2).replace('.', ',');
  return `${config.currency} ${value}`;
}

function formatTime(iso, config = DEFAULT_CONFIG) {
  return new Intl.DateTimeFormat('es-BO', {
    timeZone: config.timeZone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(iso));
}

function formatDateTime(iso, config = DEFAULT_CONFIG) {
  const parts = new Intl.DateTimeFormat('es-BO', {
    timeZone: config.timeZone,
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(new Date(iso));
  const get = (type) => parts.find((p) => p.type === type)?.value;
  return `${get('day')}/${get('month')}/${get('year')} ${get('hour')}:${get('minute')}`;
}

function buyerLine(p) {
  const phone = p.customer_phone ? `+${String(p.customer_phone).replace(/^\+/, '')}` : '';
  return [p.customer_name, phone].filter(Boolean).join(' · ');
}

function garmentLabel(garment) {
  const parts = [garment.code];
  if (garment.name && garment.name !== garment.code) parts.push(garment.name);
  if (garment.size) parts.push(`talla ${garment.size}`);
  if (garment.color) parts.push(garment.color);
  return parts.join(' · ');
}

function text(to, body) {
  return { messaging_product: 'whatsapp', to, type: 'text', text: { body } };
}

function image(to, link, caption) {
  return { messaging_product: 'whatsapp', to, type: 'image', image: { link, caption } };
}

function payUrl(token, config = DEFAULT_CONFIG) {
  return `${config.payBaseUrl}?token=${encodeURIComponent(token)}`;
}

function qrImageUrl(token, config = DEFAULT_CONFIG) {
  const params = new URLSearchParams({ text: payUrl(token, config), size: '600', margin: '2' });
  return `${config.qrImageBaseUrl}?${params.toString()}`;
}

/** Photo of the garment + "is this the one?" buttons (result of lia_request_garment). */
function buildPreviewMessage(to, garment, config = DEFAULT_CONFIG, confirmDeadline = null) {
  const body =
    `Lia: ¿Es esta prenda?\n\n${garmentLabel(garment)}\nPrecio: ${formatAmount(garment.price, config)}` +
    (garment.description ? `\n${garment.description}` : '') +
    (confirmDeadline
      ? `\n\nConfirma antes de las ${formatTime(confirmDeadline, config)} (1 minuto). Te envío el QR de pago apenas confirmes.`
      : '');

  const interactive = {
    type: 'button',
    body: { text: body },
    action: {
      buttons: [
        { type: 'reply', reply: { id: 'lia_confirm', title: 'Sí, es esta' } },
        { type: 'reply', reply: { id: 'lia_reject', title: 'No es esta' } },
      ],
    },
  };
  if (garment.photo_url) {
    interactive.header = { type: 'image', image: { link: garment.photo_url } };
  }
  return { messaging_product: 'whatsapp', to, type: 'interactive', interactive };
}

/** Reply to lia_request_garment. */
function buildRequestReply(to, result, config = DEFAULT_CONFIG) {
  switch (result.status) {
    case 'pending_confirmation':
      return buildPreviewMessage(to, result.garment, config, result.confirm_deadline);
    case 'not_found':
      return text(to, `Lia: no encontré la prenda ${result.code}. Revisa el código que dijeron en el live y escríbelo de nuevo.`);
    case 'sold_out':
      return text(to, `Lia: la ${result.garment.code} ya se vendió. Escribe el código de otra prenda del live.`);
    case 'no_live':
      return text(to, 'Lia: ahora no hay un live activo. Te esperamos en el próximo live.');
    case 'already_requested':
      return text(to, `Lia: ya tienes un pedido de la ${result.garment.code} en curso. Te aviso apenas avance.`);
    default: // duplicate, unknown_seller
      return null;
  }
}

/**
 * Reply to lia_confirm_request. 'active' and 'queued' return null: the QR and the
 * queue position arrive through the notification outbox.
 */
function buildConfirmReply(to, result) {
  switch (result.status) {
    case 'confirmation_expired':
      return text(to, `Lia: pasó el tiempo para confirmar la ${result.garment.code}. Si todavía la quieres, escribe el código otra vez.`);
    case 'sold_out':
      return text(to, `Lia: lo siento, la ${result.garment.code} ya se vendió.`);
    case 'limit_reached':
      return text(to, `Lia: ya tienes ${result.limit} prendas reservadas. Paga alguna para reservar otra.`);
    case 'nothing_to_confirm':
      return text(to, 'Lia: no tengo ninguna prenda pendiente para confirmar. Escribe el código de la prenda que quieres.');
    default: // active, queued, duplicate, unknown_seller
      return null;
  }
}

/** Builds the WhatsApp body for one outbox notification (lia_pending_notifications row). */
function buildNotificationMessage(notification, config = DEFAULT_CONFIG) {
  const to = notification.to_phone ?? notification.customer_phone;
  const p = notification.payload ?? {};
  const g = p.garment ?? {};

  switch (notification.kind) {
    case 'reservation_qr':
      return image(
        to,
        qrImageUrl(p.qr_token, config),
        `Lia: ¡la ${garmentLabel(g)} es tuya si pagas a tiempo!\n` +
          `Monto: ${formatAmount(p.amount, config)}\n` +
          `Válido hasta: ${formatTime(p.expires_at, config)} (5 minutos)\n\n` +
          'Escanea o guarda este QR y págalo desde la app de tu banco. Después de esa hora el QR ya no se puede pagar.',
      );
    case 'queued':
      return text(to, `Lia: estás en el puesto #${p.position} de la fila para la ${g.code}. Si se libera, te mando el QR al toque.`);
    case 'payment_reminder':
      return text(to, `Lia: te queda 1 minuto para pagar la ${g.code} (hasta las ${formatTime(p.expires_at, config)}).`);
    case 'payment_confirmed':
      return text(
        to,
        `Lia: ¡pago confirmado! La ${garmentLabel(g)} es tuya.\n` +
          `A nombre de: ${buyerLine(p)}\n` +
          `Monto: ${formatAmount(p.amount, config)}\n` +
          (p.paid_at ? `Fecha de pago: ${formatDateTime(p.paid_at, config)}\n` : '') +
          'La tienda te contactará para la entrega.',
      );
    case 'garment_sold':
      return text(
        to,
        `Lia: ¡vendiste la ${garmentLabel(g)}!\n` +
          `Compradora: ${buyerLine(p)}\n` +
          `Monto: ${formatAmount(p.amount, config)}\n` +
          (p.paid_at ? `Fecha de pago: ${formatDateTime(p.paid_at, config)}` : ''),
      );
    case 'late_payment':
      return text(to, `Lia: llegó un pago tardío de ${formatAmount(p.amount, config)} por la ${g.code}. Revísalo en la app.`);
    case 'qr_expired':
      return text(to, `Lia: tu QR de la ${g.code} venció y ya no se puede pagar. La prenda pasó a la siguiente persona de la fila.`);
    case 'sold_out':
      return text(to, `Lia: la ${g.code} ya fue vendida. Escribe el código de otra prenda del live.`);
    case 'confirmation_expired':
      return text(to, `Lia: no confirmaste la ${g.code} a tiempo. Si todavía la quieres, escribe el código otra vez.`);
    case 'live_ended':
      return text(to, `Lia: el live terminó y tu pedido de la ${g.code} se canceló. ¡Te esperamos en el próximo!`);
    default:
      return null;
  }
}

module.exports = {
  DEFAULT_CONFIG,
  formatAmount,
  formatTime,
  formatDateTime,
  payUrl,
  qrImageUrl,
  buildPreviewMessage,
  buildRequestReply,
  buildConfirmReply,
  buildNotificationMessage,
};
