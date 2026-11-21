# Lia en n8n — cómo lo construimos

Esta carpeta tiene todo lo que necesita la persona que arma Lia en n8n. Reglas de negocio:
`CLAUDE.md` en la raíz.

```text
n8n/
  README.md                    esta guía (workflows nodo por nodo)
  CONTRACT.md                  funciones de Supabase que n8n necesita (contrato con la base de datos)
  prompts/lia-system-prompt.md prompt y herramientas del AI Agent
  code/whatsapp-inbound.js     parseo del webhook + router (código / SI / no / texto libre)
  code/lia-messages.js         todos los mensajes de Lia (foto con botones, QR, avisos)
  test/                        tests de los dos archivos de código (npm test)
```

## 1. La idea en una frase

> **n8n es el mensajero, Supabase decide y el LLM solo conversa.**

- n8n recibe los mensajes de WhatsApp, llama a funciones de Supabase y envía lo que corresponda.
- Supabase decide quién es primero, quién tiene la reserva, cuándo vence el QR y si un pago vale.
- El LLM (AI Agent) solo atiende preguntas libres ("¿hacen envíos?", "¿tienen el vestido rojo?").
- Códigos (`A12`), "SI" y los botones **no pasan por el LLM**: los resuelve un router con reglas fijas.
  Es más rápido, no cuesta tokens y no se equivoca en lo importante.

## 2. Qué hay que preparar

| Qué | Para qué | Nota |
|---|---|---|
| n8n con **HTTPS público** | WhatsApp solo envía webhooks a una URL https | n8n Cloud, o n8n local con un túnel (cloudflared / ngrok) |
| Zona horaria de n8n `America/La_Paz` | Para que "Válido hasta 20:35" sea la hora de Bolivia | Variable `GENERIC_TIMEZONE` |
| Canal de **Whapi.Cloud** (https://gate.whapi.cloud) | Número que atiende Lia | Credencial n8n `Whapi Lia` (Header Auth: `Authorization` = `Bearer <token>`). Webhook del canal: `https://<tu-n8n>/webhook/lia-whatsapp`. En Supabase, `sellers.wa_phone_number_id` = `channel_id` del canal |
| Credencial **Supabase** (URL + service role key) | Llamar a las funciones (RPC) | La service role key va solo en n8n, nunca en la app móvil |
| Credencial **Anthropic** | AI Agent | Ver `prompts/lia-system-prompt.md` |
| Variable `LIA_PAY_BASE_URL` | URL de la página de pago simulada | `https://<tu-n8n>/webhook/lia-pay` |

Llamadas a Supabase: nodo **HTTP Request**
`POST {{SUPABASE_URL}}/rest/v1/rpc/<funcion>` con headers `apikey` y `Authorization: Bearer <service role>`
y el body JSON con los parámetros `p_...`. (El nodo Supabase de n8n trabaja con tablas, no con funciones.)

Llamadas a WhatsApp: nodo **HTTP Request**
`POST https://gate.whapi.cloud/messages/<text|image|interactive>` (Whapi.Cloud) con el body que arma
`toWhapi()` de `code/lia-messages.js`, credencial `Whapi Lia`. El token nunca va en el repo. Usamos HTTP Request y no el nodo de WhatsApp porque necesitamos mensajes
interactivos (foto + botones "Sí, es esta" / "No es esta").

## 3. Workflows

### WF1 — `lia-inbound` (el corazón)

```text
Webhook (GET verificación + POST mensajes)
  → Respond to Webhook 200 (responder YA; WhatsApp reintenta si tardamos)
  → Code: parseWhatsAppWebhook + classifyMessage   (copiar de code/whatsapp-inbound.js)
  → Switch por route
      code       → Execute Workflow "lia-request-garment"
      confirm    → HTTP RPC lia_confirm_request → Code buildConfirmReply → (si no es null) enviar WhatsApp
                   → Execute Workflow "lia-dispatch" (manda el QR o el puesto en la fila al instante)
      reject     → HTTP RPC lia_cancel_request → enviar "Listo, escribe el código de la prenda que quieres"
      free_text  → AI Agent (prompt + herramientas) → enviar la respuesta como texto
      unsupported→ enviar "Lia: por ahora solo leo texto. Escribe el código de la prenda."
```

- Verificación del webhook (GET): devolver `hub.challenge` si `hub.verify_token` coincide con el nuestro.
- El orden de llegada usa `messageTs` (hora del mensaje en WhatsApp), **no** la hora en que n8n lo procesa.
- Duplicados: WhatsApp reintenta webhooks; Supabase los descarta por `wa_message_id` (`status: duplicate`).

### WF2 — `lia-request-garment` (sub-workflow, también es la herramienta `pedir_prenda`)

```text
Execute Workflow Trigger (phoneNumberId, from, name, code, waMessageId, messageTs)
  → HTTP RPC lia_request_garment
  → Code: buildRequestReply
  → IF no es null → HTTP enviar WhatsApp
  → devolver el resultado (para el AI Agent)
```

Resultado `pending_confirmation` = foto de la prenda (desde la app de la vendedora) con los botones.

### WF3 — `lia-dispatch` (envía las notificaciones)

```text
Schedule cada 10 s  ─┐
Execute Workflow ────┴→ HTTP RPC lia_claim_notifications (p_limit 20)
                       (el nodo HTTP ya devuelve un item por notificación)
                       → Code: buildNotificationMessage
                       → HTTP enviar WhatsApp
                       → HTTP RPC lia_mark_notification_sent
```

- Todas las notificaciones nacen en Supabase por un cambio de estado (QR, fila, pago, vencido, recordatorio).
  n8n nunca "decide" avisar algo: solo entrega lo que está en la cola.
- `lia_claim_notifications` **reserva** las filas antes de enviarlas. Sin eso, el cron y el disparo
  inmediato podrían mandar el mismo QR dos veces.
- Si el envío a WhatsApp falla, no se marca como enviada y se reintenta en la próxima vuelta.

### WF4 — `lia-expiry` (los 5 minutos)

```text
Schedule cada 30 s → HTTP RPC lia_expire_reservations → IF hubo cambios → Execute Workflow "lia-dispatch"
```

Vence los QR, avisa "tu QR venció", manda el recordatorio de 1 minuto y le da el QR a la siguiente de la fila.

### WF5 — `lia-pay` (pago simulado, mientras no haya banco)

```text
GET  /webhook/lia-pay?token=…          → RPC lia_get_payment_qr → Respond HTML (prenda, monto, cuenta regresiva, botón Pagar)
POST /webhook/lia-pay/confirm (token)  → RPC lia_confirm_payment (p_provider 'lia_pay_mock') → Execute "lia-dispatch"
                                        → Respond HTML "Pago confirmado" o "QR vencido"
```

- Archivo listo para importar: `workflows/lia-pay.json` (reemplazar `__SUPABASE_URL__` y elegir la credencial Supabase).
- El QR que recibe la compradora apunta a esta página. En la demo se escanea con la cámara del celular.
- Si pasaron los 5 minutos, la página muestra **"QR vencido"** y Supabase también rechaza el pago
  (doble seguro: aunque alguien llame a la URL a mano, no se puede pagar).
- Cuando haya banco: se reemplaza WF5 por el webhook del banco, que llama a la misma `lia_confirm_payment`.

### Sub-workflows de herramientas del agente

| Sub-workflow | RPC |
|---|---|
| `lia-search-catalog` | `lia_search_catalog` |
| `lia-customer-status` | `lia_customer_status` |
| `lia-request-garment` | WF2 |

## 4. La conversación completa (lo que ve la compradora)

```text
Compradora: A12
Lia:        [foto del vestido] ¿Es esta prenda? A12 · Vestido rojo · talla M · Bs 150,00
            [Sí, es esta] [No es esta]
Compradora: (toca "Sí, es esta")
Lia:        [imagen del QR] ¡La A12 es tuya si pagas a tiempo! Monto: Bs 150,00
            Válido hasta: 20:35 (5 minutos)
Lia:        Te queda 1 minuto para pagar la A12.          ← solo si no pagó todavía
Lia:        ¡Pago confirmado! La A12 es tuya.             ← si pagó a tiempo
Lia:        Tu QR de la A12 venció y ya no se puede pagar. ← si no pagó
```

## 5. Orden de trabajo sugerido

1. Credenciales + webhook de WhatsApp respondiendo (WF1 solo con parseo y un eco).
2. WF2 con `lia_request_garment` → la foto con botones llega al celular.
3. WF3 + `confirm` en WF1 → llega la imagen del QR.
4. WF5 → pagar desde la página y recibir "Pago confirmado".
5. WF4 → probar el vencimiento con el reloj real (bajar `reservation_minutes` a 1 para probar rápido).
6. AI Agent para texto libre.

Cada paso depende de que existan las funciones de `CONTRACT.md` en Supabase.

## 6. Tests del código de los nodos

```bash
npm test
```

Prueban el parseo del webhook, el router y todos los mensajes. Si cambias un texto o una regla del
router, cambia también el archivo en `code/` y vuelve a pegarlo en el nodo Code correspondiente.

## Pegar el código en n8n

Los nodos Code de n8n no pueden importar archivos locales: copia las funciones que uses.
`lia-pay-page.js` usa `formatAmount` y `formatTime` de `lia-messages.js`; pégalas en el mismo nodo.

## Subir los workflows a n8n (deploy)

Los 7 workflows están en `n8n/workflows/` y se suben a `https://marilyncd12.app.n8n.cloud` con:

```bash
node n8n/scripts/deploy.mjs            # crea o actualiza cada workflow por nombre
node n8n/scripts/deploy.mjs --dry-run  # solo genera n8n/dist/*.json para importar a mano
```

- API key: archivo `~/.lia-n8n-key` (solo la key) o variable `N8N_API_KEY`. Nunca en el repo.
- Opcionales: `SUPABASE_URL`, `WA_VERIFY_TOKEN` (texto que elijas; el mismo que pongas en Meta), `WA_GRAPH_VERSION`.
- Credenciales en n8n con estos nombres exactos: `Supabase Lia`, `WhatsApp Lia`, `Anthropic Lia`.
- Los workflows se suben **inactivos**; se activan desde el editor cuando Supabase y WhatsApp estén listos.

## Límite de ejecuciones (plan Starter: 2.500 por mes)

Los Schedule de `lia-dispatch` (cada 10 s) y `lia-expiry` (cada 30 s) están **desactivados**: activos gastarían
miles de ejecuciones por día. Hoy `lia-dispatch` se dispara cuando alguien confirma o paga. Para el vencimiento
de los 5 minutos hay dos caminos: activar el Schedule de `lia-expiry` **solo durante el live**, o (mejor) que
Supabase corra `lia_expire_reservations` con `pg_cron` y avise a n8n con un Database Webhook solo cuando haya cambios.

## Pruebas en la instancia (sin Supabase ni WhatsApp reales)

`n8n/test/fixtures/lia-mock-supabase.json` es un Supabase simulado dentro de n8n. Con
`SUPABASE_URL=https://marilyncd12.app.n8n.cloud/webhook/<webhookId>/mock-supabase` el deploy apunta los workflows
al simulador. Probado el 2026-11-21: verificación del webhook de WhatsApp (200 / 403), página de pago (activa, vencida,
confirmar), código → foto con botones, "SI" → reserva + dispatch, "no" → cancelación, texto libre → agente con
`gpt-6-luna`. El único paso que falla es el envío a WhatsApp, porque la credencial `WhatsApp Lia` todavía tiene un token de prueba.
Antes de usar Supabase real: volver a correr el deploy con el `SUPABASE_URL` real.
