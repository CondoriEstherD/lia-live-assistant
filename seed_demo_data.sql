-- Seed de datos de prueba para ilseromero35@gmail.com
-- Pega y ejecuta este script en Supabase -> SQL Editor.
-- Nota: usa el clerk_user_id detectado en tu sesión (user_3MQXdU9imTocAfTOPCJS5AVojpO).
-- Si en Clerk tu usuario ilseromero35@gmail.com tiene otro ID, cámbialo en v_clerk_user_id.

do $$
declare
  v_clerk_user_id text := 'user_3MQXdU9imTocAfTOPCJS5AVojpO';
  v_seller_id uuid;
  v_g_a12 uuid;
  v_g_a15 uuid;
  v_g_b04 uuid;
  v_g_b09 uuid;
  v_g_c21 uuid;
  v_g_a08 uuid;
  v_g_d03 uuid;
  v_g_e01 uuid;
begin
  -- 1. Obtener o crear el registro en sellers para ilseromero35@gmail.com
  select id into v_seller_id
  from sellers
  where clerk_user_id = v_clerk_user_id;

  if v_seller_id is null then
    insert into sellers (clerk_user_id, whatsapp_number, name, username, social_links)
    values (
      v_clerk_user_id,
      '+591 70012345',
      'Ilse Boutique Live',
      'ilseromero35',
      jsonb_build_object('links', 'tiktok.com/@ilseboutiquelive')
    )
    returning id into v_seller_id;
  end if;

  -- 2. Poblar prendas (En catálogo, Vendidas y Archivadas)
  insert into garments (seller_id, code, name, description, size, color, price, stock, status)
  values (v_seller_id, 'A12', 'Vestido satinado rosado', 'Corte midi ideal para fiesta, tela satinada suave.', 'M', 'Rosado', 165.00, 3, 'in_catalog')
  returning id into v_g_a12;

  insert into garments (seller_id, code, name, description, size, color, price, stock, status)
  values (v_seller_id, 'A15', 'Blusa manga globo', 'Algodón liviano con elástico en puños.', 'S', 'Blanco', 95.00, 2, 'in_catalog')
  returning id into v_g_a15;

  insert into garments (seller_id, code, name, description, size, color, price, stock, status)
  values (v_seller_id, 'B04', 'Conjunto deportivo turquesa', 'Top + biker de lycra gruesa.', 'M', 'Turquesa', 140.00, 4, 'in_catalog')
  returning id into v_g_b04;

  insert into garments (seller_id, code, name, description, size, color, price, stock, status)
  values (v_seller_id, 'B09', 'Jean wide leg tiro alto', 'Denim rígido tono azul clásico.', '38', 'Azul', 190.00, 1, 'in_catalog')
  returning id into v_g_b09;

  insert into garments (seller_id, code, name, description, size, color, price, stock, status)
  values (v_seller_id, 'C21', null, 'Prenda sin nombre opcional para probar la vista.', 'L', 'Negro', 110.00, 2, 'in_catalog')
  returning id into v_g_c21;

  insert into garments (seller_id, code, name, description, size, color, price, stock, status)
  values (v_seller_id, 'A08', 'Conjunto lino verano', 'Short y chaleco de lino fresco.', 'M', 'Beige', 180.00, 0, 'sold')
  returning id into v_g_a08;

  insert into garments (seller_id, code, name, description, size, color, price, stock, status)
  values (v_seller_id, 'D03', 'Chaqueta eco cuero', 'Cierre metálico plateado.', 'S', 'Negro', 240.00, 0, 'sold')
  returning id into v_g_d03;

  insert into garments (seller_id, code, name, description, size, color, price, stock, status)
  values (v_seller_id, 'E01', 'Falda plisada temporada anterior', 'Archivada para conservar historial.', 'M', 'Bordó', 120.00, 1, 'archived')
  returning id into v_g_e01;

  -- 3. Poblar ventas repartidas en Hoy, Esta semana y Este mes
  insert into sales (seller_id, garment_id, channel, quantity, unit_price, amount, sold_at) values
    (v_seller_id, v_g_a12, 'manual',   1, 165.00, 165.00, now() - interval '20 minutes'),
    (v_seller_id, v_g_b04, 'whatsapp', 2, 140.00, 280.00, now() - interval '1 hour'),
    (v_seller_id, v_g_a08, 'whatsapp', 1, 180.00, 180.00, now() - interval '3 hours'),
    (v_seller_id, v_g_a15, 'manual',   2,  95.00, 190.00, now() - interval '2 days'),
    (v_seller_id, v_g_d03, 'whatsapp', 1, 240.00, 240.00, now() - interval '4 days'),
    (v_seller_id, v_g_c21, 'manual',   1, 110.00, 110.00, now() - interval '10 days'),
    (v_seller_id, v_g_e01, 'manual',   1, 120.00, 120.00, now() - interval '15 days');

  -- 4. Poblar notificaciones de vendedora (no leídas y leídas)
  insert into notifications (seller_id, audience, kind, payload, created_at, read_at) values
    (
      v_seller_id,
      'seller',
      'garment_sold',
      jsonb_build_object(
        'amount', 280.00,
        'customer_name', 'Sofía Morales',
        'garment', jsonb_build_object('code', 'B04', 'name', 'Conjunto deportivo turquesa')
      ),
      now() - interval '1 hour',
      null
    ),
    (
      v_seller_id,
      'seller',
      'garment_sold',
      jsonb_build_object(
        'amount', 180.00,
        'customer_name', 'Valeria Justiniano',
        'garment', jsonb_build_object('code', 'A08', 'name', 'Conjunto lino verano')
      ),
      now() - interval '3 hours',
      null
    ),
    (
      v_seller_id,
      'seller',
      'late_payment',
      jsonb_build_object(
        'amount', 240.00,
        'garment', jsonb_build_object('code', 'D03', 'name', 'Chaqueta eco cuero')
      ),
      now() - interval '1 day',
      now() - interval '20 hours'
    ),
    (
      v_seller_id,
      'seller',
      'garment_sold',
      jsonb_build_object(
        'amount', 190.00,
        'customer_name', 'Carla Rojas',
        'garment', jsonb_build_object('code', 'A15', 'name', 'Blusa manga globo')
      ),
      now() - interval '2 days',
      now() - interval '1 day'
    );
end $$;
