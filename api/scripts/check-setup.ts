/**
 * Supabase kurulumunu doğrular (service role ile): tablolar, app_config satırı, private bucket, RPC.
 *   npm run check:setup
 */
import 'dotenv/config';
import { createClient } from '@supabase/supabase-js';

const url = process.env.SUPABASE_URL, key = process.env.SUPABASE_SERVICE_ROLE_KEY, bucket = process.env.STORAGE_BUCKET ?? 'raw-chunks';
if (!url || !key) { console.error('SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY missing (api/.env)'); process.exit(2); }
const db = createClient(url, key, { auth: { persistSession: false } });
let ok = true;
const check = (name: string, pass: boolean, info = '') => { console.log(`${pass ? 'OK  ' : 'FAIL'} ${name}${info ? ` — ${info}` : ''}`); ok &&= pass; };

for (const t of ['api_keys', 'app_config', 'participants', 'devices', 'sessions', 'chunks', 'audit_log']) {
  const r = await db.from(t).select('*', { count: 'exact', head: true });
  check(`table ${t}`, !r.error, r.error?.message ?? `${r.count} rows`);
}
const cfg = await db.from('app_config').select('config_version').eq('id', 1).maybeSingle();
check('app_config row', !!cfg.data, cfg.error?.message ?? `config_version=${cfg.data?.config_version}`);
const b = await db.storage.getBucket(bucket);
check(`bucket ${bucket}`, !!b.data && b.data.public === false, b.error?.message ?? `public=${b.data?.public}`);
const rpc = await db.rpc('increment_received', { p_session: '00000000-0000-0000-0000-000000000000' });
check('rpc increment_received', !rpc.error, rpc.error?.message ?? '');
const keys = await db.from('api_keys').select('role').is('revoked_at', null);
check('active app key exists', (keys.data ?? []).some((k) => k.role === 'app'), 'create with: npm run key:create -- --name research-app --role app');
process.exit(ok ? 0 : 1);
