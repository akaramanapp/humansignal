/**
 * Kabul kriteri A10 (§10): `DELETE /v1/admin/participants/:code` tüm Storage dosyalarını ve satırları siler.
 * Çalışan API'ye karşı uçtan uca; sonucu service role ile doğrudan Postgres + Storage'dan kontrol eder.
 * Yalnızca test katılımcıları (P999 silinir, P998 kontrol grubudur ve en sonda o da silinir) kullanılır.
 *
 *   HS_API_URL=http://localhost:8787 HS_APP_KEY=… HS_ADMIN_KEY=… npm run a10
 */
import 'dotenv/config';
import { randomUUID } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import { createClient } from '@supabase/supabase-js';
import { sha256Hex } from '../src/util.js';

const API = process.env.HS_API_URL ?? 'http://localhost:8787';
const APP = process.env.HS_APP_KEY, ADMIN = process.env.HS_ADMIN_KEY;
const bucket = process.env.STORAGE_BUCKET ?? 'raw-chunks';
if (!APP || !ADMIN) { console.error('HS_APP_KEY and HS_ADMIN_KEY required'); process.exit(2); }
const db = createClient(process.env.SUPABASE_URL!, process.env.SUPABASE_SERVICE_ROLE_KEY!, { auth: { persistSession: false } });

let failed = 0;
const check = (name: string, ok: boolean, info = '') => { console.log(`${ok ? 'OK  ' : 'FAIL'} ${name}${info ? ` — ${info}` : ''}`); if (!ok) failed++; };

async function call(method: string, path: string, key: string, body?: unknown, headers: Record<string, string> = {}) {
  const r = await fetch(API + path, {
    method,
    headers: { 'x-api-key': key, ...(body !== undefined && !(body instanceof Buffer) ? { 'content-type': 'application/json' } : {}), ...headers },
    body: body instanceof Buffer ? body : body !== undefined ? JSON.stringify(body) : undefined,
  });
  return { status: r.status, json: (await r.json().catch(() => null)) as any };
}

async function session(code: string, chunks: number): Promise<string> {
  const r = await call('POST', '/v1/sessions', APP!, {
    local_session_id: randomUUID(), install_id: 'a10-check-install', participant: { code, handedness: 'unknown', age_range: 'unknown' },
    consent_version: 'a10-test', label: { kind: 'human', bot_tool: null, bot_host: null },
    context: { placement: 'table', posture: 'na', hands: 'na' }, notes: 'A10 acceptance test', device: { device: { model: 'a10' } },
    clock: { t0_elapsed_ns: 1, t0_uptime_ns: 1, t0_wall_ms: Date.now() }, sdk_version: 'a10', app_version: null, config_version: 1,
  });
  if (r.status !== 201) throw new Error(`create session ${r.status} ${JSON.stringify(r.json)}`);
  const id = r.json.session_id as string;
  for (let seq = 0; seq < chunks; seq++) {
    const body = gzipSync(Buffer.from(`{"k":"header","v":1,"seq":${seq}}\n`));
    const p = await call('PUT', `/v1/sessions/${id}/chunks/${seq}`, APP!, body, {
      'content-type': 'application/octet-stream', 'x-chunk-sha256': sha256Hex(body),
      'x-chunk-t-start': String(seq * 5e9), 'x-chunk-t-end': String((seq + 1) * 5e9), 'x-chunk-streams': 'marker',
    });
    if (p.status !== 201) throw new Error(`put chunk ${p.status}`);
  }
  return id;
}

const objects = async (sid: string) => ((await db.storage.from(bucket).list(`sessions/${sid}`)).data ?? []).filter((x) => x.id).length;
const rows = async (table: string, col: string, val: string) => (await db.from(table).select('*', { count: 'exact', head: true }).eq(col, val)).count ?? -1;

async function main() {
  // Önceki yarım kalmış çalıştırmalardan artık kalmasın.
  for (const c of ['P999', 'P998']) await call('DELETE', `/v1/admin/participants/${c}`, ADMIN!);

  const a = await session('P999', 3);
  const b = await session('P999', 2);
  const control = await session('P998', 1);
  // DB satırı olmayan yetim nesne (Storage'a yazılmış ama DB'ye geçmemiş yükleme):
  await db.storage.from(bucket).upload(`sessions/${b}/000099.ndjson.gz`, gzipSync(Buffer.from('orphan')), { contentType: 'application/gzip' });
  check('setup: objects in storage', (await objects(a)) === 3 && (await objects(b)) === 3, `a=${await objects(a)} b=${await objects(b)} (incl. orphan)`);

  const auditBefore = (await db.from('audit_log').select('id', { count: 'exact', head: true })).count ?? 0;
  const forbidden = await call('DELETE', '/v1/admin/participants/P999', APP!);
  check('app key cannot delete (403)', forbidden.status === 403);

  const del = await call('DELETE', '/v1/admin/participants/P999', ADMIN!);
  check('DELETE returns counts', del.status === 200 && del.json?.deleted_sessions === 2 && del.json?.deleted_chunks === 6, JSON.stringify(del.json));

  check('storage: session A empty', (await objects(a)) === 0);
  check('storage: session B empty (orphan too)', (await objects(b)) === 0);
  check('db: sessions gone', (await rows('sessions', 'id', a)) === 0 && (await rows('sessions', 'id', b)) === 0);
  check('db: chunks gone', (await rows('chunks', 'session_id', a)) === 0 && (await rows('chunks', 'session_id', b)) === 0);
  check('db: participant gone', (await rows('participants', 'code', 'P999')) === 0);
  check('control participant untouched', (await objects(control)) === 1 && (await rows('sessions', 'id', control)) === 1);

  const audit = await db.from('audit_log').select('action,detail').order('id', { ascending: false }).limit(1).single();
  const auditAfter = (await db.from('audit_log').select('id', { count: 'exact', head: true })).count ?? 0;
  check('audit_log row written', auditAfter === auditBefore + 1 && audit.data?.action === 'delete_participant', JSON.stringify(audit.data?.detail));
  check('audit_log has no personal data', !JSON.stringify(audit.data).includes('P999'));

  check('second DELETE → 404', (await call('DELETE', '/v1/admin/participants/P999', ADMIN!)).status === 404);

  // Temizlik: kontrol katılımcısı da silinir.
  const cleanup = await call('DELETE', '/v1/admin/participants/P998', ADMIN!);
  check('cleanup control participant', cleanup.status === 200 && (await objects(control)) === 0);

  console.log(failed ? `\nA10 FAILED (${failed})` : '\nA10 PASSED');
  process.exit(failed ? 1 : 0);
}

main().catch((e) => { console.error(String(e)); process.exit(1); });
