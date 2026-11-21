-- Lia core schema: catalog, live sessions, queue/reservations, QR payments, sales, outbox, audit.
-- Business rules: CLAUDE.md (sections 5-8). n8n contract: n8n/CONTRACT.md.

create type garment_status as enum ('in_catalog', 'sold', 'archived');
create type live_status as enum ('active', 'ended');
create type reservation_status as enum ('pending_confirmation', 'queued', 'active', 'paid', 'expired', 'cancelled');
create type qr_status as enum ('active', 'paid', 'expired', 'cancelled');
create type payment_status as enum ('received', 'confirmed', 'late', 'refunded', 'credited', 'reassigned');
create type sale_channel as enum ('whatsapp', 'manual');
create type notification_audience as enum ('customer', 'seller');

create table sellers (
  id uuid primary key default gen_random_uuid(),
  clerk_user_id text unique,
  whatsapp_number text not null,
  wa_phone_number_id text unique,               -- WhatsApp Cloud API phone_number_id that receives buyers
  name text,
  username text,
  social_links jsonb not null default '{}'::jsonb,
  reservation_minutes int not null default 5 check (reservation_minutes between 1 and 60),
  confirm_minutes int not null default 2 check (confirm_minutes between 1 and 30),
  max_active_per_customer int not null default 2 check (max_active_per_customer between 1 and 20),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table garments (
  id uuid primary key default gen_random_uuid(),
  seller_id uuid not null references sellers (id) on delete cascade,
  code text not null check (length(trim(code)) > 0),
  name text,
  description text,
  size text,
  color text,
  price numeric(10, 2) not null check (price > 0),
  stock int not null default 1 check (stock >= 0),
  photo_url text,
  status garment_status not null default 'in_catalog',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
-- Code unique per seller among non-archived garments, case-insensitive.
create unique index garments_seller_code_uq on garments (seller_id, lower(code)) where status <> 'archived';

create table live_sessions (
  id uuid primary key default gen_random_uuid(),
  seller_id uuid not null references sellers (id) on delete cascade,
  status live_status not null default 'active',
  started_at timestamptz not null default now(),
  ended_at timestamptz
);
create unique index live_sessions_one_active_uq on live_sessions (seller_id) where status = 'active';

create table customers (
  id uuid primary key default gen_random_uuid(),
  seller_id uuid not null references sellers (id) on delete cascade,
  phone text not null,
  name text,
  created_at timestamptz not null default now(),
  unique (seller_id, phone)
);

create table reservations (
  id uuid primary key default gen_random_uuid(),
  seller_id uuid not null references sellers (id) on delete cascade,
  garment_id uuid not null references garments (id),
  customer_id uuid not null references customers (id),
  live_session_id uuid references live_sessions (id),
  status reservation_status not null default 'pending_confirmation',
  message_ts timestamptz not null,               -- WhatsApp timestamp of the code message (R1 order)
  received_at timestamptz not null default now(), -- server time, R1 tie-breaker
  confirm_deadline timestamptz,
  amount_due numeric(10, 2) not null,
  expires_at timestamptz,                          -- set when ACTIVE (5-minute window)
  reminder_sent_at timestamptz,
  closed_at timestamptz,
  close_reason text,
  created_at timestamptz not null default now()
);
create index reservations_queue_idx on reservations (garment_id, status, message_ts, received_at, id);
create index reservations_customer_idx on reservations (customer_id, status);
create index reservations_expiry_idx on reservations (status, expires_at);

create table payment_qrs (
  id uuid primary key default gen_random_uuid(),
  reservation_id uuid not null unique references reservations (id) on delete cascade,
  provider text not null default 'lia_pay_mock',
  token text not null unique,
  external_id text,
  qr_image_url text,
  amount numeric(10, 2) not null,
  qr_expires_at timestamptz not null,
  status qr_status not null default 'active',
  created_at timestamptz not null default now()
);

create table payments (
  id uuid primary key default gen_random_uuid(),
  seller_id uuid not null references sellers (id) on delete cascade,
  reservation_id uuid not null references reservations (id),
  payment_qr_id uuid references payment_qrs (id),
  provider text not null,
  external_id text,
  amount numeric(10, 2) not null,
  paid_at timestamptz not null,
  raw_payload jsonb not null default '{}'::jsonb,
  status payment_status not null default 'received',
  created_at timestamptz not null default now()
);
-- At most one confirmed payment per QR (second line of defense against double payment).
create unique index payments_one_confirmed_per_qr_uq on payments (payment_qr_id) where status = 'confirmed';

create table sales (
  id uuid primary key default gen_random_uuid(),
  seller_id uuid not null references sellers (id) on delete cascade,
  garment_id uuid not null references garments (id),
  reservation_id uuid unique references reservations (id),
  customer_id uuid references customers (id),
  channel sale_channel not null,
  quantity int not null default 1 check (quantity > 0),
  unit_price numeric(10, 2) not null,             -- frozen at sale time
  amount numeric(10, 2) not null,
  sold_at timestamptz not null default now(),
  voided_at timestamptz
);

create table messages (
  id uuid primary key default gen_random_uuid(),
  seller_id uuid not null references sellers (id) on delete cascade,
  customer_id uuid references customers (id),
  direction text not null default 'inbound' check (direction in ('inbound', 'outbound')),
  body text,
  wa_message_id text unique,                       -- dedup: WhatsApp retries webhooks
  wa_timestamp timestamptz,
  created_at timestamptz not null default now()
);

create table notifications (
  id uuid primary key default gen_random_uuid(),
  seller_id uuid not null references sellers (id) on delete cascade,
  audience notification_audience not null,
  customer_id uuid references customers (id),
  kind text not null,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  claimed_at timestamptz,
  sent_at timestamptz,
  read_at timestamptz
);
create index notifications_outbox_idx on notifications (audience, sent_at, created_at);

create table events (
  id bigint generated always as identity primary key,
  seller_id uuid references sellers (id) on delete cascade,
  entity text not null,
  entity_id uuid,
  type text not null,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index events_entity_idx on events (entity, entity_id);
