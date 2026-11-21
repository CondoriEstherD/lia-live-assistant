-- Migration 04 (solo sube este archivo nuevo a Supabase):
-- 1) Permite reutilizar códigos de prendas vendidas o archivadas.
-- 2) Actualiza lia_request_garment para priorizar la prenda activa (in_catalog).
-- 3) Crea el bucket garment-photos en Supabase Storage con aislamiento por vendedora.

-- 1. Código único por vendedora solo mientras status = 'in_catalog'.
drop index if exists garments_seller_code_uq;
create unique index garments_seller_code_uq
  on garments (seller_id, lower(code))
  where status = 'in_catalog';

-- 2. Priorizar la prenda in_catalog cuando exista otra anterior con el mismo código en sold.
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

  -- R4b: prefer active in_catalog garment when a previous sold garment shares the same code.
  select * into v_garment from garments
  where seller_id = v_seller.id and lower(code) = lower(trim(p_code)) and status <> 'archived'
  order by (status = 'in_catalog') desc, created_at desc
  limit 1
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

-- 3. Bucket de Storage para fotos de prendas (cada vendedora solo escribe/borra en <clerk_user_id>/).
insert into storage.buckets (id, name, public)
values ('garment-photos', 'garment-photos', true)
on conflict (id) do nothing;

drop policy if exists garment_photos_select_public on storage.objects;
create policy garment_photos_select_public
  on storage.objects for select
  using (bucket_id = 'garment-photos');

drop policy if exists garment_photos_insert_own on storage.objects;
create policy garment_photos_insert_own
  on storage.objects for insert to authenticated
  with check (
    bucket_id = 'garment-photos'
    and (storage.foldername(name))[1] = (auth.jwt() ->> 'sub')
  );

drop policy if exists garment_photos_update_own on storage.objects;
create policy garment_photos_update_own
  on storage.objects for update to authenticated
  using (
    bucket_id = 'garment-photos'
    and (storage.foldername(name))[1] = (auth.jwt() ->> 'sub')
  )
  with check (
    bucket_id = 'garment-photos'
    and (storage.foldername(name))[1] = (auth.jwt() ->> 'sub')
  );

drop policy if exists garment_photos_delete_own on storage.objects;
create policy garment_photos_delete_own
  on storage.objects for delete to authenticated
  using (
    bucket_id = 'garment-photos'
    and (storage.foldername(name))[1] = (auth.jwt() ->> 'sub')
  );
