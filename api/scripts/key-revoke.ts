/** API anahtarını iptal eder (revoked_at = now()). Önbellek nedeniyle API'de en geç 60 sn içinde etkili olur.
 *   npm run key:revoke -- --id <uuid>
 */
import 'dotenv/config';
import { parseArgs } from 'node:util';
import { createClient } from '@supabase/supabase-js';

const { values } = parseArgs({ options: { id: { type: 'string' } } });
if (!values.id) { console.error('usage: npm run key:revoke -- --id <uuid>'); process.exit(2); }
const db = createClient(process.env.SUPABASE_URL!, process.env.SUPABASE_SERVICE_ROLE_KEY!, { auth: { persistSession: false } });
const r = await db.from('api_keys').update({ revoked_at: new Date().toISOString() }).eq('id', values.id).is('revoked_at', null).select('id,name,role,revoked_at');
if (r.error) { console.error(r.error.message); process.exit(1); }
console.log(r.data?.length ? `revoked ${JSON.stringify(r.data[0])}` : 'no active key with that id');
