# Implementation Plan — Lia (Live Interactive Assistant)

## Overview

Lia is **one system** with three parts that share a single Supabase database:

| Part | Who uses it | What it does |
| --- | --- | --- |
| **Seller app** (Android, Kotlin + Jetpack Compose) | The seller running the TikTok live | Loads garments with code, photo and price; starts/ends the live; watches reservations in real time; records manual sales; sees statistics |
| **Lia agent** (n8n + LLM + WhatsApp Cloud API) | Buyers on WhatsApp | Receives a garment code, shows the garment, manages the first-come queue, sends a single-use payment QR **as an image** that expires in **5 minutes**, and sends notifications |
| **Supabase** (Postgres + Storage) | Both | Single source of truth: catalog, stock, queue, reservations, payments, sales, notifications |

**Lia never has its own catalog.** Everything Lia says about a garment comes from what the seller loaded in the app.

Business rules live in `CLAUDE.md`. This document describes what to build and in which order.

```text
Seller app ──(Clerk JWT + RLS)──► Supabase ◄──(service role, RPC)── n8n (Lia) ◄──► WhatsApp
                                     ▲                                  │
                                     └──── Lia Pay mock (pay page) ◄────┘ QR image
```

This document is a plan. Publishing to an app store requires a separate decision.

## 1. Key Rule: The Database Decides

Queue order, reservations, stock, QR expiry and payment acceptance are decided **only** by transactional SQL
functions in Supabase. The app and n8n call those functions; they never update stock or statuses with raw
`UPDATE`s. This is what prevents double sales between a WhatsApp reservation and a manual sale in the app.

| Function | Caller | Purpose |
| --- | --- | --- |
| `lia_request_garment` | n8n | Buyer wrote a code → returns garment data from the catalog and opens a pending request |
| `lia_confirm_request` | n8n | Buyer answered "SI" → joins the queue; gets a reservation + QR if a unit is free |
| `lia_expire_reservations` | n8n cron (every minute) | Expires 5-minute reservations and QRs, promotes the next buyer |
| `lia_confirm_payment` | Lia Pay mock / bank webhook | Accepts a payment only if the QR is still valid |
| `lia_get_payment_qr` | Lia Pay mock | Reads QR status for the payment page |
| `lia_pending_notifications` / `lia_mark_notification_sent` | n8n | Notification outbox |
| `record_manual_sale` / `void_sale` | Seller app | Manual sales that respect active reservations |

## 2. Functional Scope

### Seller Account (app)

| Field | Requirement |
| --- | --- |
| Authentication details | Managed by Clerk; sign-in method to be decided |
| WhatsApp number with country code | Required |
| Name | Optional |
| Username | Optional |
| Social media profile links | Optional, for reference |
| Reservation minutes | Defaults to 5 |

After registration, open Home. Sellers edit these details in Profile; WhatsApp remains required.

### Garments (app → Lia)

| Field | Requirement |
| --- | --- |
| Code | Required, unique per seller among non-archived garments (case-insensitive) |
| Selling price | Required, greater than 0 |
| Stock | Defaults to 1 |
| Name | Optional (Lia uses the code when empty) |
| Photo | Optional (Lia sends it to confirm the garment) |
| Size, Color, Description | Optional |
| Initial status | In catalog |

Statuses: **In catalog** (available), **Sold** (stock 0), **Archived** (hidden, history kept).
Available units = `stock − active reservations`. The app shows a **Reserved** badge while a buyer holds a unit.

### Live Session (app)

- **Start live / End live** button on Home. Lia only reserves garments while a live is active.
- **Live board:** per garment code, who holds the reservation, remaining time, and queue length (Supabase Realtime).

### Lia on WhatsApp (n8n)

1. Buyer writes a code (e.g. `A12`).
2. Lia replies with the garment **photo**, name, size and price: "Is this the one? Reply SI".
3. Buyer replies `SI` within 1 minute (no QR before confirming) → queue by WhatsApp message time.
4. When it is the buyer's turn: Lia sends **one QR image** (garment, amount, "Valid until HH:MM") that expires in 5 minutes.
5. Paid in time → "Payment confirmed, A12 is yours"; the seller and queued buyers are notified.
6. Not paid in time → "Your QR expired and can no longer be paid"; next buyer gets a new QR.

### Payments

- **Hackathon:** simulated provider "Lia Pay". The QR opens a payment page with a 5-minute countdown that
  rejects payment after expiry. No real money.
- **Later:** a bank or payment gateway QR API with minute-level expiry and a payment webhook, behind the same
  `PaymentProvider` contract. See `CLAUDE.md` §6.

## 3. Seller App Screens and Navigation

### Authentication Screens

Welcome, Sign in, Create account (required WhatsApp + optional details), recovery/verification for the chosen
Clerk method. No bottom navigation or floating button here.

### Persistent Bottom Navigation

| Destination | Contents |
| --- | --- |
| Home | Live start/end, live board, statistics and sales history |
| Garments | Inventory, search, filters, editing, manual sale, archive |
| Profile | Seller details, reservation minutes, theme, notifications, sign out |

Highlight the active destination in pink.

### Global Floating Add Button

Floating **+** on the three main screens, above the bottom navigation. Always opens Create garment, reuses the
same form, returns to the origin, hidden inside the form, and is the only creation entry point.

### Home

- Live card: Start/End live and, while live, the live board.
- Period selector: Today, This week, This month.
- Summary cards: revenue and units sold (WhatsApp and manual).
- Sales history: code or name, quantity, amount, channel, date.

### Garments

- Search by code and name; filters In catalog, Sold, Archived.
- Cards: optional photo, code, optional name, price, stock, status, Reserved badge.
- Tap opens editing. Actions: record manual sale, archive.

### Profile

Edit WhatsApp and optional details, reservation minutes, Light/Dark mode, notifications (sales, late payments),
sign out.

## 4. Visual System and Reusable Components

Pink visual direction inspired by Gentle Shell (palette to be agreed; not official tokens). Light and dark modes,
status communicated with text and color, touch-friendly controls for use during a live, consistent cards with or
without photo. One file per reusable component; colors defined centrally.

```text
android/app/src/main/java/.../
  ui/theme/        Color.kt Theme.kt Type.kt Dimensions.kt Shape.kt
  ui/components/   AppButton.kt AppTextField.kt AppCard.kt GarmentCard.kt StatCard.kt StatusBadge.kt
                   PhotoPicker.kt AddGarmentFab.kt AppBottomBar.kt ConfirmationDialog.kt LiveBoard.kt
  ui/navigation/   AppNavigation.kt AppScaffold.kt
  ui/screens/      auth/ home/ garments/ profile/
  data/            auth/ garments/ sales/ live/ profile/ notifications/
```

## 5. Repository Layout

```text
CLAUDE.md                     business context (source of truth)
implementation-plan.md        this plan
supabase/migrations/          schema, functions, security (RLS)
supabase/tests/               SQL function tests (PGlite, `npm test`)
n8n/workflows/                exported Lia workflows (JSON)
n8n/lia-pay/                  payment page served by n8n
android/                      seller app
odd/tasks/lia-mvp.md          task log
```

## 6. Implementation Phases (demo-critical first)

| Phase | Scope | Deliverable |
| --- | --- | --- |
| 1 — Database core | Schema, SQL functions, notification outbox, RLS, tests | Queue, 5-min reservation, QR expiry and payment rules proven by tests |
| 2 — Lia agent | n8n workflows: WhatsApp inbound, code → photo → SI → queue, QR image, expiry cron, notifications | A real WhatsApp conversation reserves and sells a garment |
| 3 — Lia Pay mock | QR image + payment page with countdown; expired QR rejects payment | Demo of "cannot pay after 5 minutes" |
| 4 — App foundation | Kotlin + Compose project, theme, components, navigation, FAB | Navigable shell in both themes |
| 5 — App account + garments | Clerk, seller profile, garments CRUD, photos in Storage | Garments loaded in the app are visible to Lia |
| 6 — App live + sales | Start/end live, live board (Realtime), manual sales, statistics | Seller runs the live from the phone |
| 7 — Polish | Theme persistence, notifications in Profile, push (FCM) if time allows | — |

Phases 1–3 and 4–5 can run in parallel by different teammates: the contract between them is the database
schema in `supabase/migrations/`.

## 7. Technology

| Area | Technology |
| --- | --- |
| Seller app | Kotlin, Jetpack Compose, Material 3 |
| Authentication | Clerk (Supabase third-party auth with the Clerk JWT; verify SDK setup) |
| Data, photos, realtime | Supabase (Postgres, Storage, Realtime) |
| Agent | n8n + LLM + WhatsApp Cloud API |
| Payments | Lia Pay mock now; bank/gateway QR API later |
| Push notifications | Firebase Cloud Messaging (optional, phase 7) |

## 8. Validation Checklist

- Two buyers write the same code: the earlier WhatsApp message gets the reservation; the other is queued.
- Only one active QR per unit; the next buyer gets a QR only after expiry or cancellation.
- A QR older than 5 minutes cannot be paid (page and database both reject it).
- Paid in time → stock decreases, sale recorded, buyer, seller and queued buyers notified.
- A manual sale in the app cannot take a unit held by an active WhatsApp reservation.
- Garments created with only code and price work in the app and in Lia; stock defaults to 1.
- Historical sale amounts do not change after price edits; voiding a sale restores stock.
- Seller data and photos are isolated between accounts.
- App: session persistence, sign out, theme survives restart, FAB does not cover content.

## 9. Open Decisions

1. **Profit:** revenue only, or add an optional cost field?
2. **Clerk sign-in method.**
3. **Bank/gateway** for real QR payments (after the hackathon).

Resolved: garment codes are unique per seller; notification events are defined in `CLAUDE.md` §6.4.

## 10. Out of Scope for the Hackathon

Real bank payments, reading TikTok live comments directly, shipping, invoicing, public storefront, app store
publication.
