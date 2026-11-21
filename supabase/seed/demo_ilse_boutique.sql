-- Demo data for the seller app account of ilseromero35@gmail.com ("Ilse Boutique Live").
-- Test data only. Safe to run more than once: if this seller already has garments, it stops.
--
-- What it creates:
--   * seller (linked to the Clerk user id below) with an active live session
--   * 8 garments with photos (Supabase Storage bucket garment-photos/seed/ilse/*.jpg)
--   * sales spread over today / this week / this month for the dashboard
--     (WhatsApp sales carry buyer phone + name from the voucher, as Lia records them)
--   * seller notifications (read and unread), already marked as delivered
--   * links the Whapi WhatsApp channel to this seller so Lia answers with this catalog
--
-- If the Clerk user id of ilseromero35@gmail.com is different, change v_clerk_user_id.

do $$
declare
  v_clerk_user_id text := 'user_3MQXdU9imTocAfTOPCJS5AVojpO';
  v_whapi_channel text := 'HULKBR-4WGWP';
  v_photos text := 'https://qxlirecxxqbjhxsbfdwu.supabase.co/storage/v1/object/public/garment-photos/seed/ilse/';
  v_seller_id uuid;
  v_g_a12 uuid; v_g_a15 uuid; v_g_b04 uuid; v_g_b09 uuid;
  v_g_c21 uuid; v_g_a08 uuid; v_g_d03 uuid; v_g_e01 uuid;
begin
  -- 1. Seller
  select id into v_seller_id from sellers where clerk_user_id = v_clerk_user_id;
  if v_seller_id is null then
    insert into sellers (clerk_user_id, whatsapp_number, name, username, social_links)
    values (v_clerk_user_id, '+59170012345', 'Ilse Boutique Live', 'ilseromero35',
            jsonb_build_object('tiktok', 'tiktok.com/@ilseboutiquelive'))
    returning id into v_seller_id;
  end if;

  if exists (select 1 from garments where seller_id = v_seller_id) then
    raise notice 'Seller % already has garments: seed skipped.', v_seller_id;
    return;
  end if;

  -- 2. Garments with photos (in catalog, sold and archived)
  insert into garments (seller_id, code, name, description, size, color, price, stock, status, photo_url)
  values (v_seller_id, 'A12', 'Vestido satinado rosado', 'Corte midi ideal para fiesta, tela satinada suave.', 'M', 'Rosado', 165.00, 3, 'in_catalog', v_photos || 'A12.jpg')
  returning id into v_g_a12;
  insert into garments (seller_id, code, name, description, size, color, price, stock, status, photo_url)
  values (v_seller_id, 'A15', 'Blusa manga globo', 'Algodón liviano con elástico en puños.', 'S', 'Blanco', 95.00, 2, 'in_catalog', v_photos || 'A15.jpg')
  returning id into v_g_a15;
  insert into garments (seller_id, code, name, description, size, color, price, stock, status, photo_url)
  values (v_seller_id, 'B04', 'Conjunto deportivo turquesa', 'Top + biker de lycra gruesa.', 'M', 'Turquesa', 140.00, 4, 'in_catalog', v_photos || 'B04.jpg')
  returning id into v_g_b04;
  insert into garments (seller_id, code, name, description, size, color, price, stock, status, photo_url)
  values (v_seller_id, 'B09', 'Jean wide leg tiro alto', 'Denim rígido tono azul clásico.', '38', 'Azul', 190.00, 1, 'in_catalog', v_photos || 'B09.jpg')
  returning id into v_g_b09;
  insert into garments (seller_id, code, name, description, size, color, price, stock, status, photo_url)
  values (v_seller_id, 'C21', null, 'Prenda sin nombre opcional para probar la vista.', 'L', 'Negro', 110.00, 2, 'in_catalog', v_photos || 'C21.jpg')
  returning id into v_g_c21;
  insert into garments (seller_id, code, name, description, size, color, price, stock, status, photo_url)
  values (v_seller_id, 'A08', 'Conjunto lino verano', 'Short y chaleco de lino fresco.', 'M', 'Beige', 180.00, 0, 'sold', v_photos || 'A08.jpg')
  returning id into v_g_a08;
  insert into garments (seller_id, code, name, description, size, color, price, stock, status, photo_url)
  values (v_seller_id, 'D03', 'Chaqueta eco cuero', 'Cierre metálico plateado.', 'S', 'Negro', 240.00, 0, 'sold', v_photos || 'D03.jpg')
  returning id into v_g_d03;
  insert into garments (seller_id, code, name, description, size, color, price, stock, status, photo_url)
  values (v_seller_id, 'E01', 'Falda plisada temporada anterior', 'Archivada para conservar historial.', 'M', 'Bordó', 120.00, 1, 'archived', v_photos || 'E01.jpg')
  returning id into v_g_e01;

  -- 3. Sales for the dashboard (today / this week / this month).
  --    WhatsApp sales: buyer phone + name taken from the voucher (name_source = 'voucher').
  insert into sales (seller_id, garment_id, channel, quantity, unit_price, amount, sold_at,
                     customer_name, customer_phone, name_source, voucher_reference) values
    (v_seller_id, v_g_a12, 'manual',   1, 165.00, 165.00, now() - interval '20 minutes', null, null, null, null),
    (v_seller_id, v_g_b04, 'whatsapp', 2, 140.00, 280.00, now() - interval '1 hour',
       'SOFIA MORALES VACA', '59171234567', 'voucher', 'QR-DEMO-0001'),
    (v_seller_id, v_g_a08, 'whatsapp', 1, 180.00, 180.00, now() - interval '3 hours',
       'VALERIA JUSTINIANO ROCA', '59176543210', 'voucher', 'QR-DEMO-0002'),
    (v_seller_id, v_g_a15, 'manual',   2,  95.00, 190.00, now() - interval '2 days', null, null, null, null),
    (v_seller_id, v_g_d03, 'whatsapp', 1, 240.00, 240.00, now() - interval '4 days',
       'CARLA ROJAS SUAREZ', '59172223344', 'voucher', 'QR-DEMO-0003'),
    (v_seller_id, v_g_c21, 'manual',   1, 110.00, 110.00, now() - interval '10 days', null, null, null, null),
    (v_seller_id, v_g_e01, 'manual',   1, 120.00, 120.00, now() - interval '15 days', null, null, null, null);

  -- 4. Seller notifications for the app (unread and read). sent_at is set so n8n never re-sends them.
  insert into notifications (seller_id, audience, kind, payload, created_at, sent_at, read_at) values
    (v_seller_id, 'seller', 'buyer_identified',
     jsonb_build_object('amount', 280.00, 'customer_name', 'SOFIA MORALES VACA', 'customer_phone', '59171234567',
                        'paid_at', now() - interval '1 hour',
                        'garment', jsonb_build_object('code', 'B04', 'name', 'Conjunto deportivo turquesa')),
     now() - interval '1 hour', now() - interval '1 hour', null),
    (v_seller_id, 'seller', 'buyer_identified',
     jsonb_build_object('amount', 180.00, 'customer_name', 'VALERIA JUSTINIANO ROCA', 'customer_phone', '59176543210',
                        'paid_at', now() - interval '3 hours',
                        'garment', jsonb_build_object('code', 'A08', 'name', 'Conjunto lino verano')),
     now() - interval '3 hours', now() - interval '3 hours', null),
    (v_seller_id, 'seller', 'late_payment',
     jsonb_build_object('amount', 240.00, 'garment', jsonb_build_object('code', 'D03', 'name', 'Chaqueta eco cuero')),
     now() - interval '1 day', now() - interval '1 day', now() - interval '20 hours'),
    (v_seller_id, 'seller', 'buyer_identified',
     jsonb_build_object('amount', 240.00, 'customer_name', 'CARLA ROJAS SUAREZ', 'customer_phone', '59172223344',
                        'paid_at', now() - interval '4 days',
                        'garment', jsonb_build_object('code', 'D03', 'name', 'Chaqueta eco cuero')),
     now() - interval '4 days', now() - interval '4 days', now() - interval '3 days');

  -- 5. Active live session so Lia accepts garment codes.
  if not exists (select 1 from live_sessions where seller_id = v_seller_id and status = 'active') then
    insert into live_sessions (seller_id) values (v_seller_id);
  end if;

  -- 6. Lia's WhatsApp channel answers with this seller's catalog.
  update sellers set wa_phone_number_id = null where wa_phone_number_id = v_whapi_channel and id <> v_seller_id;
  update sellers set wa_phone_number_id = v_whapi_channel where id = v_seller_id;
end $$;
