-- Security: Row Level Security for the seller app (Clerk JWT) and function privileges.
-- n8n uses the service_role key (bypasses RLS). The app uses the "authenticated" role.
-- The anon role gets nothing.

alter table sellers enable row level security;
alter table garments enable row level security;
alter table live_sessions enable row level security;
alter table customers enable row level security;
alter table reservations enable row level security;
alter table payment_qrs enable row level security;
alter table payments enable row level security;
alter table sales enable row level security;
alter table messages enable row level security;
alter table notifications enable row level security;
alter table events enable row level security;

-- Sellers: each Clerk user sees and edits only their own profile.
create policy sellers_select_own on sellers for select to authenticated
  using (clerk_user_id = (auth.jwt() ->> 'sub'));
create policy sellers_insert_own on sellers for insert to authenticated
  with check (clerk_user_id = (auth.jwt() ->> 'sub'));
create policy sellers_update_own on sellers for update to authenticated
  using (clerk_user_id = (auth.jwt() ->> 'sub'))
  with check (clerk_user_id = (auth.jwt() ->> 'sub'));

-- Garments: the seller manages their own catalog (Lia reads it through the functions).
create policy garments_select_own on garments for select to authenticated
  using (seller_id = lia_current_seller_id());
create policy garments_insert_own on garments for insert to authenticated
  with check (seller_id = lia_current_seller_id());
create policy garments_update_own on garments for update to authenticated
  using (seller_id = lia_current_seller_id())
  with check (seller_id = lia_current_seller_id());

-- Read-only for the app: state changes go through the SQL functions.
create policy live_sessions_select_own on live_sessions for select to authenticated
  using (seller_id = lia_current_seller_id());
create policy customers_select_own on customers for select to authenticated
  using (seller_id = lia_current_seller_id());
create policy reservations_select_own on reservations for select to authenticated
  using (seller_id = lia_current_seller_id());
create policy payments_select_own on payments for select to authenticated
  using (seller_id = lia_current_seller_id());
create policy sales_select_own on sales for select to authenticated
  using (seller_id = lia_current_seller_id());
create policy events_select_own on events for select to authenticated
  using (seller_id = lia_current_seller_id());
create policy notifications_select_own on notifications for select to authenticated
  using (seller_id = lia_current_seller_id() and audience = 'seller');
-- payment_qrs and messages: no app policy (service role only).

create policy notifications_mark_read_own on notifications for update to authenticated
  using (seller_id = lia_current_seller_id() and audience = 'seller')
  with check (seller_id = lia_current_seller_id() and audience = 'seller');

-- Table privileges, scoped to Lia tables only (other objects in "public" are left untouched).
-- Supabase grants ALL to anon/authenticated by default; start from nothing and grant per column.
revoke all on sellers, garments, live_sessions, customers, reservations, payment_qrs, payments, sales, messages,
  notifications, events from anon, authenticated;
grant select on sellers, garments, live_sessions, customers, reservations, payments, sales, notifications, events
  to authenticated;

-- Sellers: wa_phone_number_id and the limits are set by the team (service role), never by the app.
grant insert (clerk_user_id, whatsapp_number, name, username, social_links, reservation_minutes) on sellers
  to authenticated;
grant update (whatsapp_number, name, username, social_links, reservation_minutes) on sellers to authenticated;

-- Garments: details are editable; stock and status change only through set_garment_stock,
-- record_manual_sale, void_sale and archive_garment (they lock the row and respect reservations).
grant insert (seller_id, code, name, description, size, color, price, stock, photo_url) on garments to authenticated;
grant update (code, name, description, size, color, price, photo_url) on garments to authenticated;

-- Seller notifications: the app may only mark them as read.
grant update (read_at) on notifications to authenticated;

-- Functions: lock down every Lia function first (Supabase grants EXECUTE to anon/authenticated by default).
do $$
declare
  f regprocedure;
begin
  for f in
    select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = $q$public$q$
      and (starts_with(p.proname, $q$lia_$q$) or p.proname in ($q$start_live$q$, $q$end_live$q$, $q$record_manual_sale$q$,
                                                    $q$void_sale$q$, $q$set_garment_stock$q$, $q$archive_garment$q$))
  loop
    execute format($q$revoke execute on function %s from public, anon, authenticated$q$, f);
  end loop;
end $$;

-- n8n (service role) only: buyer flow, cron, outbox, payments.
grant execute on function
  lia_request_garment(text, text, text, text, text, timestamptz, timestamptz),
  lia_confirm_request(text, text, text, timestamptz, timestamptz),
  lia_cancel_request(text, text, text, timestamptz, timestamptz),
  lia_search_catalog(text, text, int),
  lia_customer_status(text, text),
  lia_expire_reservations(timestamptz),
  lia_claim_notifications(int, timestamptz),
  lia_mark_notification_sent(uuid, timestamptz),
  lia_get_payment_qr(text, timestamptz),
  lia_confirm_payment(text, numeric, timestamptz, text, text, jsonb, timestamptz)
  to service_role;

-- Seller app (Clerk JWT).
grant execute on function
  lia_current_seller_id(),
  start_live(timestamptz),
  end_live(timestamptz),
  record_manual_sale(uuid, int, timestamptz),
  void_sale(uuid, timestamptz),
  set_garment_stock(uuid, int, timestamptz),
  archive_garment(uuid, timestamptz)
  to authenticated, service_role;

-- Live board in the app (Supabase Realtime), only where the publication exists.
do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    alter publication supabase_realtime add table garments, reservations, sales, notifications, live_sessions;
  end if;
end $$;
