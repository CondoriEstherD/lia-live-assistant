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
    messageTs: '2025-11-21T16:00:00.000Z', type: 'text', text: 'A12', buttonId: null,
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
  assert.equal(classifyMessage({ type: 'image', text: null }).route, 'unsupported');
});

test('formats amounts in bolivianos and times in Bolivia', () => {
  assert.equal(formatAmount(150), 'Bs 150,00');
  assert.equal(formatTime('2026-11-22T00:35:00Z'), '20:35');
});

test('garment preview shows the photo with confirm/reject buttons', () => {
  const msg = buildRequestReply('591700', { status: 'pending_confirmation', garment });

  assert.equal(msg.type, 'interactive');
  assert.deepEqual(msg.interactive.header, { type: 'image', image: { link: 'https://cdn/a12.jpg' } });
  assert.match(msg.interactive.body.text, /A12 · Vestido rojo · talla M/);
  assert.match(msg.interactive.body.text, /Bs 150,00/);
  assert.deepEqual(msg.interactive.action.buttons.map((b) => b.reply.id), ['lia_confirm', 'lia_reject']);
});

test('garment preview without photo has no header', () => {
  const msg = buildRequestReply('591700', { status: 'pending_confirmation', garment: { ...garment, photo_url: null } });
  assert.equal(msg.interactive.header, undefined);
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
  assert.match(msg.interactive.body.text, /Confirma antes de las 20:36 \(1 minuto\)/);
});

test('payment confirmation shows the buyer name and phone', () => {
  const msg = buildNotificationMessage({
    kind: 'payment_confirmed', to_phone: '59171111111', customer_phone: '59171111111',
    payload: { amount: 150, paid_at: '2026-11-22T00:35:00Z', customer_name: 'Ana Pérez', customer_phone: '59171111111', garment },
  });
  assert.equal(msg.to, '59171111111');
  assert.match(msg.text.body, /Ana Pérez/);
  assert.match(msg.text.body, /\+59171111111/);
  assert.match(msg.text.body, /Fecha de pago: 21.11.2026 20:35/);
});

test('the seller gets who bought: name, phone, garment and amount', () => {
  const msg = buildNotificationMessage({
    kind: 'garment_sold', audience: 'seller', to_phone: '59170000000',
    payload: { amount: 150, paid_at: '2026-11-22T00:35:00Z', customer_name: 'Ana Pérez', customer_phone: '59171111111', garment },
  });
  assert.equal(msg.to, '59170000000');
  assert.match(msg.text.body, /A12/);
  assert.match(msg.text.body, /Ana Pérez/);
  assert.match(msg.text.body, /\+59171111111/);
  assert.match(msg.text.body, /Bs 150,00/);
  assert.match(msg.text.body, /Fecha de pago: 21.11.2026 20:35/);
});
