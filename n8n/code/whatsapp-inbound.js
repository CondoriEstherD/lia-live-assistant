// Parsing and routing of WhatsApp Cloud API webhooks for Lia.
// Used inside n8n Code nodes (copy the functions) and tested with `npm test`.

const CONFIRM_WORDS = new Set([
  'si', 'sii', 'siii', 'si es', 'si esa', 'es esa', 'esa', 'esa es', 'ok', 'okey', 'dale', 'la quiero', 'confirmo',
]);
const REJECT_WORDS = new Set(['no', 'no es', 'no es esa', 'otra', 'cancelar', 'cancela']);

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
        });
      }
    }
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

module.exports = { parseWhatsAppWebhook, classifyMessage, normalizeText };
