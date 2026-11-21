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
  const params = [['text', payUrl(token, config)], ['size', '600'], ['margin', '2']]
    .map(([key, value]) => `${key}=${encodeURIComponent(value)}`)
    .join('&');
  return `${config.qrImageBaseUrl}?${params}`;
}

/**
 * Photo of the garment + "is this the one?" question (result of lia_request_garment).
 * Plain photo with caption, no buttons: WhatsApp does not display button messages on every
 * phone when they come from a non-Business account. The buyer answers SI / NO as text.
 */
function buildPreviewMessage(to, garment, config = DEFAULT_CONFIG, confirmDeadline = null) {
  const lines = [
    'Lia: ¿Es esta prenda?',
    '',
    garmentLabel(garment),
    `Precio: ${formatAmount(garment.price, config)}`,
  ];
  if (garment.description) lines.push(garment.description);
  lines.push('', 'Responde SI si es esta, o NO si no es.');
  if (confirmDeadline) {
    lines.push(`Confirma antes de las ${formatTime(confirmDeadline, config)} (1 minuto). Te envío el QR de pago apenas confirmes.`);
  }
  const body = lines.join('\n');
  return garment.photo_url ? image(to, garment.photo_url, body) : text(to, body);
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
        [
          `Lia: ¡pago confirmado! La ${garmentLabel(g)} es tuya.`,
          `Teléfono: ${buyerLine({ customer_phone: p.customer_phone })}`,
          `Monto: ${formatAmount(p.amount, config)}`,
          p.paid_at ? `Fecha de pago: ${formatDateTime(p.paid_at, config)}` : null,
          '',
          'Para registrar la compra a tu nombre, envíame la foto de tu comprobante de pago.',
        ].filter((line) => line !== null).join('\n'),
      );
    case 'garment_sold':
      return text(
        to,
        [
          `Lia: ¡vendiste la ${garmentLabel(g)}!`,
          `Teléfono: ${buyerLine({ customer_phone: p.customer_phone })}`,
          'Nombre: pendiente del comprobante',
          `Monto: ${formatAmount(p.amount, config)}`,
          p.paid_at ? `Fecha de pago: ${formatDateTime(p.paid_at, config)}` : null,
        ].filter((line) => line !== null).join('\n'),
      );
    case 'voucher_registered':
      return text(
        to,
        [
          `Lia: listo, la ${garmentLabel(g)} quedó registrada a nombre de ${p.customer_name}.`,
          `Teléfono: ${buyerLine({ customer_phone: p.customer_phone })}`,
          p.paid_at ? `Fecha de pago: ${formatDateTime(p.paid_at, config)}` : null,
          'La tienda te contactará para la entrega.',
        ].filter((line) => line !== null).join('\n'),
      );
    case 'buyer_identified':
      return text(
        to,
        [
          `Lia: comprobante recibido de la ${garmentLabel(g)}.`,
          `Compradora: ${buyerLine(p)}`,
          `Monto: ${formatAmount(p.amount, config)}`,
          p.paid_at ? `Fecha de pago: ${formatDateTime(p.paid_at, config)}` : null,
        ].filter((line) => line !== null).join('\n'),
      );
    case 'voucher_review':
      return text(
        to,
        `Lia: revisa un comprobante de la ${g.code}: dice ${formatAmount(p.voucher_amount, config)} y la venta es de ` +
          `${formatAmount(p.expected, config)} (${buyerLine({ customer_name: p.payer_name, customer_phone: p.customer_phone })}).`,
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

/**
 * Converts a Cloud API style message (built above) into a Whapi.Cloud request:
 * { path, body } for POST https://gate.whapi.cloud/<path>. Returns null for null.
 */
function toWhapi(message) {
  if (!message) return null;
  const to = message.to;
  if (message.type === 'text') {
    return { path: 'messages/text', body: { to, body: message.text.body } };
  }
  if (message.type === 'image') {
    const body = { to, media: message.image.link };
    if (message.image.caption) body.caption = message.image.caption;
    return { path: 'messages/image', body };
  }
  if (message.type === 'interactive') {
    const i = message.interactive;
    const body = {
      to,
      type: 'button',
      body: { text: i.body.text },
      action: {
        buttons: i.action.buttons.map((b) => ({ type: 'quick_reply', title: b.reply.title, id: b.reply.id })),
      },
    };
    if (i.header?.image?.link) body.media = i.header.image.link;
    return { path: 'messages/interactive', body };
  }
  throw new Error(`Unsupported message type: ${message.type}`);
}

/** Reply to lia_register_voucher. 'registered' returns null: confirmations go through the outbox. */
function buildVoucherReply(to, result, config = DEFAULT_CONFIG) {
  switch (result.status) {
    case 'name_missing':
      return text(to, 'Lia: no pude leer el nombre en tu comprobante. Envíame una foto más clara del comprobante completo.');
    case 'amount_mismatch':
      return text(to, `Lia: tu comprobante dice ${formatAmount(result.voucher_amount, config)} y tu compra es de ${formatAmount(result.expected, config)}. La tienda lo va a revisar.`);
    case 'no_sale':
      return text(to, 'Lia: no encuentro un pago confirmado a tu número. Primero paga con el QR que te envié y después manda el comprobante.');
    case 'already_registered':
      return text(to, 'Lia: tu compra ya está registrada con tu comprobante. ¡Gracias!');
    default: // registered, unknown_seller
      return null;
  }
}

/** Parses the AI reading of a voucher photo. Unknown or unreadable fields come back as null. */
function parseVoucherExtraction(raw) {
  const empty = { payer_name: null, amount: null, paid_at: null, reference: null };
  const match = String(raw ?? '').match(/\{[\s\S]*\}/);
  if (!match) return empty;
  let data;
  try { data = JSON.parse(match[0]); } catch { return empty; }
  const name = typeof data.payer_name === 'string' ? data.payer_name.replace(/\s+/g, ' ').trim() : '';
  // "1.500,00" -> 1500.00 ; "150,00" -> 150.00 ; "150.00" -> 150.00
  const digits = String(data.amount ?? '').replace(/[^0-9,.]/g, '');
  const normalized = /,\d{1,2}$/.test(digits) ? digits.replace(/\./g, '').replace(',', '.') : digits.replace(/,/g, '');
  const amount = Number(normalized);
  const date = data.paid_at ? new Date(data.paid_at) : null;
  return {
    payer_name: name || null,
    amount: digits && Number.isFinite(amount) && amount > 0 ? amount : null,
    paid_at: date && !Number.isNaN(date.getTime()) ? date.toISOString() : null,
    reference: typeof data.reference === 'string' && data.reference.trim() ? data.reference.trim() : null,
  };
}

module.exports = {
  DEFAULT_CONFIG,
  toWhapi,
  formatAmount,
  formatTime,
  formatDateTime,
  payUrl,
  qrImageUrl,
  buildPreviewMessage,
  buildRequestReply,
  buildConfirmReply,
  buildNotificationMessage,
  buildVoucherReply,
  parseVoucherExtraction,
};
