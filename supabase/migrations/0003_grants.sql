-- Erişim: yalnızca service role (API). RLS zaten açık ve politika yok (§7); bu dosya ikinci bir katman ekler
-- ve yeni Supabase projelerinde tabloların service_role'e açık olmasını garanti eder.

grant usage on schema public to service_role;
grant select, insert, update, delete on all tables in schema public to service_role;
grant usage, select on all sequences in schema public to service_role;
grant execute on function public.increment_received(uuid) to service_role;

revoke all on all tables in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
-- Fonksiyonlar varsayılan olarak PUBLIC'e açıktır; REST üzerinden anon çağıramasın.
revoke execute on function public.increment_received(uuid) from public, anon, authenticated;
