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
| App de Meta con **WhatsApp Cloud API** | Número que atiende Lia | En modo prueba solo se puede escribir a pocos números verificados: registrar los teléfonos de la demo |
| Credencial **Supabase** (URL + service role key) | Llamar a las funciones (RPC) | La service role key va solo en n8n, nunca en la app móvil |
| Credencial **Anthropic** | AI Agent | Ver `prompts/lia-system-prompt.md` |
| Variable `LIA_PAY_BASE_URL` | URL de la página de pago simulada | `https://<tu-n8n>/webhook/lia-pay` |

Llamadas a Supabase: nodo **HTTP Request**
`POST {{SUPABASE_URL}}/rest/v1/rpc/<funcion>` con headers `apikey` y `Authorization: Bearer <service role>`
y el body JSON con los parámetros `p_...`. (El nodo Supabase de n8n trabaja con tablas, no con funciones.)

Llamadas a WhatsApp: nodo **HTTP Request**
`POST https://graph.facebook.com/<version>/{{phone_number_id}}/messages` con el body que arma
`code/lia-messages.js`. Usamos HTTP Request y no el nodo de WhatsApp porque necesitamos mensajes
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
                       → Split Out (una por notificación)
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
