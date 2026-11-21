-- Public bucket for garment photos. Lia (WhatsApp) and the seller app show these photos,
-- so they must be readable by URL; only the service role writes here until the app
-- upload policy (Clerk user -> own folder) is added.
do $$
begin
  if to_regclass('storage.buckets') is not null then
    insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
    values ('garment-photos', 'garment-photos', true, 5242880, array['image/jpeg', 'image/png', 'image/webp'])
    on conflict (id) do update set public = true;
  end if;
end $$;
