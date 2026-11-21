# Lia — Live Interactive Assistant

Asistente de ventas por WhatsApp para lives de TikTok. La compradora escribe el código de la prenda, Lia le
muestra la foto, respeta el orden de llegada y le envía un **QR de pago que vence en 5 minutos**: nadie paga
tarde y no hay pagos dobles.

| Parte | Dónde | Estado |
|---|---|---|
| Reglas de negocio | [`CLAUDE.md`](CLAUDE.md) | Fuente de verdad |
| Plan | [`implementation-plan.md`](implementation-plan.md) | App móvil + Supabase + n8n |
| Base de datos (Supabase) | [`supabase/`](supabase) | Migraciones aplicadas en el proyecto `lia` |
| Agente Lia (n8n) | [`n8n/`](n8n) | 7 workflows desplegados en `marilyncd12.app.n8n.cloud` |
| App móvil de la vendedora (Android) | — | Pendiente |

## Comandos

```bash
npm install          # dependencias de los tests (PGlite)
npm test             # tests de n8n y de la base de datos
npm run db:push:dry  # ver migraciones pendientes en Supabase
npm run db:push      # aplicar migraciones
node n8n/scripts/deploy.mjs   # subir los workflows a n8n
```

Las keys van en un `.env` local (`SUPABASE_ACCESS_TOKEN`, `SUPABASE_PROJECT_REF`, `N8N_API_KEY`) o en `~/.lia-n8n-key`. **Nunca se suben al repo.**

## Pendiente para la demo

- Cargar el token real de WhatsApp Cloud API en la credencial `WhatsApp Lia` de n8n.
- Vencimiento de los 5 minutos: activar el Schedule de `lia-expiry` durante el live o usar `pg_cron` (ver `n8n/README.md`).
- Borrar los datos de prueba de Supabase ("Tienda Demo (prueba)", prenda A12).
