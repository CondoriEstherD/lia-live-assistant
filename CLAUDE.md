# Lia (Live Interactive Assistant) — Contexto del proyecto

> **Lia** es la asistente de ventas para lives (TikTok → WhatsApp). El agente se presenta a las clientas como "Lia".
> Hackathon Nodo Cero · Santa Cruz, Bolivia · Stack: n8n + Supabase + panel web (Clerk)
> Este archivo es la fuente de verdad del negocio. Toda IA o persona que implemente debe leerlo antes de escribir código.

## 1. El problema (uno solo)

En las ventas por live de TikTok (ropa, zapatos, utensilios, de todo), la vendedora muestra
una prenda, dice su **código** (ej. `A12`) y las clientas escriben ese código por WhatsApp.
Llegan decenas de mensajes a la vez y pasa esto:

1. Nadie sabe con certeza **quién escribió primero**.
2. La vendedora envía su QR a la primera; tiene ~5 minutos para pagar.
3. Si no paga, le envía el QR a la segunda.
4. **Conflicto:** la primera paga tarde y la segunda también paga. Hay dos pagos por una sola prenda,
   devoluciones manuales y clientas enojadas.
5. Además, la clienta a veces se equivoca de código y compra una prenda que no quería.

**Objetivo:** que cada unidad tenga **un solo dueño a la vez**, con orden de llegada justo,
reserva con vencimiento y pago que no se pueda cruzar.

## 2. Principio de arquitectura (no negociable)

> **La IA conversa; la base de datos decide.**

- El LLM solo **interpreta** mensajes (¿qué código?, ¿confirmó?, ¿qué pregunta?). Nunca decide stock ni pagos.
- Quién es primero, quién tiene la reserva, cuándo vence y quién ganó la prenda lo deciden
  **funciones SQL transaccionales en Supabase (Postgres)**, nunca el agente ni n8n con lógica suelta.
- Motivo: dos ejecuciones de n8n en paralelo pueden leer "stock = 1" al mismo tiempo. Solo un
  bloqueo en la base de datos (`SELECT ... FOR UPDATE` o `UPDATE ... WHERE` condicional) evita la doble venta.

## 3. Actores

| Actor | Qué hace |
|---|---|
| Vendedora (seller) | Carga prendas con código y foto, abre/cierra el live, confirma pagos dudosos, ve ventas |
| Clienta (customer) | Escribe el código por WhatsApp, confirma la prenda, paga con el QR de su reserva dentro de los 5 min |
| Agente (n8n + LLM) | Responde, muestra la foto, gestiona la fila y los avisos |
| Supabase | Fuente de verdad: catálogo, stock, fila, reservas, pagos |

## 4. Flujo principal

```
Clienta escribe "A12" por WhatsApp
  → n8n recibe el webhook (guarda el timestamp de WhatsApp)
  → Detecta el código (regex primero; LLM solo si el mensaje es ambiguo)
  → Busca la variante del código en el live activo
  → Responde con FOTO + nombre + talla + precio: "¿Es esta? Responde SI"
  → Clienta responde "SI"
  → Supabase: join_queue(variant, customer, message_ts)
       ├─ hay unidad libre → reserva ACTIVE, expires_at = now + 5 min
       │     → PaymentProvider.createQr() → QR único que vence en 5 min → se envía con la hora límite
       └─ no hay unidad libre → queda en fila: "Eres la #3 en la fila, te avisamos"
  → Clienta paga dentro de los 5 min
       → el proveedor avisa (webhook) → confirm_payment() → reserva PAID, stock descontado
       → notificaciones de pago (sección 6.4)
  → Si pasan los 5 min sin pago:
       → el QR ya no acepta pagos → reserva EXPIRED → aviso "tu QR venció, ya no se puede pagar"
       → recién entonces se crea reserva + QR nuevo para la siguiente de la fila
```

Confirmar con la foto antes de reservar es obligatorio: resuelve el "me equivoqué de prenda".

## 5. Reglas de negocio

- **R1 — Orden de llegada:** se ordena por el `timestamp` del mensaje que entrega WhatsApp
  (precisión de segundos); empate → por `received_at` del servidor y luego por id. El orden se
  fija al recibir el **código**, no al confirmar con "SI" (la confirmación solo tiene un plazo, ej. 2 min).
- **R2 — Un dueño por unidad:** como máximo hay `stock` reservas `ACTIVE` + ventas `PAID` por variante.
  Se garantiza en SQL, no en n8n.
- **R3 — Un solo QR activo por unidad:** nunca se envía el QR a la siguiente clienta mientras la reserva
  anterior siga `ACTIVE`.
- **R4 — QR de 5 minutos (regla dura):** cada QR es **único por reserva** y **solo vale 5 minutos**
  (`qr_expires_at = expires_at`). Pasado ese tiempo la clienta **no puede pagar**: el QR queda inválido,
  la reserva pasa a `EXPIRED`, se le avisa y se pasa a la siguiente de la fila. Ver sección 6.
- **R4b — Catálogo solo desde la base de datos:** Lia nunca inventa prendas, precios, tallas ni stock.
  Todo dato de producto sale de Supabase en el momento de responder; si un código no existe o no está
  en el live activo, lo dice.
- **R5 — Pago tardío (red de seguridad):** con R4 no debería ocurrir, pero si un proveedor real deja
  pasar un pago, gana el **primer pago válido dentro de una reserva activa**. Un pago con hora
  posterior a `expires_at` queda `LATE`:
  - si la prenda sigue libre → se le puede asignar (decide la vendedora o una regla automática);
  - si ya la ganó otra → se ofrece otra prenda/talla, saldo a favor o devolución (manual).
- **R6 — Límite por clienta:** máximo N reservas activas simultáneas por clienta (evita acaparar).
- **R7 — Auditoría:** cada mensaje y cada cambio de estado se guarda (tabla `events`). Ante un reclamo
  se demuestra quién llegó primero.

## 6. Pagos (la parte crítica)

### 6.1 Por qué hace falta un QR que vence

El conflicto nace porque el **QR estático** de la vendedora sirve para siempre: aunque la reserva
venza, la clienta igual puede pagar y aparece el doble pago. La solución de Lia es:

> **Cada reserva tiene su propio QR, con monto exacto, de un solo uso y válido solo 5 minutos.**
> Si la clienta no paga a tiempo, el QR deja de aceptar pagos. No hay forma de que pague tarde.

Un QR estático **no puede** cumplir esta regla. Por eso Lia nunca envía el QR fijo de la vendedora.

### 6.1b Cómo recibe el QR la clienta: una imagen en el chat (prioridad: que pagar sea fácil)

La clienta ya sabe pagar así: recibe la **foto del QR** por WhatsApp, la guarda o la escanea desde la
app de su banco y paga. Lia respeta ese hábito; no hay links, formularios ni apps nuevas.

- Lia envía **una sola imagen** (mensaje de tipo imagen de WhatsApp) con el mismo formato que un QR bancario:
  ```
  ┌──────────────────────────┐
  │        [logo / tienda]   │
  │                          │
  │        ▓▓ QR ▓▓          │
  │                          │
  │  Prenda:  A12 · Vestido M│
  │  Monto:   Bs 150,00      │
  │  Válido hasta: 20:35     │  ← hora exacta, no fecha lejana
  └──────────────────────────┘
  ```
- Debajo, un texto corto: *"Lia: la A12 es tuya si pagás antes de las 20:35 (5 min). Escaneá o guardá este QR
  y pagalo desde tu app del banco."*
- La clienta **no envía comprobante**: el pago se confirma solo (webhook del proveedor) y Lia le avisa.
- La imagen se genera por reserva (`createQr` → `qr_image_url` en Supabase Storage) y se envía con la
  API de WhatsApp como imagen + caption.
- Un QR por mensaje, sin texto largo: menos fricción, menos errores.

### 6.2 Diseño: proveedor de pagos intercambiable

Toda la lógica de cobro pasa por una sola interfaz (`PaymentProvider`). El resto del sistema
(n8n, Supabase, Lia) no sabe si detrás hay un simulador o un banco real.

| Operación | Entrada | Salida |
|---|---|---|
| `createQr` | reservation_id, amount, expires_at | qr_image, external_id, qr_expires_at |
| `cancelQr` | external_id | ok (se llama al vencer o cancelar la reserva) |
| webhook de pago | payload del proveedor | external_id, amount, paid_at → `confirm_payment()` |

`confirm_payment()` (SQL, transaccional) solo acepta el pago si la reserva sigue `ACTIVE` y
`paid_at <= expires_at`; si no, aplica R5.

### 6.3 Cómo lo manejamos por etapa

**Hackathon (ahora) — proveedor simulado "Lia Pay":**
- `createQr` genera la **imagen del QR** (formato 6.1b) con un token único y la sube a Supabase Storage;
  Lia la envía por WhatsApp como imagen.
- Como no hay banco, al escanearlo con la cámara abre una página de pago simulada (`/pay/<token>`) que hace
  de "app del banco": muestra prenda, monto y **cuenta regresiva de 5 min**. Antes del vencimiento, "Pagar"
  simula el webhook del banco → `PAID`. Después muestra **"QR vencido"** y rechaza el pago.
- La experiencia de la clienta en la demo es la misma que tendrá en producción: recibe la foto del QR, paga, Lia confirma.
- Decirlo con honestidad en el pitch: el cobro está simulado; lo real es la lógica de reserva, fila y vencimiento.

**Plan B de la demo — QR fijo de la vendedora (solo si falla todo lo anterior):**
- Lia envía la imagen del QR fijo de la vendedora con la hora límite en el texto.
- Limitación: ese QR **no vence**, así que no bloquea el pago tardío; solo se detecta y se resuelve con R5.
  No es la solución del producto, es solo un respaldo.

**Producción (después) — banco o pasarela real:**
- Se implementa otro `PaymentProvider` contra la API de QR de un banco o pasarela de pagos de Bolivia.
- `createQr` pide un **QR dinámico de un solo uso** con monto exacto y vencimiento = `expires_at`; la imagen
  que devuelve el banco (o la que armamos con sus datos) se envía igual que en 6.1b.
- El banco notifica el pago por **webhook** → `confirm_payment()`. Sin capturas ni revisión manual.
- Al vencer la reserva se llama `cancelQr` (si la API lo permite) como doble seguro.
- **Validar antes de elegir proveedor:**
  1. ¿Permite vencimiento en **minutos** o solo por día? Si es solo por día, `cancelQr` al vencer es obligatorio.
  2. ¿Ofrece webhook de pago confirmado o hay que consultar el estado periódicamente?
  3. Requisitos para la cuenta de la emprendedora (NIT, cuenta empresa) y comisión por cobro.
- Si un proveedor no garantiza el bloqueo, R5 queda como red de seguridad.

### 6.4 Notificaciones de pago

| Evento | A quién | Mensaje (ejemplo) |
|---|---|---|
| Reserva creada | Clienta | "Lia: la prenda A12 está reservada para vos. Pagá con este QR antes de las 20:35 (5 min)." |
| Falta 1 minuto | Clienta | "Te queda 1 minuto para pagar la A12." |
| Pago confirmado | Clienta que pagó | "¡Pago confirmado! La A12 es tuya. Te contactamos para la entrega." |
| Pago confirmado | Vendedora | Aviso en panel/WhatsApp: "A12 vendida a <nombre> — Bs 150." |
| Pago confirmado y sin stock | Clientas en fila | "La A12 ya fue vendida. ¿Querés ver prendas similares?" |
| QR vencido | Clienta que no pagó | "Tu QR de la A12 venció y ya no se puede pagar. La prenda pasó a la siguiente en la fila." |
| Turno en la fila | Siguiente clienta | "¡Es tu turno! La A12 está reservada para vos por 5 min." + QR nuevo |

Ninguna notificación se envía desde la lógica del agente: se disparan por **cambios de estado** en la base
de datos (evento → n8n), así nunca se avisa algo que no ocurrió.

## 7. Estados

**Reserva (`reservations.status`):**
`PENDING_CONFIRMATION` (mostró foto, espera "SI") → `QUEUED` (en fila) → `ACTIVE` (tiene QR, corre el reloj)
→ `PAID` | `EXPIRED` | `CANCELLED`.

**Pago (`payments.status`):**
`RECEIVED` → `CONFIRMED` (dentro del plazo) | `LATE` (fuera del plazo, solo si el proveedor lo dejó pasar) → `REFUNDED` | `CREDITED` | `REASSIGNED`.

## 8. Modelo de datos (Supabase, borrador)

| Tabla | Campos clave |
|---|---|
| `sellers` | id, clerk_user_id, name, whatsapp_number, reservation_minutes, max_active_per_customer |
| `products` | id, seller_id, name, description, category, base_price |
| `product_variants` | id, product_id, **code** (único por seller), size, color, price, stock |
| `product_images` | id, product_id, url (Supabase Storage), position |
| `live_sessions` | id, seller_id, started_at, ended_at, status |
| `live_items` | live_session_id, variant_id (qué códigos están activos en el live) |
| `customers` | id, seller_id, phone, name |
| `reservations` | id, variant_id, customer_id, live_session_id, status, message_ts, received_at, queue_position, amount_due, expires_at |
| `payment_qrs` | id, reservation_id, provider (`lia_pay_mock` \| banco), external_id, token, qr_image_url, amount, qr_expires_at, status (`ACTIVE`, `PAID`, `EXPIRED`, `CANCELLED`) |
| `payments` | id, reservation_id, payment_qr_id, provider, external_id, amount, paid_at, raw_payload, status |
| `messages` | id, customer_id, direction, body, wa_message_id (único, para deduplicar), wa_timestamp |
| `events` | id, entity, entity_id, type, payload, created_at (auditoría) |

Funciones SQL (RPC) que n8n llama, cada una en una transacción:

- `join_queue(variant_id, customer_id, message_ts)` → reserva ACTIVE o posición en fila.
- `expire_reservations()` → vence reservas y promueve a la siguiente de la fila (cada minuto con `pg_cron` o un Cron de n8n).
- `confirm_payment(external_id, amount, paid_at, raw_payload)` → acepta solo si la reserva está `ACTIVE`
  y el QR no venció; marca `PAID`, descuenta stock y deja el evento; si no, aplica R5.
- `resolve_late_payment(payment_id, action)` → desde el panel (reasignar, saldo a favor, devolución).

`wa_message_id` único evita procesar dos veces el mismo mensaje (WhatsApp reintenta webhooks).

## 9. Workflows de n8n

| Workflow | Disparador | Hace |
|---|---|---|
| WF1 Inbound | Webhook de WhatsApp Cloud API | Dedup → guarda mensaje → clasifica (código / "SI" / pregunta) → enruta |
| WF2 Reserva | Llamado por WF1 | Busca el código en Supabase, envía foto, llama `join_queue`, `createQr` y envía QR con hora límite, o posición en fila |
| WF3 Pago | Webhook del `PaymentProvider` (Lia Pay simulado o banco) | `confirm_payment` → notificaciones de la sección 6.4 |
| WF4 Vencimientos | Cron cada minuto (o webhook de Supabase) | `expire_reservations` → `cancelQr` → avisa "venció" → crea reserva + QR para la siguiente |
| WF4b Recordatorio | Cron cada minuto | Avisa "te queda 1 minuto" a reservas que vencen pronto |
| WF5 Preguntas | Texto libre | Agente responde con datos del catálogo (precio, talla, envío) |

## 10. Panel de la vendedora (web)

- Login (Clerk). Carga de prendas: foto, nombre, precio, tallas/colores, stock y **código** (autogenerado editable).
- Iniciar/cerrar live y elegir qué códigos están activos.
- Vista en vivo por código: quién tiene la reserva, cuánto le queda, la fila.
- Bandeja de pagos `LATE` (red de seguridad) para resolver con un toque.
- Ventas: vendidas, pendientes de entrega, totales del live.

## 11. Fuera de alcance del MVP

Leer comentarios del live de TikTok directamente, envíos, facturación, Instagram/Facebook,
devoluciones automáticas.

## 12. Riesgos y supuestos a validar

- WhatsApp Cloud API: número de prueba con destinatarios limitados en la demo; las conversaciones tienen costo.
- La precisión del timestamp de WhatsApp es de segundos: los empates se resuelven por R1.
- Validar con 3 vendedoras de lives: ¿cuántos pedidos por prenda?, ¿cuántos minutos dan para pagar?,
  ¿qué hacen hoy con el pago tardío?
- El cobro real depende de conseguir una API de QR bancaria o pasarela con vencimiento (ver sección 6.3).

## 13. Convenciones para implementar

- Código, identificadores y comentarios en inglés; documentación de negocio en español.
- Estados como `enum` de Postgres; toda transición de estado pasa por una función SQL y deja un `event`.
- Nunca descontar stock ni cambiar estados desde un nodo de n8n con un `UPDATE` suelto.
