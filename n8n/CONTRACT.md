# Contrato n8n ↔ Supabase

n8n solo habla con Supabase a través de estas funciones (RPC, con la service role key).
Quien implemente la base de datos debe respetar nombres, parámetros y respuestas; quien arme n8n
puede trabajar en paralelo contra este contrato. Todas devuelven `jsonb` salvo que se indique.

Tiempos en ISO 8601 (UTC). `p_wa_phone_number_id` identifica a la vendedora (número de WhatsApp que recibe).

## Mensajes de la compradora

### `lia_request_garment`
Parámetros: `p_wa_phone_number_id`, `p_customer_phone`, `p_customer_name`, `p_code`, `p_wa_message_id`, `p_message_ts`.

| `status` | Campos extra | Lia responde |
|---|---|---|
| `pending_confirmation` | `reservation_id`, `confirm_deadline`, `garment` | Foto + botones |
| `not_found` | `code` | "No encontré la prenda" |
| `sold_out` | `garment` | "Ya se vendió" |
| `no_live` | — | "No hay live activo" |
| `already_requested` | `reservation_id`, `reservation_status`, `garment` | "Ya tienes un pedido en curso" |
| `duplicate`, `unknown_seller` | — | Nada |

`garment` = `{ id, code, name, description, size, color, price, photo_url, status }` (datos cargados desde la app).

### `lia_confirm_request`
Parámetros: `p_wa_phone_number_id`, `p_customer_phone`, `p_wa_message_id`, `p_message_ts`.

| `status` | Campos extra | Nota |
|---|---|---|
| `active` | `reservation_id`, `expires_at`, `garment` | El QR sale por la cola (`reservation_qr`) |
| `queued` | `reservation_id`, `position`, `garment` | El puesto sale por la cola (`queued`) |
| `confirmation_expired`, `sold_out` | `garment` | |
| `limit_reached` | `limit` | |
| `nothing_to_confirm`, `duplicate`, `unknown_seller` | — | |

### `lia_cancel_request`
Parámetros: `p_wa_phone_number_id`, `p_customer_phone`, `p_wa_message_id`, `p_message_ts`.
Cancela el pedido `pending_confirmation` más reciente de la compradora ("No es esta").
Respuesta: `{ status: 'cancelled' | 'nothing_to_cancel' | 'duplicate' }`.

## Herramientas del agente (solo lectura)

### `lia_search_catalog`
Parámetros: `p_wa_phone_number_id`, `p_query`, `p_limit` (por defecto 5).
Respuesta: `{ items: [garment, ...] }` — solo prendas `in_catalog` con stock, buscando en código, nombre, color, talla y descripción.

### `lia_customer_status`
Parámetros: `p_wa_phone_number_id`, `p_customer_phone`.
Respuesta: `{ items: [{ code, name, status, expires_at, position }] }` — pedidos abiertos de esa compradora.

## Tiempo y cola de notificaciones

### `lia_expire_reservations`
Sin parámetros. Respuesta: `{ expired, cancelled, reminders, promoted }` (contadores).

### `lia_claim_notifications`
Parámetros: `p_limit`. **Devuelve filas** `{ id, kind, payload, customer_phone, wa_phone_number_id }`
y las marca como tomadas (`claimed_at`) en la misma transacción (`for update skip locked`), para que
dos ejecuciones de n8n no envíen el mismo mensaje. Una notificación tomada y no enviada vuelve a estar
disponible después de 2 minutos.

### `lia_mark_notification_sent`
Parámetros: `p_id`.

Tipos (`kind`) y su `payload`:

| `kind` | `payload` |
|---|---|
| `reservation_qr` | `reservation_id`, `qr_token`, `amount`, `expires_at`, `garment` |
| `queued` | `reservation_id`, `position`, `garment` |
| `payment_reminder` | `reservation_id`, `expires_at`, `garment` |
| `payment_confirmed` | `reservation_id`, `amount`, `garment` |
| `qr_expired` | `reservation_id`, `garment` |
| `sold_out` | `reservation_id`, `garment` |
| `confirmation_expired` | `reservation_id`, `garment` |
| `live_ended` | `reservation_id`, `garment` |

Las notificaciones para la vendedora (`garment_sold`, `late_payment`) no pasan por n8n: las lee la app.

## Pago simulado (Lia Pay)

### `lia_get_payment_qr`
Parámetros: `p_token`. Respuesta: `{ status: 'active' | 'expired' | 'paid' | 'cancelled' | 'not_found', amount, expires_at, seconds_left, garment }`.
Un QR `active` pasado de hora se informa como `expired` aunque el cron todavía no haya corrido.

### `lia_confirm_payment`
Parámetros: `p_token`, `p_amount`, `p_paid_at`, `p_provider` (`'lia_pay_mock'`), `p_external_id`, `p_raw_payload`.
Respuesta: `{ status: 'paid' | 'expired' | 'already_paid' | 'amount_mismatch' | 'late' | 'not_found' }`.
Con `lia_pay_mock`, un QR vencido devuelve `expired` y **no registra pago**.
