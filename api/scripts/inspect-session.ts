/**
 * Supabase'teki bir oturumu okur ve parçalarını doğrular (SHA-256, gzip, NDJSON, header.seq, akış sayıları).
 * Kişisel veri yazdırmaz; yalnızca sayılar ve akış adları.
 *   npm run inspect -- [session_id]        # boşsa en son oturum
 */
import 'dotenv/config';
import { createClient } from '@supabase/supabase-js';
import { gunzipSync } from 'node:zlib';
import { sha256Hex } from '../src/util.js';

async function main() {
  const url = process.env.SUPABASE_URL, key = process.env.SUPABASE_SERVICE_ROLE_KEY, bucket = process.env.STORAGE_BUCKET ?? 'raw-chunks';
  if (!url || !key) throw new Error('SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY missing (api/.env)');
  const db = createClient(url, key, { auth: { persistSession: false } });

  const args = process.argv.slice(2);
  const id = args.find((a) => !a.startsWith('--'));
  // A6: kayıtlarda yazılan metin geçmemeli. Görevlerdeki sahte değerler + IBAN biçimi aranır.
  const FORBID = ['Kira Kasım', 'Market alışverişi', 'Elektrik faturası', 'Doğum günü hediyesi', 'Borç ödemesi', 'Aidat',
    'Ayşe Yılmaz', 'Mehmet Demir', 'Zeynep Kaya', 'Can Öztürk'];
  const fold = (x: string) => x.replace(/[çğıöşüÇĞİÖŞÜ]/g, (c) => ({ ç: 'c', ğ: 'g', ı: 'i', ö: 'o', ş: 's', ü: 'u', Ç: 'C', Ğ: 'G', İ: 'I', Ö: 'O', Ş: 'S', Ü: 'U' } as Record<string, string>)[c]!);
  const forbidden = [...new Set(FORBID.flatMap((w) => [w, fold(w)]).map((w) => w.toLowerCase()))];
  const IBAN = /TR\d{2}\s?\d{4}/i;
  let leaks = 0;
  const q = db.from('sessions').select('id,local_session_id,status,label_kind,bot_tool,bot_host,total_chunks,received_chunks,stop_reason,stats,clock,started_at');
  const { data: s, error } = id ? await q.eq('id', id).single() : await q.order('started_at', { ascending: false }).limit(1).single();
  if (error || !s) throw new Error(`session not found: ${error?.message}`);

  const sensorClock = (s.clock as { sensor_clock?: Record<string, { basis: string }> }).sensor_clock ?? {};
  console.log(`session ${s.id}  local=${s.local_session_id}`);
  console.log(`  status=${s.status} label=${s.label_kind}${s.bot_tool ? `/${s.bot_tool}/${s.bot_host}` : ''} chunks=${s.received_chunks}/${s.total_chunks} stop=${s.stop_reason}`);
  console.log(`  sensor_clock: ${Object.entries(sensorClock).map(([k, v]) => `${k}=${v.basis}`).join(' ') || '-'}`);
  console.log(`  stats: ${JSON.stringify(s.stats)}`);

  const { data: chunks } = await db.from('chunks').select('seq,storage_path,sha256,size_bytes').eq('session_id', s.id).order('seq');
  const totals = new Map<string, number>();
  let samples = 0, bad = 0, maskedTouch = 0, rawTouch = 0;
  const textKeys = new Set<string>();
  const keyLines: Record<string, unknown>[] = [];
  const touches: { t: number; a: string; h: number; dev: number; tool: string; pr: number }[] = [];
  const lifecycle: { t: number; e: string }[] = [];
  const accT: number[] = [];
  for (const c of chunks ?? []) {
    const f = await db.storage.from(bucket).download(c.storage_path);
    if (f.error || !f.data) { console.log(`  FAIL seq=${c.seq} download: ${f.error?.message}`); bad++; continue; }
    const gz = Buffer.from(await f.data.arrayBuffer());
    const shaOk = sha256Hex(gz) === c.sha256 && gz.length === c.size_bytes;
    const raw = gunzipSync(gz).toString('utf8');
    const lower = raw.toLowerCase();
    for (const w of forbidden) if (lower.includes(w)) { leaks++; console.log(`  LEAK seq=${c.seq}: "${w}"`); }
    if (IBAN.test(raw)) { leaks++; console.log(`  LEAK seq=${c.seq}: IBAN-like string`); }
    const lines = raw.trim().split('\n').map((l) => JSON.parse(l) as Record<string, unknown>);
    const headerOk = lines[0]?.k === 'header' && lines[0]?.seq === c.seq;
    for (const l of lines.slice(1)) {
      const k = String(l.k);
      totals.set(k, (totals.get(k) ?? 0) + 1);
      if (k === 'sensor') { samples += (l.t as unknown[]).length; if (l.s === 'acc') accT.push(...(l.t as number[])); }
      if (k === 'lifecycle') lifecycle.push({ t: l.t as number, e: String(l.e) });
      if (k === 'touch') {
        const ps = l.p as Record<string, unknown>[];
        for (const p of ps) p.masked ? maskedTouch++ : rawTouch++;
        touches.push({ t: l.t as number, a: l.a as string, h: l.h as number, dev: l.dev as number, tool: String(ps[0]?.tool), pr: Number(ps[0]?.pr) });
      }
      if (k === 'text') Object.keys(l).forEach((x) => textKeys.add(x));
      if (k === 'key') keyLines.push(l);
    }
    if (!shaOk || !headerOk) { bad++; console.log(`  FAIL seq=${c.seq} sha=${shaOk} header=${headerOk}`); }
  }
  console.log(`  verified ${(chunks ?? []).length - bad}/${(chunks ?? []).length} chunks (sha256 + gzip + header.seq)`);
  console.log(`  lines by stream: ${[...totals].map(([k, n]) => `${k}=${n}`).join(' ')}  sensor_samples=${samples}`);
  // Gizlilik özeti (§3.6): maskelenmiş dokunmalarda x/y yok, text satırında içerik alanı yok.
  console.log(`  privacy: touch pointers masked=${maskedTouch} raw=${rawTouch}; text fields={${[...textKeys].sort().join(',')}}; key lines=${keyLines.length} (kc: ${keyLines.map((k) => k.kc).join(',')}; kid set: ${keyLines.filter((k) => k.kid != null).length})`);
  // Dokunma özeti: modlar/insan arasındaki fark burada görünmeli.
  touches.sort((a, b) => a.t - b.t);
  const downs: number[] = [], durs: number[] = [], moves: number[] = [];
  let cur: { t: number; m: number } | null = null;
  for (const e of touches) {
    if (e.a === 'DOWN') { cur = { t: e.t, m: 0 }; downs.push(e.t); }
    else if (e.a === 'MOVE' && cur) cur.m++;
    else if ((e.a === 'UP' || e.a === 'CANCEL') && cur) { durs.push((e.t - cur.t) / 1e6); moves.push(cur.m); cur = null; }
  }
  const med = (xs: number[]) => (xs.length ? [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)]! : NaN);
  const prs = touches.map((e) => e.pr).filter(Number.isFinite);
  const mean = prs.reduce((a, b) => a + b, 0) / (prs.length || 1);
  const sd = Math.sqrt(prs.reduce((a, b) => a + (b - mean) ** 2, 0) / (prs.length || 1));
  console.log(`  touch: gestures=${downs.length} tools={${[...new Set(touches.map((e) => e.tool))].join(',')}} devs={${[...new Set(touches.map((e) => e.dev))].join(',')}} ` +
    `historical=${touches.filter((e) => e.h === 1).length}/${touches.length} moves/gesture(med)=${med(moves)} down→up(med)=${med(durs).toFixed(0)}ms ` +
    `pressure mean=${mean.toFixed(3)} sd=${sd.toFixed(3)} distinct=${new Set(prs.map((p) => p.toFixed(3))).size}`);
  // A5: yaşam döngüsü olayları ve ivme akışındaki boşluklar (arka planda toplama durmalı).
  accT.sort((a, b) => a - b);
  const gaps: string[] = [];
  for (let i = 1; i < accT.length; i++) if (accT[i]! - accT[i - 1]! > 1e9) gaps.push(`${(accT[i - 1]! / 1e9).toFixed(1)}s→${(accT[i]! / 1e9).toFixed(1)}s`);
  lifecycle.sort((a, b) => a.t - b.t);
  console.log(`  lifecycle: ${lifecycle.map((x) => `${x.e}@${(x.t / 1e9).toFixed(1)}s`).join(' ') || '-'}`);
  console.log(`  acc gaps >1s: ${gaps.join(' ') || 'none'}`);
  console.log(`  A6 content scan: ${leaks === 0 ? 'no typed text / IBAN found' : `${leaks} LEAKS`} (${forbidden.length} phrases + IBAN pattern)`);
  if (bad || leaks) process.exit(1);
}

main().catch((e) => { console.error(String(e)); process.exit(1); });
