/**
 * Veri toplama ilerlemesi: Supabase'teki oturumların etiket / bot aracı / host / katılımcı dağılımı ve SPEC §9.3 matrisine
 * göre eksikler. Kişisel veri yazdırmaz (yalnızca katılımcı kodları ve sayılar).
 *   npm run progress [-- --since 2026-11-13]
 */
import 'dotenv/config';
import { parseArgs } from 'node:util';
import { createClient } from '@supabase/supabase-js';

const { values } = parseArgs({ options: { since: { type: 'string' } } });
const db = createClient(process.env.SUPABASE_URL!, process.env.SUPABASE_SERVICE_ROLE_KEY!, { auth: { persistSession: false } });

type Row = {
  id: string; label_kind: string; bot_tool: string | null; bot_host: string | null; placement: string; status: string;
  total_chunks: number | null; received_chunks: number; started_at: string; notes: string | null;
  stats: { duration_s?: number } | null; participants: { code: string } | null;
};

async function all(): Promise<Row[]> {
  const out: Row[] = [];
  for (let from = 0; ; from += 1000) {
    let q = db.from('sessions')
      .select('id,label_kind,bot_tool,bot_host,placement,status,total_chunks,received_chunks,started_at,notes,stats,participants(code)')
      .order('started_at').range(from, from + 999);
    if (values.since) q = q.gte('started_at', values.since);
    const r = await q;
    if (r.error) throw new Error(r.error.message);
    out.push(...(r.data as unknown as Row[]));
    if (r.data.length < 1000) return out;
  }
}

const TARGET = 20; // §9.3: her kombinasyon için en az 20 oturum
// §9.3 matrisi: host × araç (✅ hücreleri). "real_device" satırları konum (placement) ile ayrılır.
const MATRIX: { host: string; placement?: string; tools: string[] }[] = [
  { host: 'emulator_android_studio', tools: ['adb_input', 'appium', 'autoclicker_app', 'accessibility_app'] },
  { host: 'emulator_genymotion', tools: ['adb_input', 'appium'] },
  { host: 'emulator_bluestacks', tools: ['adb_input', 'autoclicker_app'] },
  { host: 'real_device', placement: 'table', tools: ['adb_input', 'appium', 'autoclicker_app', 'accessibility_app', 'scrcpy_remote'] },
  { host: 'real_device', placement: 'holder', tools: ['adb_input', 'autoclicker_app'] },
  { host: 'real_device', placement: 'hand', tools: ['adb_input', 'accessibility_app'] },
];

async function main() {
  const rows = await all();
  // Geliştirme/test oturumları (notes ile işaretli) ayrı sayılır.
  const isTest = (r: Row) => /m1-live-sensor-dev|contract-test|A10 acceptance/i.test(r.notes ?? '') || r.participants?.code === 'P900';
  const real = rows.filter((r) => !isTest(r));
  const done = real.filter((r) => r.status === 'complete');
  const mins = (rs: Row[]) => (rs.reduce((a, r) => a + (r.stats?.duration_s ?? 0), 0) / 60).toFixed(0);

  console.log(`sessions: ${rows.length} total, ${rows.length - real.length} dev/test (excluded below)`);
  console.log(`data sessions: ${real.length} (${done.length} complete, ${real.filter((r) => r.status !== 'complete').length} not complete) · ${mins(done)} min recorded\n`);

  const humans = done.filter((r) => r.label_kind === 'human');
  const perP = new Map<string, number>();
  for (const r of humans) perP.set(r.participants?.code ?? '?', (perP.get(r.participants?.code ?? '?') ?? 0) + 1);
  console.log(`HUMAN: ${humans.length} sessions, ${perP.size} participants, ${mins(humans)} min`);
  if (perP.size) console.log('  ' + [...perP].sort().map(([c, n]) => `${c}:${n}`).join('  '));

  const bots = done.filter((r) => r.label_kind === 'bot');
  console.log(`\nBOT: ${bots.length} sessions, ${mins(bots)} min — §9.3 matrix (target ${TARGET} each)`);
  for (const m of MATRIX) {
    const cells = m.tools.map((t) => {
      const n = bots.filter((r) => r.bot_host === m.host && r.bot_tool === t && (!m.placement || r.placement === m.placement)).length;
      return `${t}=${n}${n >= TARGET ? '✓' : ''}`;
    });
    console.log(`  ${(m.host + (m.placement ? `/${m.placement}` : '')).padEnd(30)} ${cells.join('  ')}`);
  }
  const other = bots.filter((r) => !MATRIX.some((m) => m.host === r.bot_host && m.tools.includes(r.bot_tool ?? '') && (!m.placement || r.placement === m.placement)));
  if (other.length) console.log(`  (outside matrix: ${other.length} — e.g. ${[...new Set(other.map((r) => `${r.bot_host}/${r.placement}/${r.bot_tool}`))].slice(0, 4).join(', ')})`);

  const open = real.filter((r) => r.status !== 'complete');
  if (open.length) {
    console.log(`\nNOT COMPLETE (${open.length}) — still uploading, or chunks lost on the phone:`);
    for (const r of open.slice(-10)) console.log(`  ${r.id.slice(0, 8)} ${r.started_at.slice(0, 16)} ${r.status} ${r.received_chunks}/${r.total_chunks ?? '?'} ${r.label_kind}${r.participants ? ' ' + r.participants.code : ''}`);
  }
}

main().catch((e) => { console.error(String(e)); process.exit(1); });
