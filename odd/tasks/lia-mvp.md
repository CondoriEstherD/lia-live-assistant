# Feature: lia-mvp

Objective: one system — seller Android app + Supabase + Lia (n8n WhatsApp agent) + simulated 5-minute QR payments.
Branch: `feat/lia-mvp` · Delivery: ask-on-risk · Test runner: `npm test` (node:test + PGlite for SQL).

## Specs

- S1 — Single system: "también va a haber una parte móvil, que es la que maneja ahorita el que vende por live, por eso lo tenemos que juntar porque todo será uno solo".
- S2 — Lia reads from the app data: "Lia saca información de la app móvil". The catalog the seller loads in the app (Supabase) is the only source Lia uses.
- S3 — Live code flow: "el emprendedor pueda administrar sus prendas, subir sus prendas y administrar sus ventas, qué prendas ya están vendidas"; "la persona que quiere comprar la prenda tiene que escribir en el chat solo el código, la IA le tiene que mostrar la prenda".
- S4 — First-come order and no double payment: "las personas no saben quién es el primero"; "cuando a la segunda persona que le pasa el QR paga, la primera también paga, entonces eso genera conflictos".
- S5 — 5-minute QR sent as image: "a la otra clienta no le tiene que permitir pagar cuando no paga en el tiempo establecido, por eso el QR que le mando solo puede tener una duración de 5 min"; "solo tiene que mandarle foto del QR y ya con eso estaría listo".
- S6 — Notifications: "le tiene que dar una notificación al cliente de que esa prenda ya se pagó".
- S7 — Bank integration deferred: "lo de conectar con el banco lo dejamos para después ... solo déjalo explicado". Hackathon uses the simulated provider (CLAUDE.md §6.3).
- S8 — Seller app scope from `implementation-plan.md` (account, garments, home stats, profile) is kept and merged.

## Tasks

| ID | Specs | Route | Status | Commit |
|---|---|---|---|---|
| T1 | S1, S2, S8 | inline | done | docs commit |
| T2 | S2–S6 | — | paused by user (L4); draft SQL left uncommitted in `supabase/` | — |
| T3 | S3–S6 | inline | n8n guide, contract, node code + tests done; workflows still to build in n8n | n8n commit |
| T4 | S5, S7 | inline | pay page code done (`n8n/code/lia-pay-page.js`); WF5 to build in n8n | n8n commit |
| T5 | S1, S8 | inline | pending | — |

- T1 — Unify `implementation-plan.md` and `CLAUDE.md` into one architecture.
- T2 — Supabase schema, SQL functions (queue, reservation, QR, payment, manual sale), notification outbox, tests.
- T3 — n8n workflows for Lia (WhatsApp inbound, reservation, expiry, notifications).
- T4 — Lia Pay mock (QR image + payment page with 5-minute countdown).
- T5 — Android app (Kotlin + Compose) connected to Supabase. No Android SDK on this machine: build must be verified on a teammate's Android Studio.

## Log

- L1 (2026-11-21, user request, verbatim): "es que no te olvides que también va a haber una parte móvil, que es la que maneja, ahorita el que vende por live, por eso lo tenemos que juntar porque todo será uno solo, así ayúdanos con Lia el agente que va trabajar con la app móvil, pero Lia saca información de la app móvil, quiero que comencemos con todo, corrige de una vez".
- L2 — Review found `implementation-plan.md` deferred n8n and payments; T1 merges it with CLAUDE.md. Garment model adopted from the plan (one code = one garment record with stock).
- L3 — Environment: Node 26 available; no Docker, psql, Java or Android SDK. SQL is tested with PGlite; Android build cannot be verified here.
- Note: quotes in Specs and L1 have spelling normalized from the user's original messages.
- L4 (2026-11-21, user, verbatim): "continua peor no hagas la base de datos quiero que prepares realizando, la parte de n8n, para la parte del eagente tambien tenemos que agregar, como lo vamos hacer".
- L5 — n8n prepared: `n8n/README.md` (5 workflows), `n8n/CONTRACT.md` (RPC contract incl. new `lia_cancel_request`, `lia_search_catalog`, `lia_customer_status`, `lia_claim_notifications`), agent prompt, node code with 14 passing tests (`npm test`). Tests were written after the code (no RED observed). Workflows not built or run in n8n yet (no n8n instance here).
