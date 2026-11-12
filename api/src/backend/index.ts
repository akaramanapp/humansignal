import type { Config } from '../config.js';
import { MemoryBackend } from './memory.js';
import { SupabaseBackend } from './supabase.js';
import type { Backend } from './types.js';
import { sha256Hex } from '../util.js';

export async function createBackend(c: Config): Promise<Backend> {
  if (c.DATA_BACKEND === 'memory') {
    const m = new MemoryBackend();
    // "app:anahtar,admin:anahtar" — yalnızca yerel geliştirme.
    for (const pair of (c.DEV_API_KEYS ?? '').split(',').filter(Boolean)) {
      const [role, key] = pair.split(':');
      if ((role === 'app' || role === 'admin') && key) await m.insertApiKey(`dev-${role}`, sha256Hex(key), role);
    }
    return m;
  }
  return new SupabaseBackend(c.SUPABASE_URL!, c.SUPABASE_SERVICE_ROLE_KEY!, c.STORAGE_BUCKET);
}
