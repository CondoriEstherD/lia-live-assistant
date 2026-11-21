// Parsing and routing of WhatsApp webhooks (Whapi.Cloud or Cloud API) for Lia.
// Used inside n8n Code nodes (copy the functions) and tested with `npm test`.

const CONFIRM_WORDS = new Set([
  'si', 'sii', 'siii', 'si es', 'si esa', 'es esa', 'esa', 'esa es', 'ok', 'okey', 'dale', 'la quiero', 'confirmo',
  'si es esta', 'es esta', 'si esta', 'esta', 'esta es',
]);
const REJECT_WORDS = new Set([
  'no', 'no es', 'no es esa', 'otra', 'cancelar', 'cancela', 'no es esta', 'no no es esta', 'no no es esa',
]);

// Garment codes: letters + digits, optional dash, at least one digit (A12, B-07, 123, ZP45B).
const CODE_PATTERN = /^[a-z]{0,4}-?\d{1,4}[a-z]?$/i;

function normalizeText(text) {
  return String(text ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '') // remove accents: "sí" -> "si"
    .replace(/[^\p{L}\p{N}\s-]/gu, ' ') // drop emojis and punctuation
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase();
}

/**
 * Flattens a WhatsApp Cloud API webhook body into buyer messages.
 * Delivery/read status callbacks are ignored.
 */
function parseWhatsAppWebhook(body) {
  if (Array.isArray(body?.messages)) return parseWhapiWebhook(body);
  const result = [];
  for (const entry of body?.entry ?? []) {
    for (const change of entry?.changes ?? []) {
      const value = change?.value ?? {};
      const phoneNumberId = value.metadata?.phone_number_id ?? null;
      const names = new Map((value.contacts ?? []).map((c) => [c.wa_id, c.profile?.name ?? null]));

      for (const msg of value.messages ?? []) {
        const text =
          msg.text?.body ??
          msg.interactive?.button_reply?.title ??
          msg.interactive?.list_reply?.title ??
          msg.button?.text ??
          null;
        const buttonId = msg.interactive?.button_reply?.id ?? msg.button?.payload ?? null;

        result.push({
          phoneNumberId,
          from: msg.from,
          name: names.get(msg.from) ?? null,
          waMessageId: msg.id,
          messageTs: new Date(Number(msg.timestamp) * 1000).toISOString(),
          type: msg.type,
          text,
          buttonId,
          mediaId: msg.image?.id ?? null,
        });
      }
    }
  }
  return result;
}

/**
 * Flattens a Whapi.Cloud webhook body ({ messages, channel_id }) into buyer messages.
 * Own messages (from_me), groups and non-message events are ignored.
 * phoneNumberId is the Whapi channel_id (stored in sellers.wa_phone_number_id).
 */
function parseWhapiWebhook(body) {
  const result = [];
  for (const msg of body.messages) {
    if (!msg || msg.from_me) continue;
    if (msg.chat_id && !String(msg.chat_id).endsWith('@s.whatsapp.net')) continue;
    const reply = msg.reply?.buttons_reply ?? msg.reply?.list_reply ?? null;
    // Whapi prefixes button ids ("ButtonsV3:lia_confirm").
    const buttonId = reply?.id ? String(reply.id).replace(/^[^:]*:/, '') : null;
    const text = msg.text?.body ?? reply?.title ?? msg.link_preview?.body ?? null;
    result.push({
      phoneNumberId: body.channel_id ?? null,
      from: String(msg.from ?? String(msg.chat_id ?? '').split('@')[0]),
      name: msg.from_name ?? null,
      waMessageId: msg.id,
      messageTs: new Date(Number(msg.timestamp) * 1000).toISOString(),
      type: reply ? 'interactive' : msg.type === 'link_preview' ? 'text' : msg.type,
      text,
      buttonId,
      // Whapi media: download with GET https://gate.whapi.cloud/media/<id> (Bearer token).
      mediaId: msg.image?.id ?? null,
    });
  }
  return result;
}

/**
 * Decides the route of a buyer message without calling the LLM.
 * Returns { route: 'confirm' | 'reject' | 'code' | 'free_text' | 'unsupported', code? }.
 */
function classifyMessage({ type, text, buttonId }) {
  if (buttonId === 'lia_confirm') return { route: 'confirm' };
  if (buttonId === 'lia_reject') return { route: 'reject' };
  if (type === 'image') return { route: 'voucher' }; // photo of the payment voucher (comprobante)

  if (!text) return { route: 'unsupported' };

  const normalized = normalizeText(text);
  if (CONFIRM_WORDS.has(normalized)) return { route: 'confirm' };
  if (REJECT_WORDS.has(normalized)) return { route: 'reject' };

  const candidate = normalized.replace(/^(codigo|cod)\s+/, '');
  if (!candidate.includes(' ') && CODE_PATTERN.test(candidate)) {
    return { route: 'code', code: candidate.toUpperCase() };
  }

  return { route: type === 'text' || type === 'interactive' || type === 'button' ? 'free_text' : 'unsupported' };
}

module.exports = { parseWhatsAppWebhook, parseWhapiWebhook, classifyMessage, normalizeText };
