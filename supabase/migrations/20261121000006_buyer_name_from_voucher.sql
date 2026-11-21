-- Business rule: the buyer's name comes ONLY from the payment voucher (comprobante) she sends after
-- paying, read by the AI in n8n. The WhatsApp profile name is never used as the buyer name.
-- The phone number (unique per seller) and the payment date are recorded when the payment is confirmed.

alter table sales
  add column name_source text check (name_source in ('voucher')),
  add column voucher_reference text,
  add column voucher_url text,
  add column voucher_data jsonb,
  add column voucher_received_at timestamptz;

-- Names copied from WhatsApp profiles by migration 0005 are not valid buyer names.
update sales set customer_name = null where name_source is null;

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

  -- The phone (unique buyer id) is copied now; the buyer name comes only from the payment voucher (lia_register_voucher).
  insert into sales (seller_id, garment_id, reservation_id, customer_id, customer_name, customer_phone,
                     channel, quantity, unit_price, amount, sold_at)
  values (v_res.seller_id, v_garment.id, v_res.id, v_res.customer_id, null, v_customer.phone,
          'whatsapp', 1, v_qr.amount, v_qr.amount, v_paid_at);
  perform lia_log_event(v_res.seller_id, 'payment', v_payment_id, 'confirmed',
    jsonb_build_object('reservation_id', v_res.id, 'amount', v_qr.amount));
  perform lia_notify(v_res.seller_id, 'customer', v_res.customer_id, 'payment_confirmed',
    jsonb_build_object('reservation_id', v_res.id, 'amount', v_qr.amount,
                       'customer_phone', v_customer.phone, 'paid_at', v_paid_at, 'garment', lia_garment_json(v_garment)));
  perform lia_notify(v_res.seller_id, 'seller', v_res.customer_id, 'garment_sold',
    jsonb_build_object('reservation_id', v_res.id, 'amount', v_qr.amount,
                       'customer_phone', v_customer.phone, 'paid_at', v_paid_at, 'garment', lia_garment_json(v_garment)));

  if v_garment.stock = 0 then
    perform lia_close_waiting(v_garment.id, p_now);
  end if;

  return jsonb_build_object('status', 'paid', 'payment_id', v_payment_id, 'reservation_id', v_res.id);
end $$;

create or replace function lia_claim_notifications(p_limit int default 20, p_now timestamptz default now())
returns table (id uuid, kind text, payload jsonb, customer_phone text, wa_phone_number_id text,
               audience text, to_phone text)
language plpgsql security definer set search_path = public as $$
begin
  return query
  with picked as (
    select n.id from notifications n
    where n.sent_at is null
      and (n.audience = 'customer' or (n.audience = 'seller' and n.kind in ('garment_sold', 'late_payment', 'buyer_identified', 'voucher_review')))
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

create or replace function lia_register_voucher(
  p_wa_phone_number_id text, p_customer_phone text, p_payer_name text, p_amount numeric,
  p_paid_at timestamptz, p_reference text, p_voucher_url text, p_raw jsonb, p_now timestamptz default now()
) returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_seller sellers;
  v_customer customers;
  v_sale sales;
  v_garment garments;
  v_name text;
  v_payload jsonb;
begin
  v_seller := lia_seller_by_wa(p_wa_phone_number_id);
  if v_seller.id is null then
    return jsonb_build_object('status', 'unknown_seller');
  end if;

  select * into v_customer from customers where seller_id = v_seller.id and phone = p_customer_phone;
  if v_customer.id is null then
    return jsonb_build_object('status', 'no_sale');
  end if;

  -- Most recent WhatsApp sale of this buyer that still has no name from a voucher.
  select * into v_sale from sales
  where seller_id = v_seller.id and customer_id = v_customer.id and channel = 'whatsapp'
    and voided_at is null and name_source is null
  order by sold_at desc
  limit 1
  for update;

  if v_sale.id is null then
    if exists (select 1 from sales where customer_id = v_customer.id and name_source = 'voucher' and voided_at is null) then
      return jsonb_build_object('status', 'already_registered');
    end if;
    return jsonb_build_object('status', 'no_sale');
  end if;

  select * into v_garment from garments where id = v_sale.garment_id;
  v_name := nullif(btrim(regexp_replace(coalesce(p_payer_name, ''), '[[:space:]]+', ' ', 'g')), '');

  if v_name is null then
    return jsonb_build_object('status', 'name_missing');
  end if;

  if p_amount is not null and p_amount <> v_sale.amount then
    perform lia_notify(v_seller.id, 'seller', v_customer.id, 'voucher_review',
      jsonb_build_object('sale_id', v_sale.id, 'expected', v_sale.amount, 'voucher_amount', p_amount,
                         'payer_name', v_name, 'customer_phone', v_customer.phone, 'garment', lia_garment_json(v_garment)));
    perform lia_log_event(v_seller.id, 'sale', v_sale.id, 'voucher_amount_mismatch',
      jsonb_build_object('expected', v_sale.amount, 'voucher_amount', p_amount));
    return jsonb_build_object('status', 'amount_mismatch', 'expected', v_sale.amount, 'voucher_amount', p_amount);
  end if;

  update sales set
    customer_name = v_name,
    name_source = 'voucher',
    voucher_reference = nullif(btrim(p_reference), ''),
    voucher_url = p_voucher_url,
    voucher_data = coalesce(p_raw, '{}'::jsonb)
      || jsonb_build_object('payer_name', v_name, 'amount', p_amount, 'paid_at', p_paid_at),
    voucher_received_at = p_now
  where id = v_sale.id;

  v_payload := jsonb_build_object('sale_id', v_sale.id, 'amount', v_sale.amount, 'paid_at', v_sale.sold_at,
                                  'customer_name', v_name, 'customer_phone', v_customer.phone,
                                  'garment', lia_garment_json(v_garment));
  perform lia_notify(v_seller.id, 'customer', v_customer.id, 'voucher_registered', v_payload);
  perform lia_notify(v_seller.id, 'seller', v_customer.id, 'buyer_identified', v_payload);
  perform lia_log_event(v_seller.id, 'sale', v_sale.id, 'voucher_registered',
    jsonb_build_object('payer_name', v_name, 'reference', p_reference));

  return jsonb_build_object('status', 'registered', 'sale_id', v_sale.id, 'customer_name', v_name,
                            'customer_phone', v_customer.phone, 'paid_at', v_sale.sold_at,
                            'garment', lia_garment_json(v_garment));
end $$;

revoke execute on function lia_register_voucher(text, text, text, numeric, timestamptz, text, text, jsonb, timestamptz)
  from public, anon, authenticated;
grant execute on function lia_register_voucher(text, text, text, numeric, timestamptz, text, text, jsonb, timestamptz)
  to service_role;
