/**
 * `supabase/migrations/*.sql` dosyalarını sırayla uygular (psql ile, SUPABASE_DB_URL).
 * Uygulananlar `public.hs_schema_migrations` tablosunda tutulur; tekrar çalıştırmak güvenlidir.
 *   npm run db:migrate
 */
import 'dotenv/config';
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const url = process.env.SUPABASE_DB_URL;
if (!url) { console.error('SUPABASE_DB_URL is not set (api/.env)'); process.exit(2); }

const dir = join(import.meta.dirname, '..', '..', 'supabase', 'migrations');
const psql = (sql: string, args: string[] = []) =>
  execFileSync('psql', [url, '-v', 'ON_ERROR_STOP=1', '-q', '-X', '-t', '-A', ...args], { input: sql, encoding: 'utf8' });

psql(`create table if not exists public.hs_schema_migrations (name text primary key, applied_at timestamptz not null default now());
      alter table public.hs_schema_migrations enable row level security;`);
const done = new Set(psql('select name from public.hs_schema_migrations;').split('\n').filter(Boolean));

for (const f of readdirSync(dir).filter((x) => x.endsWith('.sql')).sort()) {
  if (done.has(f)) { console.log(`skip    ${f}`); continue; }
  const sql = readFileSync(join(dir, f), 'utf8');
  // Tek transaction: yarım kalmış migration olmaz.
  psql(`begin;\n${sql}\ninsert into public.hs_schema_migrations(name) values ('${f.replace(/'/g, "''")}');\ncommit;`);
  console.log(`applied ${f}`);
}
