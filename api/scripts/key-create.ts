/**
 * API anahtarı üretir (§6.5): `npm run key:create -- --name research-app --role app`
 * Anahtar yalnızca bir kez ekrana yazılır; DB'de yalnızca SHA-256 özeti tutulur.
 */
import 'dotenv/config';
import { randomBytes } from 'node:crypto';
import { parseArgs } from 'node:util';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createBackend } from '../src/backend/index.js';
import { loadConfig } from '../src/config.js';
import { sha256Hex } from '../src/util.js';

const { values } = parseArgs({
  options: {
    name: { type: 'string' },
    role: { type: 'string', default: 'app' },
    /** Anahtarı ekrana yazmak yerine bir .properties dosyasına `humansignal.apiKey=` olarak yazar. */
    'write-properties': { type: 'string' },
  },
});
if (!values.name || (values.role !== 'app' && values.role !== 'admin')) {
  console.error('usage: npm run key:create -- --name <name> --role app|admin');
  process.exit(2);
}
const config = loadConfig();
if (config.DATA_BACKEND !== 'supabase') {
  console.error('key:create needs DATA_BACKEND=supabase (memory keys come from DEV_API_KEYS)');
  process.exit(2);
}
const backend = await createBackend(config);
const key = `hs_${values.role}_${randomBytes(24).toString('base64url')}`;
const row = await backend.insertApiKey(values.name, sha256Hex(key), values.role);
console.log(`created key id=${row.id} name=${row.name} role=${row.role}`);
const out = values['write-properties'];
if (out) {
  const lines = existsSync(out) ? readFileSync(out, 'utf8').split('\n').filter((l) => !l.startsWith('humansignal.apiKey=')) : [];
  while (lines.length && lines[lines.length - 1] === '') lines.pop();
  lines.push(`humansignal.apiKey=${key}`, '');
  writeFileSync(out, lines.join('\n'), { mode: 0o600 });
  console.log(`key written to ${out} (not printed)`);
} else {
  console.log(`\n  ${key}\n`);
  console.log('Store it now — it cannot be shown again.');
}
