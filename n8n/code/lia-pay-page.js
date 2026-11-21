// HTML of the simulated payment page (Lia Pay) served by the n8n workflow `lia-pay`.
// Input: the lia_get_payment_qr result and the token. Used in a Code node before
// "Respond to Webhook" (Content-Type: text/html).

const { formatAmount, formatTime, DEFAULT_CONFIG } = require('./lia-messages');

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function layout(title, body) {
  return `<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>
  body{margin:0;font-family:system-ui,sans-serif;background:#fff5f8;color:#2b1b22;display:flex;justify-content:center}
  main{max-width:420px;width:100%;padding:24px 16px;text-align:center}
  .brand{color:#d6336c;font-weight:800;font-size:28px}
  .card{background:#fff;border-radius:16px;padding:20px;box-shadow:0 4px 16px #0001;margin-top:16px}
  .amount{font-size:32px;font-weight:700;margin:8px 0}
  .timer{font-size:20px;color:#d6336c;font-weight:700}
  button{width:100%;padding:16px;border:0;border-radius:12px;background:#d6336c;color:#fff;font-size:18px;font-weight:700}
  button:disabled{background:#bbb}
  .muted{color:#7a6870;font-size:14px}
</style></head><body><main><div class="brand">Lia Pay</div><div class="muted">Pago simulado · demo</div>${body}</main></body></html>`;
}

function buildPayPage(result, token, config = DEFAULT_CONFIG) {
  if (result.status === 'not_found') {
    return layout('QR no válido', '<div class="card"><h2>QR no válido</h2></div>');
  }

  const g = result.garment ?? {};
  const info = `<p>${escapeHtml(g.code)} · ${escapeHtml(g.name)}</p>
    <div class="amount">${escapeHtml(formatAmount(result.amount, config))}</div>`;

  if (result.status === 'paid') {
    return layout('Pago confirmado', `<div class="card">${info}<h2>✅ Pago confirmado</h2></div>`);
  }
  if (result.status !== 'active') {
    return layout('QR vencido', `<div class="card">${info}<h2>⛔ QR vencido</h2>
      <p class="muted">Este QR ya no se puede pagar.</p></div>`);
  }

  return layout('Pagar', `<div class="card">${info}
    <p>Válido hasta ${escapeHtml(formatTime(result.expires_at, config))}</p>
    <p class="timer" id="t"></p>
    <form method="post" action="${escapeHtml(config.payBaseUrl)}/confirm">
      <input type="hidden" name="token" value="${escapeHtml(token)}">
      <button id="b" type="submit">Pagar</button>
    </form></div>
<script>
  let s=${Number(result.seconds_left) || 0};const t=document.getElementById('t'),b=document.getElementById('b');
  function tick(){if(s<=0){t.textContent='QR vencido';b.disabled=true;return}
    t.textContent=Math.floor(s/60)+':'+String(s%60).padStart(2,'0');s--;setTimeout(tick,1000)}
  tick();
</script>`);
}

module.exports = { buildPayPage, escapeHtml };
