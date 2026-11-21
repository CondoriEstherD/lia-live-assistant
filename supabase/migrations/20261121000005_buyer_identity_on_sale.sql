-- Business rule: when a payment is confirmed, the sale keeps the buyer's name and phone number
-- (the phone is unique per seller), the buyer's confirmation shows them, and the seller is told
-- who bought through n8n (WhatsApp), not only in the app.

alter table sales add column customer_name text, add column customer_phone text;

update sales s set customer_name = c.name, customer_phone = c.phone
from customers c where c.id = s.customer_id and s.customer_phone is null;

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

  select * into v_customer from customers where id = v_res.customer_id;

  -- Buyer identity is copied into the sale: the phone is the unique buyer id, the name is what WhatsApp showed.
  insert into sales (seller_id, garment_id, reservation_id, customer_id, customer_name, customer_phone,
                     channel, quantity, unit_price, amount, sold_at)
  values (v_res.seller_id, v_garment.id, v_res.id, v_res.customer_id, v_customer.name, v_customer.phone,
          'whatsapp', 1, v_qr.amount, v_qr.amount, v_paid_at);
  perform lia_log_event(v_res.seller_id, 'payment', v_payment_id, 'confirmed',
    jsonb_build_object('reservation_id', v_res.id, 'amount', v_qr.amount));
  perform lia_notify(v_res.seller_id, 'customer', v_res.customer_id, 'payment_confirmed',
    jsonb_build_object('reservation_id', v_res.id, 'amount', v_qr.amount, 'customer_name', v_customer.name,
                       'customer_phone', v_customer.phone, 'paid_at', v_paid_at, 'garment', lia_garment_json(v_garment)));
  perform lia_notify(v_res.seller_id, 'seller', v_res.customer_id, 'garment_sold',
    jsonb_build_object('reservation_id', v_res.id, 'amount', v_qr.amount, 'customer_name', v_customer.name,
                       'customer_phone', v_customer.phone, 'paid_at', v_paid_at, 'garment', lia_garment_json(v_garment)));

  if v_garment.stock = 0 then
    perform lia_close_waiting(v_garment.id, p_now);
  end if;

  return jsonb_build_object('status', 'paid', 'payment_id', v_payment_id, 'reservation_id', v_res.id);
end $$;


-- Outbox now also delivers seller notifications about sales; to_phone is the destination number.
drop function lia_claim_notifications(int, timestamptz);
create function lia_claim_notifications(p_limit int default 20, p_now timestamptz default now())
returns table (id uuid, kind text, payload jsonb, customer_phone text, wa_phone_number_id text,
               audience text, to_phone text)
language plpgsql security definer set search_path = public as $$
begin
  return query
  with picked as (
    select n.id from notifications n
    where n.sent_at is null
      and (n.audience = 'customer' or (n.audience = 'seller' and n.kind in ('garment_sold', 'late_payment')))
      and (n.claimed_at is null or n.claimed_at < p_now - interval '2 minutes')
    order by n.created_at
    limit least(greatest(coalesce(p_limit, 20), 1), 100)
    for update skip locked
  )
  update notifications n set claimed_at = p_now
  from picked, customers c, sellers s
  where n.id = picked.id and c.id = n.customer_id and s.id = n.seller_id
  returning n.id, n.kind, n.payload, c.phone, s.wa_phone_number_id, n.audience::text,
    case when n.audience = 'seller' then regexp_replace(s.whatsapp_number, '[^0-9]', '', 'g') else c.phone end;
end $$;

revoke execute on function lia_claim_notifications(int, timestamptz) from public, anon, authenticated;
grant execute on function lia_claim_notifications(int, timestamptz) to service_role;
