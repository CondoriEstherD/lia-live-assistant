-- Adds 8 garments with real product photos to the demo seller "Ilse Boutique Live".
-- Test data only. Safe to run more than once: a code that already exists in the catalog is skipped.
-- Photos live in Supabase Storage: garment-photos/seed/ilse/catalog/<code>.jpg
-- Prices, sizes and stock are demo values; the seller can edit them in the app.

do $$
declare
  v_seller_id uuid;
  v_photos text := 'https://qxlirecxxqbjhxsbfdwu.supabase.co/storage/v1/object/public/garment-photos/seed/ilse/catalog/';
begin
  select id into v_seller_id from sellers where clerk_user_id = 'user_3MQXdU9imTocAfTOPCJS5AVojpO';
  if v_seller_id is null then
    raise notice 'Seller not found: run demo_ilse_boutique.sql first.';
    return;
  end if;

  insert into garments (seller_id, code, name, description, size, color, price, stock, status, photo_url)
  select v_seller_id, g.code, g.name, g.description, g.size, g.color, g.price, g.stock, 'in_catalog', v_photos || g.code || '.jpg'
  from (values
    ('P01', 'Jean flare celeste tiro alto', 'Bota ancha, lavado claro y basta con dobladillo.', '36', 'Celeste', 185.00, 2),
    ('P02', 'Jean flare azul oscuro', 'Denim índigo con costuras en contraste, tiro alto.', '38', 'Azul oscuro', 189.00, 2),
    ('P03', 'Pantalón acampanado gris', 'Tejido de punto suave con pretina doblada.', 'M', 'Gris', 120.00, 3),
    ('T01', 'Top hombros descubiertos con encaje', 'Tejido acanalado con detalle de encaje negro.', 'S', 'Blanco', 135.00, 2),
    ('T02', 'Top asimétrico argolla dorada', 'Un hombro, con argolla dorada y fruncido lateral.', 'S', 'Rosado', 95.00, 3),
    ('T03', 'Camisa a rayas con nudo', 'Rayas celeste y blanco, manga corta, se anuda al frente.', 'M', 'Celeste', 110.00, 2),
    ('P04', 'Jean wide leg azul medio', 'Pierna ancha y recta, tiro alto.', '36', 'Azul', 175.00, 2),
    ('P05', 'Jean wide leg celeste', 'Pierna ancha, lavado claro, tiro alto.', '38', 'Celeste', 169.00, 1)
  ) as g(code, name, description, size, color, price, stock)
  where not exists (
    select 1 from garments x
    where x.seller_id = v_seller_id and lower(x.code) = lower(g.code) and x.status <> 'archived'
  );
end $$;
