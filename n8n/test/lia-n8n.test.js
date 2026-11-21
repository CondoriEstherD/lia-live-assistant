const test = require('node:test');
const assert = require('node:assert/strict');

const { parseWhatsAppWebhook, classifyMessage } = require('../code/whatsapp-inbound');
const {
  formatAmount,
  formatTime,
  qrImageUrl,
  buildRequestReply,
  buildConfirmReply,
  buildNotificationMessage,
} = require('../code/lia-messages');

const { buildPayPage } = require('../code/lia-pay-page');

const garment = { code: 'A12', name: 'Vestido rojo', size: 'M', price: 150, photo_url: 'https://cdn/a12.jpg' };

test('parses buyer messages and ignores status callbacks', () => {
  const body = {
    entry: [{
      changes: [{
        value: {
          metadata: { phone_number_id: 'PN1' },
          contacts: [{ wa_id: '59170000001', profile: { name: 'Ana' } }],
          messages: [
            { from: '59170000001', id: 'wamid.1', timestamp: '1763740800', type: 'text', text: { body: 'A12' } },
            {
              from: '59170000001', id: 'wamid.2', timestamp: '1763740810', type: 'interactive',
              interactive: { type: 'button_reply', button_reply: { id: 'lia_confirm', title: 'Sí, es esta' } },
            },
          ],
        },
      }, { value: { metadata: { phone_number_id: 'PN1' }, statuses: [{ id: 'wamid.x', status: 'read' }] } }],
    }],
  };

  const messages = parseWhatsAppWebhook(body);

  assert.equal(messages.length, 2);
  assert.deepEqual(messages[0], {
    phoneNumberId: 'PN1', from: '59170000001', name: 'Ana', waMessageId: 'wamid.1',
    messageTs: '2025-11-21T16:00:00.000Z', type: 'text', text: 'A12', buttonId: null, mediaId: null,
  });
  assert.equal(messages[1].buttonId, 'lia_confirm');
});

test('classifies garment codes', () => {
  assert.deepEqual(classifyMessage({ type: 'text', text: 'a12' }), { route: 'code', code: 'A12' });
  assert.deepEqual(classifyMessage({ type: 'text', text: '#B-07' }), { route: 'code', code: 'B-07' });
  assert.deepEqual(classifyMessage({ type: 'text', text: 'código 123' }), { route: 'code', code: '123' });
});

test('classifies confirmations and rejections from text or buttons', () => {
  assert.equal(classifyMessage({ type: 'text', text: 'Sí!!' }).route, 'confirm');
  assert.equal(classifyMessage({ type: 'text', text: 'SI 👍' }).route, 'confirm');
  assert.equal(classifyMessage({ type: 'interactive', text: 'x', buttonId: 'lia_confirm' }).route, 'confirm');
  assert.equal(classifyMessage({ type: 'text', text: 'no es esa' }).route, 'reject');
  assert.equal(classifyMessage({ type: 'interactive', text: 'x', buttonId: 'lia_reject' }).route, 'reject');
});

test('sends free text to the agent and ignores unsupported media', () => {
  assert.equal(classifyMessage({ type: 'text', text: '¿Hacen envíos a Montero?' }).route, 'free_text');
  assert.equal(classifyMessage({ type: 'text', text: 'quiero la A12 en talla M' }).route, 'free_text');
  assert.equal(classifyMessage({ type: 'audio', text: null }).route, 'unsupported');
});

test('formats amounts in bolivianos and times in Bolivia', () => {
  assert.equal(formatAmount(150), 'Bs 150,00');
  assert.equal(formatTime('2026-11-22T00:35:00Z'), '20:35');
});

test('garment preview is a plain photo with caption (buttons are not shown on every phone)', () => {
  const msg = buildRequestReply('591700', { status: 'pending_confirmation', garment });

  assert.equal(msg.type, 'image');
  assert.equal(msg.image.link, 'https://cdn/a12.jpg');
  assert.match(msg.image.caption, /A12 · Vestido rojo · talla M/);
  assert.match(msg.image.caption, /Bs 150,00/);
  assert.match(msg.image.caption, /Responde SI/);
  assert.match(msg.image.caption, /NO/);
});

test('garment preview without photo is plain text', () => {
  const msg = buildRequestReply('591700', { status: 'pending_confirmation', garment: { ...garment, photo_url: null } });
  assert.equal(msg.type, 'text');
  assert.match(msg.text.body, /Responde SI/);
});

test('request replies for missing, sold and duplicate cases', () => {
  assert.match(buildRequestReply('1', { status: 'not_found', code: 'Z9' }).text.body, /no encontré la prenda Z9/);
  assert.match(buildRequestReply('1', { status: 'sold_out', garment }).text.body, /ya se vendió/);
  assert.equal(buildRequestReply('1', { status: 'duplicate' }), null);
});

test('confirm replies stay silent when the outbox will deliver the QR or queue position', () => {
  assert.equal(buildConfirmReply('1', { status: 'active', garment }), null);
  assert.equal(buildConfirmReply('1', { status: 'queued', position: 2, garment }), null);
  assert.match(buildConfirmReply('1', { status: 'limit_reached', limit: 3 }).text.body, /3 prendas reservadas/);
});

test('reservation QR is sent as an image with amount and expiry time', () => {
  const msg = buildNotificationMessage({
    kind: 'reservation_qr',
    customer_phone: '59170000001',
    payload: { qr_token: 'tok123', amount: 150, expires_at: '2026-11-22T00:35:00Z', garment },
  });

  assert.equal(msg.type, 'image');
  assert.equal(msg.to, '59170000001');
  assert.equal(msg.image.link, qrImageUrl('tok123'));
  assert.match(decodeURIComponent(msg.image.link), /lia-pay\?token=tok123/);
  assert.match(msg.image.caption, /Monto: Bs 150,00/);
  assert.match(msg.image.caption, /Válido hasta: 20:35/);
});

test('every outbox kind produces a message', () => {
  const kinds = ['queued', 'payment_reminder', 'payment_confirmed', 'qr_expired', 'sold_out', 'confirmation_expired', 'live_ended'];
  for (const kind of kinds) {
    const msg = buildNotificationMessage({
      kind, customer_phone: '1', payload: { position: 2, expires_at: '2026-11-22T00:35:00Z', garment },
    });
    assert.equal(msg.type, 'text', kind);
    assert.match(msg.text.body, /^Lia: /, kind);
  }
  assert.equal(buildNotificationMessage({ kind: 'unknown', customer_phone: '1', payload: {} }), null);
});

test('pay page shows the countdown and pay button while the QR is active', () => {
  const html = buildPayPage(
    { status: 'active', amount: 150, expires_at: '2026-11-22T00:35:00Z', seconds_left: 240, garment },
    'tok123',
  );
  assert.match(html, /Bs 150,00/);
  assert.match(html, /Válido hasta 20:35/);
  assert.match(html, /name="token" value="tok123"/);
  assert.match(html, /let s=240;/);
});

test('pay page blocks payment once the QR expired', () => {
  const html = buildPayPage({ status: 'expired', amount: 150, garment }, 'tok123');
  assert.match(html, /QR vencido/);
  assert.doesNotMatch(html, /<button/);
});

test('pay page escapes garment text', () => {
  const html = buildPayPage({ status: 'paid', amount: 1, garment: { code: 'A1', name: '<script>x</script>' } }, 't');
  assert.doesNotMatch(html, /<script>x/);
});

test('garment preview tells the buyer she has 1 minute to confirm', () => {
  const msg = buildRequestReply('591700', {
    status: 'pending_confirmation', confirm_deadline: '2026-11-22T00:36:00Z', garment,
  });
  assert.match(msg.image.caption, /Confirma antes de las 20:36 \(1 minuto\)/);
});

const { buildVoucherReply, parseVoucherExtraction } = require('../code/lia-messages');

const buyer = { customer_phone: '59171111111', paid_at: '2026-11-22T00:35:00Z', amount: 150, garment };

test('payment confirmation shows phone and date and asks for the voucher photo', () => {
  const msg = buildNotificationMessage({ kind: 'payment_confirmed', to_phone: '59171111111', payload: buyer });
  assert.equal(msg.to, '59171111111');
  assert.match(msg.text.body, /\+59171111111/);
  assert.match(msg.text.body, /Fecha de pago: 21.11.2026 20:35/);
  assert.match(msg.text.body, /foto de tu comprobante/);
});

test('the seller is told about the sale while the buyer name is pending', () => {
  const msg = buildNotificationMessage({ kind: 'garment_sold', audience: 'seller', to_phone: '59170000000', payload: buyer });
  assert.equal(msg.to, '59170000000');
  assert.match(msg.text.body, /A12/);
  assert.match(msg.text.body, /\+59171111111/);
  assert.match(msg.text.body, /Bs 150,00/);
  assert.match(msg.text.body, /Fecha de pago: 21.11.2026 20:35/);
  assert.match(msg.text.body, /pendiente del comprobante/);
});

test('after the voucher, buyer and seller get the name from the voucher', () => {
  const payload = { ...buyer, customer_name: 'Ana María Pérez Rojas' };
  const toBuyer = buildNotificationMessage({ kind: 'voucher_registered', to_phone: '59171111111', payload });
  assert.match(toBuyer.text.body, /a nombre de Ana María Pérez Rojas/);
  assert.match(toBuyer.text.body, /\+59171111111/);
  assert.match(toBuyer.text.body, /Fecha de pago: 21.11.2026 20:35/);

  const toSeller = buildNotificationMessage({ kind: 'buyer_identified', to_phone: '59170000000', payload });
  assert.equal(toSeller.to, '59170000000');
  assert.match(toSeller.text.body, /Ana María Pérez Rojas/);
  assert.match(toSeller.text.body, /\+59171111111/);
  assert.match(toSeller.text.body, /A12/);
  assert.match(toSeller.text.body, /Fecha de pago: 21.11.2026 20:35/);
});

test('the seller is asked to review a voucher with another amount', () => {
  const msg = buildNotificationMessage({
    kind: 'voucher_review', to_phone: '59170000000',
    payload: { expected: 150, voucher_amount: 90, payer_name: 'Ana', customer_phone: '59171111111', garment },
  });
  assert.match(msg.text.body, /Bs 90,00/);
  assert.match(msg.text.body, /Bs 150,00/);
});

test('a photo from the buyer is treated as a payment voucher', () => {
  const [m] = parseWhatsAppWebhook({ entry: [{ changes: [{ value: {
    metadata: { phone_number_id: 'PN1' },
    messages: [{ from: '591', id: 'wamid.img', timestamp: '1763740800', type: 'image', image: { id: 'MEDIA1', mime_type: 'image/jpeg' } }],
  } }] }] });
  assert.equal(m.mediaId, 'MEDIA1');
  assert.deepEqual(classifyMessage(m), { route: 'voucher' });
});

test('voucher replies to the buyer', () => {
  assert.equal(buildVoucherReply('1', { status: 'registered' }), null); // sent through the outbox
  assert.match(buildVoucherReply('1', { status: 'name_missing' }).text.body, /no pude leer el nombre/);
  assert.match(buildVoucherReply('1', { status: 'amount_mismatch', expected: 150, voucher_amount: 90 }).text.body, /Bs 90,00/);
  assert.match(buildVoucherReply('1', { status: 'no_sale' }).text.body, /no encuentro un pago/);
  assert.match(buildVoucherReply('1', { status: 'already_registered' }).text.body, /ya está registrada/);
});

test('the AI voucher reading is parsed safely', () => {
  assert.deepEqual(
    parseVoucherExtraction('```json\n{"payer_name":" Ana  María ","amount":"150,00","paid_at":"2026-11-21T20:35:00-04:00","reference":"TRX1"}\n```'),
    { payer_name: 'Ana María', amount: 150, paid_at: '2026-11-22T00:35:00.000Z', reference: 'TRX1' },
  );
  assert.deepEqual(parseVoucherExtraction('no es un comprobante'), { payer_name: null, amount: null, paid_at: null, reference: null });
});

test('parses Whapi webhooks: text, button replies; ignores own and group messages', () => {
  const body = {
    channel_id: 'HULKBR-4WGWP',
    event: { type: 'messages', event: 'post' },
    messages: [
      { id: 'w1', from_me: false, type: 'text', chat_id: '59170000001@s.whatsapp.net', timestamp: 1763740800,
        text: { body: 'A12' }, from: '59170000001', from_name: 'Ana' },
      { id: 'w2', from_me: false, type: 'reply', chat_id: '59170000001@s.whatsapp.net', timestamp: 1763740810,
        reply: { type: 'buttons_reply', buttons_reply: { id: 'ButtonsV3:lia_confirm', title: 'Sí, es esta' } },
        from: '59170000001', from_name: 'Ana' },
      { id: 'w3', from_me: true, type: 'text', chat_id: '59170000001@s.whatsapp.net', timestamp: 1763740811, text: { body: 'x' } },
      { id: 'w4', from_me: false, type: 'text', chat_id: '123@g.us', timestamp: 1763740812, text: { body: 'A12' }, from: '5917' },
    ],
  };

  const messages = parseWhatsAppWebhook(body);

  assert.equal(messages.length, 2);
  assert.deepEqual(messages[0], {
    phoneNumberId: 'HULKBR-4WGWP', from: '59170000001', name: 'Ana', waMessageId: 'w1',
    messageTs: '2025-11-21T16:00:00.000Z', type: 'text', text: 'A12', buttonId: null, mediaId: null,
  });
  assert.equal(messages[1].buttonId, 'lia_confirm');
  assert.deepEqual(classifyMessage(messages[1]), { route: 'confirm' });
  assert.deepEqual(classifyMessage(messages[0]), { route: 'code', code: 'A12' });
});

test('toWhapi converts text, image and button messages to Whapi requests', () => {
  const { toWhapi } = require('../code/lia-messages');
  assert.equal(toWhapi(null), null);
  assert.deepEqual(toWhapi({ messaging_product: 'whatsapp', to: '591', type: 'text', text: { body: 'hola' } }),
    { path: 'messages/text', body: { to: '591', body: 'hola' } });
  assert.deepEqual(toWhapi({ messaging_product: 'whatsapp', to: '591', type: 'image', image: { link: 'https://q', caption: 'c' } }),
    { path: 'messages/image', body: { to: '591', media: 'https://q', caption: 'c' } });

  const preview = toWhapi(buildRequestReply('591', { status: 'pending_confirmation', garment }));
  assert.equal(preview.path, 'messages/image');
  assert.equal(preview.body.media, 'https://cdn/a12.jpg');
  assert.match(preview.body.caption, /Responde SI/);
});

test('QR image URL works in the n8n Code sandbox, which has no URLSearchParams', () => {
  const original = globalThis.URLSearchParams;
  delete globalThis.URLSearchParams;
  try {
    const url = qrImageUrl('tok123');
    assert.ok(url.startsWith('https://quickchart.io/qr?text='));
    assert.ok(decodeURIComponent(url).includes('lia-pay?token=tok123'));
    assert.match(url, /size=600/);
  } finally {
    globalThis.URLSearchParams = original;
  }
});
