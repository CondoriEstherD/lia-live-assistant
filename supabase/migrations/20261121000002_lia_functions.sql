-- Lia transactional functions. "The AI talks; the database decides" (CLAUDE.md section 2).
-- Every state change goes through these functions and leaves an event.
-- Lock order everywhere: garment row first (FOR UPDATE), then reservations / QRs of that garment.
-- p_now parameters exist only for tests; callers omit them.

-- ---------------------------------------------------------------- helpers

create or replace function lia_garment_json(g garments) returns jsonb
language sql stable set search_path = public as $$
  select jsonb_build_object(
    'id', g.id, 'code', g.code, 'name', coalesce(nullif(trim(g.name), ''), g.code),
    'description', g.description, 'size', g.size, 'color', g.color,
    'price', g.price, 'photo_url', g.photo_url, 'status', g.status)
$$;

create or replace function lia_log_event(p_seller_id uuid, p_entity text, p_entity_id uuid, p_type text,
                                         p_payload jsonb default '{}'::jsonb)
returns void language sql set search_path = public as $$
  insert into events (seller_id, entity, entity_id, type, payload)
  values (p_seller_id, p_entity, p_entity_id, p_type, coalesce(p_payload, '{}'::jsonb))
$$;

create or replace function lia_notify(p_seller_id uuid, p_audience notification_audience, p_customer_id uuid,
                                      p_kind text, p_payload jsonb)
returns void language sql set search_path = public as $$
  insert into notifications (seller_id, audience, customer_id, kind, payload)
  values (p_seller_id, p_audience, p_customer_id, p_kind, coalesce(p_payload, '{}'::jsonb))
$$;

create or replace function lia_seller_by_wa(p_wa_phone_number_id text) returns sellers
language sql stable set search_path = public as $$
  select * from sellers where wa_phone_number_id = p_wa_phone_number_id
$$;

create or replace function lia_upsert_customer(p_seller_id uuid, p_phone text, p_name text default null)
returns customers language plpgsql set search_path = public as $$
declare
  v customers;
begin
  insert into customers (seller_id, phone, name)
  values (p_seller_id, p_phone, nullif(trim(p_name), ''))
  on conflict (seller_id, phone)
  do update set name = coalesce(nullif(trim(excluded.name), ''), customers.name)
  returning * into v;
  return v;
end $$;

-- Returns false when the WhatsApp message was already processed (webhook retry).
create or replace function lia_register_message(p_seller_id uuid, p_customer_id uuid, p_wa_message_id text,
                                                p_body text, p_message_ts timestamptz)
returns boolean language plpgsql set search_path = public as $$
begin
  if p_wa_message_id is null then
    return true;
  end if;
  insert into messages (seller_id, customer_id, direction, body, wa_message_id, wa_timestamp)
  values (p_seller_id, p_customer_id, 'inbound', p_body, p_wa_message_id, p_message_ts)
  on conflict (wa_message_id) do nothing;
  return found;
end $$;

-- Queue position (1-based) of a QUEUED reservation, R1 order.
create or replace function lia_queue_position(p_reservation_id uuid) returns int
language sql stable set search_path = public as $$
  select count(*)::int
  from reservations q, reservations r
  where r.id = p_reservation_id
    and q.garment_id = r.garment_id
    and q.status = 'queued'
    and (q.message_ts, q.received_at, q.id) <= (r.message_ts, r.received_at, r.id)
$$;

create or replace function lia_close_reservation(p_res reservations, p_status reservation_status, p_reason text,
                                                 p_notify_kind text, p_now timestamptz)
returns void language plpgsql set search_path = public as $$
declare
  v_garment garments;
begin
  update reservations set status = p_status, closed_at = p_now, close_reason = p_reason where id = p_res.id;
  update payment_qrs set status = case when p_status = 'expired' then 'expired'::qr_status else 'cancelled'::qr_status end
  where reservation_id = p_res.id and status = 'active';
  perform lia_log_event(p_res.seller_id, 'reservation', p_res.id, p_status::text, jsonb_build_object('reason', p_reason));
  if p_notify_kind is not null then
    select * into v_garment from garments where id = p_res.garment_id;
    perform lia_notify(p_res.seller_id, 'customer', p_res.customer_id, p_notify_kind,
      jsonb_build_object('reservation_id', p_res.id, 'garment', lia_garment_json(v_garment)));
  end if;
end $$;

-- Gives free units to the queue in R1 order (R2, R3). Caller must hold the garment lock.
create or replace function lia_promote_queue(p_garment_id uuid, p_now timestamptz default now())
returns int language plpgsql set search_path = public as $$
declare
  v_garment garments;
  v_seller sellers;
  v_free int;
  v_res reservations;
  v_token text;
  v_expires timestamptz;
  v_count int := 0;
begin
  select * into v_garment from garments where id = p_garment_id for update;
  if v_garment.id is null or v_garment.status <> 'in_catalog' then
    return 0;
  end if;
  select * into v_seller from sellers where id = v_garment.seller_id;

  select v_garment.stock - count(*) into v_free
  from reservations where garment_id = p_garment_id and status = 'active';

  for v_res in
    select * from reservations
    where garment_id = p_garment_id and status = 'queued'
    order by message_ts, received_at, id
    limit greatest(v_free, 0)
    for update
  loop
    v_expires := p_now + make_interval(mins => v_seller.reservation_minutes);
    v_token := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');

    update reservations set status = 'active', expires_at = v_expires where id = v_res.id;
    insert into payment_qrs (reservation_id, provider, token, amount, qr_expires_at)
    values (v_res.id, 'lia_pay_mock', v_token, v_res.amount_due, v_expires); -- R4: qr_expires_at = expires_at

    perform lia_log_event(v_res.seller_id, 'reservation', v_res.id, 'active',
      jsonb_build_object('expires_at', v_expires));
    perform lia_notify(v_res.seller_id, 'customer', v_res.customer_id, 'reservation_qr',
      jsonb_build_object('reservation_id', v_res.id, 'qr_token', v_token, 'amount', v_res.amount_due,
                         'expires_at', v_expires, 'garment', lia_garment_json(v_garment)));
    v_count := v_count + 1;
  end loop;
  return v_count;
end $$;

-- Garment ran out of stock: close everyone still waiting for it.
create or replace function lia_close_waiting(p_garment_id uuid, p_now timestamptz)
returns void language plpgsql set search_path = public as $$
declare
  v_res reservations;
begin
  for v_res in
    select * from reservations
    where garment_id = p_garment_id and status in ('pending_confirmation', 'queued')
    order by message_ts, received_at, id
    for update
  loop
    perform lia_close_reservation(v_res, 'cancelled', 'sold_out', 'sold_out', p_now);
  end loop;
end $$;

-- ---------------------------------------------------------------- buyer messages (n8n)

create or replace function lia_request_garment(
  p_wa_phone_number_id text, p_customer_phone text, p_customer_name text, p_code text,
  p_wa_message_id text, p_message_ts timestamptz, p_now timestamptz default now()
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_seller sellers;
  v_customer customers;
  v_live live_sessions;
  v_garment garments;
  v_res reservations;
  v_old reservations;
begin
  v_seller := lia_seller_by_wa(p_wa_phone_number_id);
  if v_seller.id is null then
    return jsonb_build_object('status', 'unknown_seller');
  end if;
  v_customer := lia_upsert_customer(v_seller.id, p_customer_phone, p_customer_name);
  if not lia_register_message(v_seller.id, v_customer.id, p_wa_message_id, p_code, p_message_ts) then
    return jsonb_build_object('status', 'duplicate');
  end if;

  select * into v_live from live_sessions where seller_id = v_seller.id and status = 'active';
  if v_live.id is null then
    return jsonb_build_object('status', 'no_live');
  end if;

  -- R4b: the garment only comes from the seller's catalog.
  select * into v_garment from garments
  where seller_id = v_seller.id and lower(code) = lower(trim(p_code)) and status <> 'archived'
  for update;
  if v_garment.id is null then
    return jsonb_build_object('status', 'not_found', 'code', upper(trim(p_code)));
  end if;

  select * into v_res from reservations
  where customer_id = v_customer.id and garment_id = v_garment.id
    and status in ('pending_confirmation', 'queued', 'active')
  order by created_at desc limit 1;
  if v_res.id is not null then
    return jsonb_build_object('status', 'already_requested', 'reservation_id', v_res.id,
      'reservation_status', v_res.status, 'garment', lia_garment_json(v_garment));
  end if;

  if v_garment.status = 'sold' or v_garment.stock = 0 then
    return jsonb_build_object('status', 'sold_out', 'garment', lia_garment_json(v_garment));
  end if;

  -- Only one pending confirmation per buyer, so "SI" is never ambiguous.
  for v_old in
    select * from reservations where customer_id = v_customer.id and status = 'pending_confirmation' for update
  loop
    perform lia_close_reservation(v_old, 'cancelled', 'replaced', null, p_now);
  end loop;

  insert into reservations (seller_id, garment_id, customer_id, live_session_id, status, message_ts,
                            received_at, confirm_deadline, amount_due)
  values (v_seller.id, v_garment.id, v_customer.id, v_live.id, 'pending_confirmation', p_message_ts,
          p_now, p_now + make_interval(mins => v_seller.confirm_minutes), v_garment.price)
  returning * into v_res;
  perform lia_log_event(v_seller.id, 'reservation', v_res.id, 'pending_confirmation',
    jsonb_build_object('code', v_garment.code, 'message_ts', p_message_ts));

  return jsonb_build_object('status', 'pending_confirmation', 'reservation_id', v_res.id,
    'confirm_deadline', v_res.confirm_deadline, 'garment', lia_garment_json(v_garment));
end $$;

create or replace function lia_confirm_request(
  p_wa_phone_number_id text, p_customer_phone text, p_wa_message_id text, p_message_ts timestamptz,
  p_now timestamptz default now()
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_seller sellers;
  v_customer customers;
  v_res reservations;
  v_garment garments;
  v_open int;
  v_position int;
begin
  v_seller := lia_seller_by_wa(p_wa_phone_number_id);
  if v_seller.id is null then
    return jsonb_build_object('status', 'unknown_seller');
  end if;
  v_customer := lia_upsert_customer(v_seller.id, p_customer_phone);
  if not lia_register_message(v_seller.id, v_customer.id, p_wa_message_id, 'SI', p_message_ts) then
    return jsonb_build_object('status', 'duplicate');
  end if;

  select * into v_res from reservations
  where customer_id = v_customer.id and status = 'pending_confirmation'
  order by created_at desc limit 1;
  if v_res.id is null then
    return jsonb_build_object('status', 'nothing_to_confirm');
  end if;

  select * into v_garment from garments where id = v_res.garment_id for update;
  select * into v_res from reservations where id = v_res.id for update;
  if v_res.status <> 'pending_confirmation' then
    return jsonb_build_object('status', 'nothing_to_confirm');
  end if;

  if p_now > v_res.confirm_deadline then
    perform lia_close_reservation(v_res, 'cancelled', 'confirmation_expired', null, p_now);
    return jsonb_build_object('status', 'confirmation_expired', 'garment', lia_garment_json(v_garment));
  end if;

  if v_garment.status <> 'in_catalog' or v_garment.stock = 0 then
    perform lia_close_reservation(v_res, 'cancelled', 'sold_out', null, p_now);
    return jsonb_build_object('status', 'sold_out', 'garment', lia_garment_json(v_garment));
  end if;

  -- R6: limit open reservations per buyer.
  select count(*) into v_open from reservations
  where customer_id = v_customer.id and status in ('queued', 'active');
  if v_open >= v_seller.max_active_per_customer then
    perform lia_close_reservation(v_res, 'cancelled', 'limit_reached', null, p_now);
    return jsonb_build_object('status', 'limit_reached', 'limit', v_seller.max_active_per_customer);
  end if;

  update reservations set status = 'queued' where id = v_res.id;
  perform lia_log_event(v_seller.id, 'reservation', v_res.id, 'queued');
  perform lia_promote_queue(v_garment.id, p_now);

  select * into v_res from reservations where id = v_res.id;
  if v_res.status = 'active' then
    return jsonb_build_object('status', 'active', 'reservation_id', v_res.id,
      'expires_at', v_res.expires_at, 'garment', lia_garment_json(v_garment));
  end if;

  v_position := lia_queue_position(v_res.id);
  perform lia_notify(v_seller.id, 'customer', v_customer.id, 'queued',
    jsonb_build_object('reservation_id', v_res.id, 'position', v_position, 'garment', lia_garment_json(v_garment)));
  return jsonb_build_object('status', 'queued', 'reservation_id', v_res.id,
    'position', v_position, 'garment', lia_garment_json(v_garment));
end $$;

create or replace function lia_cancel_request(
  p_wa_phone_number_id text, p_customer_phone text, p_wa_message_id text, p_message_ts timestamptz,
  p_now timestamptz default now()
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_seller sellers;
  v_customer customers;
  v_res reservations;
begin
  v_seller := lia_seller_by_wa(p_wa_phone_number_id);
  if v_seller.id is null then
    return jsonb_build_object('status', 'nothing_to_cancel');
  end if;
  v_customer := lia_upsert_customer(v_seller.id, p_customer_phone);
  if not lia_register_message(v_seller.id, v_customer.id, p_wa_message_id, 'NO', p_message_ts) then
    return jsonb_build_object('status', 'duplicate');
  end if;

  select * into v_res from reservations
  where customer_id = v_customer.id and status = 'pending_confirmation'
  order by created_at desc limit 1
  for update;
  if v_res.id is null then
    return jsonb_build_object('status', 'nothing_to_cancel');
  end if;
  perform lia_close_reservation(v_res, 'cancelled', 'buyer_rejected', null, p_now);
  return jsonb_build_object('status', 'cancelled', 'reservation_id', v_res.id);
end $$;

-- ---------------------------------------------------------------- agent tools (read only)

create or replace function lia_search_catalog(p_wa_phone_number_id text, p_query text default '', p_limit int default 5)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  v_seller sellers;
  v_q text := '%' || coalesce(trim(p_query), '') || '%';
begin
  v_seller := lia_seller_by_wa(p_wa_phone_number_id);
  if v_seller.id is null then
    return jsonb_build_object('items', '[]'::jsonb);
  end if;
  return jsonb_build_object('items', coalesce((
    select jsonb_agg(lia_garment_json(g) order by g.code)
    from (
      select * from garments g
      where g.seller_id = v_seller.id and g.status = 'in_catalog' and g.stock > 0
        and (g.code ilike v_q or g.name ilike v_q or g.color ilike v_q or g.size ilike v_q or g.description ilike v_q)
      order by g.code
      limit least(greatest(coalesce(p_limit, 5), 1), 20)
    ) g
  ), '[]'::jsonb));
end $$;

create or replace function lia_customer_status(p_wa_phone_number_id text, p_customer_phone text)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  v_seller sellers;
begin
  v_seller := lia_seller_by_wa(p_wa_phone_number_id);
  if v_seller.id is null then
    return jsonb_build_object('items', '[]'::jsonb);
  end if;
  return jsonb_build_object('items', coalesce((
    select jsonb_agg(jsonb_build_object(
      'code', g.code, 'name', coalesce(nullif(trim(g.name), ''), g.code), 'status', r.status,
      'expires_at', r.expires_at,
      'position', case when r.status = 'queued' then lia_queue_position(r.id) end) order by r.created_at)
    from reservations r
    join customers c on c.id = r.customer_id
    join garments g on g.id = r.garment_id
    where c.seller_id = v_seller.id and c.phone = p_customer_phone
      and r.status in ('pending_confirmation', 'queued', 'active')
  ), '[]'::jsonb));
end $$;

-- ---------------------------------------------------------------- time (cron) and outbox

create or replace function lia_expire_reservations(p_now timestamptz default now())
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_res reservations;
  v_garment_id uuid;
  v_expired int := 0;
  v_cancelled int := 0;
  v_reminders int := 0;
  v_promoted int := 0;
begin
  -- R4: 5 minutes passed without payment -> QR invalid, reservation EXPIRED, next buyer gets a QR.
  for v_garment_id in
    select distinct garment_id from reservations
    where status = 'active' and expires_at <= p_now order by garment_id
  loop
    perform 1 from garments where id = v_garment_id for update;
    for v_res in
      select * from reservations
      where garment_id = v_garment_id and status = 'active' and expires_at <= p_now
      for update
    loop
      perform lia_close_reservation(v_res, 'expired', 'payment_timeout', 'qr_expired', p_now);
      v_expired := v_expired + 1;
    end loop;
    v_promoted := v_promoted + lia_promote_queue(v_garment_id, p_now);
  end loop;

  for v_res in
    select * from reservations
    where status = 'pending_confirmation' and confirm_deadline <= p_now
    for update skip locked
  loop
    perform lia_close_reservation(v_res, 'cancelled', 'confirmation_expired', 'confirmation_expired', p_now);
    v_cancelled := v_cancelled + 1;
  end loop;

  for v_res in
    select * from reservations
    where status = 'active' and reminder_sent_at is null
      and expires_at > p_now and expires_at <= p_now + interval '1 minute'
    for update skip locked
  loop
    update reservations set reminder_sent_at = p_now where id = v_res.id;
    perform lia_notify(v_res.seller_id, 'customer', v_res.customer_id, 'payment_reminder',
      jsonb_build_object('reservation_id', v_res.id, 'expires_at', v_res.expires_at,
        'garment', (select lia_garment_json(g) from garments g where g.id = v_res.garment_id)));
    v_reminders := v_reminders + 1;
  end loop;

  return jsonb_build_object('expired', v_expired, 'cancelled', v_cancelled,
                            'reminders', v_reminders, 'promoted', v_promoted);
end $$;

-- Claims customer notifications for sending. Claimed-but-unsent rows come back after 2 minutes.
create or replace function lia_claim_notifications(p_limit int default 20, p_now timestamptz default now())
returns table (id uuid, kind text, payload jsonb, customer_phone text, wa_phone_number_id text)
language plpgsql security definer set search_path = public as $$
begin
  return query
  with picked as (
    select n.id from notifications n
    where n.audience = 'customer' and n.sent_at is null
      and (n.claimed_at is null or n.claimed_at < p_now - interval '2 minutes')
    order by n.created_at
    limit least(greatest(coalesce(p_limit, 20), 1), 100)
    for update skip locked
  )
  update notifications n set claimed_at = p_now
  from picked, customers c, sellers s
  where n.id = picked.id and c.id = n.customer_id and s.id = n.seller_id
  returning n.id, n.kind, n.payload, c.phone, s.wa_phone_number_id;
end $$;

create or replace function lia_mark_notification_sent(p_id uuid, p_now timestamptz default now())
returns jsonb language plpgsql security definer set search_path = public as $$
begin
  update notifications set sent_at = p_now where id = p_id and sent_at is null;
  return jsonb_build_object('status', case when found then 'sent' else 'not_found' end);
end $$;

-- ---------------------------------------------------------------- Lia Pay (simulated) and bank webhook

create or replace function lia_get_payment_qr(p_token text, p_now timestamptz default now())
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  v_qr payment_qrs;
  v_garment garments;
  v_status text;
begin
  select * into v_qr from payment_qrs where token = p_token;
  if v_qr.id is null then
    return jsonb_build_object('status', 'not_found');
  end if;
  select g.* into v_garment from garments g join reservations r on r.garment_id = g.id
  where r.id = v_qr.reservation_id;

  v_status := v_qr.status::text;
  if v_status = 'active' and p_now > v_qr.qr_expires_at then
    v_status := 'expired';  -- expired even if the cron has not run yet
  end if;

  return jsonb_build_object('status', v_status, 'amount', v_qr.amount, 'expires_at', v_qr.qr_expires_at,
    'seconds_left', greatest(0, floor(extract(epoch from (v_qr.qr_expires_at - p_now))))::int,
    'garment', lia_garment_json(v_garment));
end $$;

create or replace function lia_confirm_payment(
  p_token text,
  p_amount numeric,
  p_paid_at timestamptz,
  p_provider text default 'lia_pay_mock',
  p_external_id text default null,
  p_raw_payload jsonb default '{}'::jsonb,
  p_now timestamptz default now()
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_qr payment_qrs;
  v_res reservations;
  v_garment garments;
  v_customer customers;
  v_payment_id uuid;
  v_paid_at timestamptz := coalesce(p_paid_at, p_now);
begin
  select * into v_qr from payment_qrs where token = p_token;
  if v_qr.id is null then
    return jsonb_build_object('status', 'not_found');
  end if;
  select * into v_res from reservations where id = v_qr.reservation_id;

  select * into v_garment from garments where id = v_res.garment_id for update;
  select * into v_qr from payment_qrs where id = v_qr.id for update;
  select * into v_res from reservations where id = v_qr.reservation_id for update;

  if v_qr.status = 'paid' then
    return jsonb_build_object('status', 'already_paid');
  end if;

  if p_amount is not null and p_amount <> v_qr.amount then
    perform lia_log_event(v_res.seller_id, 'payment_qr', v_qr.id, 'amount_mismatch',
      jsonb_build_object('expected', v_qr.amount, 'received', p_amount));
    return jsonb_build_object('status', 'amount_mismatch', 'expected', v_qr.amount);
  end if;

  if v_qr.status <> 'active' or v_res.status <> 'active' or v_paid_at > v_qr.qr_expires_at then
    if p_provider = 'lia_pay_mock' then
      -- R4: an expired QR cannot be paid; no money moves.
      return jsonb_build_object('status', 'expired');
    end if;
    -- R5: a real provider let a late payment through. Record it for the seller.
    insert into payments (seller_id, reservation_id, payment_qr_id, provider, external_id,
                          amount, paid_at, raw_payload, status)
    values (v_res.seller_id, v_res.id, v_qr.id, p_provider, p_external_id,
            coalesce(p_amount, v_qr.amount), v_paid_at, coalesce(p_raw_payload, '{}'::jsonb), 'late')
    returning id into v_payment_id;
    perform lia_notify(v_res.seller_id, 'seller', v_res.customer_id, 'late_payment',
      jsonb_build_object('payment_id', v_payment_id, 'amount', coalesce(p_amount, v_qr.amount),
                         'garment', lia_garment_json(v_garment)));
    perform lia_log_event(v_res.seller_id, 'payment', v_payment_id, 'late');
    return jsonb_build_object('status', 'late', 'payment_id', v_payment_id);
  end if;

  insert into payments (seller_id, reservation_id, payment_qr_id, provider, external_id,
                        amount, paid_at, raw_payload, status)
  values (v_res.seller_id, v_res.id, v_qr.id, p_provider, p_external_id,
          v_qr.amount, v_paid_at, coalesce(p_raw_payload, '{}'::jsonb), 'confirmed')
  returning id into v_payment_id;

  update payment_qrs set status = 'paid' where id = v_qr.id;
  update reservations set status = 'paid', closed_at = p_now, close_reason = 'paid' where id = v_res.id;
  update garments
  set stock = stock - 1,
      status = case when stock - 1 = 0 then 'sold'::garment_status else status end,
      updated_at = p_now
  where id = v_garment.id
  returning * into v_garment;

  insert into sales (seller_id, garment_id, reservation_id, customer_id, channel, quantity, unit_price, amount, sold_at)
  values (v_res.seller_id, v_garment.id, v_res.id, v_res.customer_id, 'whatsapp', 1, v_qr.amount, v_qr.amount, v_paid_at);

  select * into v_customer from customers where id = v_res.customer_id;
  perform lia_log_event(v_res.seller_id, 'payment', v_payment_id, 'confirmed',
    jsonb_build_object('reservation_id', v_res.id, 'amount', v_qr.amount));
  perform lia_notify(v_res.seller_id, 'customer', v_res.customer_id, 'payment_confirmed',
    jsonb_build_object('reservation_id', v_res.id, 'amount', v_qr.amount, 'garment', lia_garment_json(v_garment)));
  perform lia_notify(v_res.seller_id, 'seller', v_res.customer_id, 'garment_sold',
    jsonb_build_object('reservation_id', v_res.id, 'amount', v_qr.amount, 'customer_name', v_customer.name,
                       'customer_phone', v_customer.phone, 'garment', lia_garment_json(v_garment)));

  if v_garment.stock = 0 then
    perform lia_close_waiting(v_garment.id, p_now);
  end if;

  return jsonb_build_object('status', 'paid', 'payment_id', v_payment_id, 'reservation_id', v_res.id);
end $$;

-- ---------------------------------------------------------------- seller app (Clerk JWT)

-- Seller of the signed-in Clerk user (Supabase third-party auth: JWT "sub" = Clerk user id).
create or replace function lia_current_seller_id() returns uuid
language sql stable security definer set search_path = public as $$
  select id from sellers where clerk_user_id = (auth.jwt() ->> 'sub')
$$;

create or replace function lia_require_seller() returns uuid
language plpgsql stable security definer set search_path = public as $$
declare
  v_id uuid := lia_current_seller_id();
begin
  if v_id is null then
    raise exception 'seller_not_found' using errcode = '42501';
  end if;
  return v_id;
end $$;

create or replace function start_live(p_now timestamptz default now())
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_seller_id uuid := lia_require_seller();
  v_live live_sessions;
begin
  select * into v_live from live_sessions where seller_id = v_seller_id and status = 'active';
  if v_live.id is null then
    insert into live_sessions (seller_id, started_at) values (v_seller_id, p_now) returning * into v_live;
    perform lia_log_event(v_seller_id, 'live_session', v_live.id, 'started');
  end if;
  return jsonb_build_object('status', 'active', 'live_session_id', v_live.id, 'started_at', v_live.started_at);
end $$;

-- Ends the live. Waiting buyers are released; buyers already holding a QR keep their 5 minutes.
create or replace function end_live(p_now timestamptz default now())
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_seller_id uuid := lia_require_seller();
  v_live live_sessions;
  v_res reservations;
  v_released int := 0;
begin
  select * into v_live from live_sessions where seller_id = v_seller_id and status = 'active' for update;
  if v_live.id is null then
    return jsonb_build_object('status', 'no_live');
  end if;
  update live_sessions set status = 'ended', ended_at = p_now where id = v_live.id;

  for v_res in
    select * from reservations
    where live_session_id = v_live.id and status in ('pending_confirmation', 'queued')
    order by garment_id
    for update
  loop
    perform lia_close_reservation(v_res, 'cancelled', 'live_ended', 'live_ended', p_now);
    v_released := v_released + 1;
  end loop;
  perform lia_log_event(v_seller_id, 'live_session', v_live.id, 'ended', jsonb_build_object('released', v_released));
  return jsonb_build_object('status', 'ended', 'live_session_id', v_live.id, 'released', v_released);
end $$;

-- Manual sale from the app. Cannot take units held by an active WhatsApp reservation (R2).
create or replace function record_manual_sale(p_garment_id uuid, p_quantity int default 1, p_now timestamptz default now())
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_seller_id uuid := lia_require_seller();
  v_garment garments;
  v_available int;
  v_sale_id uuid;
begin
  if coalesce(p_quantity, 0) < 1 then
    return jsonb_build_object('status', 'invalid_quantity');
  end if;
  select * into v_garment from garments where id = p_garment_id and seller_id = v_seller_id for update;
  if v_garment.id is null or v_garment.status = 'archived' then
    return jsonb_build_object('status', 'not_found');
  end if;
  select v_garment.stock - count(*) into v_available
  from reservations where garment_id = v_garment.id and status = 'active';
  if p_quantity > v_available then
    return jsonb_build_object('status', 'not_enough_available', 'available', greatest(v_available, 0));
  end if;

  update garments
  set stock = stock - p_quantity,
      status = case when stock - p_quantity = 0 then 'sold'::garment_status else status end,
      updated_at = p_now
  where id = v_garment.id
  returning * into v_garment;
  insert into sales (seller_id, garment_id, channel, quantity, unit_price, amount, sold_at)
  values (v_seller_id, v_garment.id, 'manual', p_quantity, v_garment.price, v_garment.price * p_quantity, p_now)
  returning id into v_sale_id;
  perform lia_log_event(v_seller_id, 'sale', v_sale_id, 'manual_sale', jsonb_build_object('quantity', p_quantity));

  if v_garment.stock = 0 then
    perform lia_close_waiting(v_garment.id, p_now);
  end if;
  return jsonb_build_object('status', 'sold', 'sale_id', v_sale_id, 'stock', v_garment.stock);
end $$;

create or replace function void_sale(p_sale_id uuid, p_now timestamptz default now())
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_seller_id uuid := lia_require_seller();
  v_sale sales;
  v_garment garments;
begin
  select * into v_sale from sales where id = p_sale_id and seller_id = v_seller_id;
  if v_sale.id is null then
    return jsonb_build_object('status', 'not_found');
  end if;
  select * into v_garment from garments where id = v_sale.garment_id for update;
  select * into v_sale from sales where id = p_sale_id for update;
  if v_sale.voided_at is not null then
    return jsonb_build_object('status', 'already_voided');
  end if;

  update sales set voided_at = p_now where id = v_sale.id;
  update garments
  set stock = stock + v_sale.quantity,
      status = case when status = 'sold' then 'in_catalog'::garment_status else status end,
      updated_at = p_now
  where id = v_garment.id
  returning * into v_garment;
  perform lia_log_event(v_seller_id, 'sale', v_sale.id, 'voided');
  perform lia_promote_queue(v_garment.id, p_now);
  return jsonb_build_object('status', 'voided', 'stock', v_garment.stock);
end $$;
