import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import type {
  ApiKeyRow, Backend, ChunkRow, DeviceInput, NewChunk, NewSession, ParticipantInput, Role, SessionFilter,
  SessionListItem, SessionRow,
} from './types.js';

const PG_UNIQUE_VIOLATION = '23505';

/** Postgres + Storage, service role anahtarıyla (§6.1). Yalnızca sunucu tarafında kullanılır. */
export class SupabaseBackend implements Backend {
  private db: SupabaseClient;

  constructor(url: string, serviceRoleKey: string, private bucket: string) {
    this.db = createClient(url, serviceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
  }

  /**
   * PostgREST varsayılan olarak sorgu başına en fazla 1000 satır döner (`max_rows`). Uzun oturumlarda parça listesi
   * bunu aşar (1000 parça ≈ 83 dk); bu yüzden listeler sayfalanır.
   */
  private static async all<T>(page: (from: number, to: number) => PromiseLike<{ data: T[] | null; error: { message: string } | null }>, what: string): Promise<T[]> {
    const out: T[] = [];
    for (let from = 0; ; from += 1000) {
      const r = await page(from, from + 999);
      if (r.error) throw new Error(`${what}: ${r.error.message}`);
      out.push(...(r.data ?? []));
      if ((r.data ?? []).length < 1000) return out;
    }
  }

  private static check<T>(r: { data: T; error: { message: string; code?: string } | null }, what: string): T {
    if (r.error) throw new Error(`${what}: ${r.error.message}`);
    return r.data;
  }

  async ping() {
    const r = await this.db.from('app_config').select('id').limit(1);
    SupabaseBackend.check(r, 'ping');
  }

  async findApiKeyByHash(hash: string): Promise<ApiKeyRow | null> {
    const r = await this.db.from('api_keys').select('id,name,role,revoked_at').eq('key_hash', hash).maybeSingle();
    return SupabaseBackend.check(r, 'api_keys') as ApiKeyRow | null;
  }

  async insertApiKey(name: string, hash: string, role: Role): Promise<ApiKeyRow> {
    const r = await this.db.from('api_keys').insert({ name, key_hash: hash, role }).select('id,name,role,revoked_at').single();
    return SupabaseBackend.check(r, 'insert api_key') as ApiKeyRow;
  }

  async getAppConfig() {
    const r = await this.db.from('app_config').select('config_version,config').eq('id', 1).maybeSingle();
    return SupabaseBackend.check(r, 'app_config') as { config_version: number; config: Record<string, unknown> } | null;
  }

  async upsertDevice(d: DeviceInput): Promise<string> {
    const r = await this.db
      .from('devices')
      .upsert({ ...d, last_seen: new Date().toISOString() }, { onConflict: 'install_id' })
      .select('id')
      .single();
    return (SupabaseBackend.check(r, 'upsert device') as { id: string }).id;
  }

  async upsertParticipant(p: ParticipantInput): Promise<string> {
    // Yalnızca verilen alanlar güncellenir (sonraki oturumlarda yalnızca `code` gelir).
    const row: Record<string, unknown> = { code: p.code, consent_version: p.consent_version };
    if (p.handedness != null) row.handedness = p.handedness;
    if (p.age_range != null) row.age_range = p.age_range;
    if (p.gender != null) row.gender = p.gender;
    const r = await this.db.from('participants').upsert(row, { onConflict: 'code' }).select('id').single();
    return (SupabaseBackend.check(r, 'upsert participant') as { id: string }).id;
  }

  async findParticipantByCode(code: string) {
    const r = await this.db.from('participants').select('id').eq('code', code).maybeSingle();
    return SupabaseBackend.check(r, 'participants') as { id: string } | null;
  }

  async findSessionByLocalId(localId: string) {
    const r = await this.db.from('sessions').select('*').eq('local_session_id', localId).maybeSingle();
    return SupabaseBackend.check(r, 'sessions') as SessionRow | null;
  }

  async insertSession(s: NewSession) {
    const r = await this.db.from('sessions').insert(s).select('*').single();
    if (r.error?.code === PG_UNIQUE_VIOLATION) return null;
    return SupabaseBackend.check(r, 'insert session') as SessionRow;
  }

  async getSession(id: string) {
    const r = await this.db.from('sessions').select('*').eq('id', id).maybeSingle();
    return SupabaseBackend.check(r, 'sessions') as SessionRow | null;
  }

  async updateSession(id: string, patch: Partial<SessionRow>) {
    const r = await this.db.from('sessions').update(patch).eq('id', id);
    SupabaseBackend.check(r, 'update session');
  }

  async listSessions(f: SessionFilter): Promise<SessionListItem[]> {
    let q = this.db
      .from('sessions')
      .select('*, participants(code)')
      .order('started_at', { ascending: false })
      .order('id', { ascending: false })
      .limit(f.limit);
    if (f.label_kind) q = q.eq('label_kind', f.label_kind);
    if (f.bot_tool) q = q.eq('bot_tool', f.bot_tool);
    if (f.status) q = q.eq('status', f.status);
    if (f.from) q = q.gte('started_at', f.from);
    if (f.to) q = q.lt('started_at', f.to);
    if (f.participant_code) {
      const p = await this.findParticipantByCode(f.participant_code);
      if (!p) return [];
      q = q.eq('participant_id', p.id);
    }
    if (f.after) {
      const { started_at: t, id } = f.after;
      // Zaman damgası `+`/`:` içerdiği için çift tırnakla verilir.
      q = q.or(`started_at.lt."${t}",and(started_at.eq."${t}",id.lt.${id})`);
    }
    const rows = SupabaseBackend.check(await q, 'list sessions') as (SessionRow & { participants: { code: string } | null })[];
    return rows.map(({ participants, ...s }) => ({ ...s, participant_code: participants?.code ?? null }));
  }

  async sessionIdsForParticipant(participantId: string) {
    const rows = await SupabaseBackend.all<{ id: string }>(
      (f, t) => this.db.from('sessions').select('id').eq('participant_id', participantId).order('id').range(f, t), 'sessions');
    return rows.map((x) => x.id);
  }

  async getChunk(sessionId: string, seq: number) {
    const r = await this.db.from('chunks').select('*').eq('session_id', sessionId).eq('seq', seq).maybeSingle();
    return SupabaseBackend.check(r, 'chunks') as ChunkRow | null;
  }

  async insertChunk(c: NewChunk) {
    const r = await this.db.from('chunks').insert(c);
    if (r.error?.code === PG_UNIQUE_VIOLATION) return false;
    SupabaseBackend.check(r, 'insert chunk');
    return true;
  }

  async incrementReceived(sessionId: string) {
    const r = await this.db.rpc('increment_received', { p_session: sessionId });
    SupabaseBackend.check(r, 'increment_received');
  }

  async listChunks(sessionId: string) {
    return SupabaseBackend.all<ChunkRow>(
      (f, t) => this.db.from('chunks').select('*').eq('session_id', sessionId).order('seq').range(f, t), 'chunks');
  }

  async chunkSeqs(sessionId: string) {
    const rows = await SupabaseBackend.all<{ seq: number }>(
      (f, t) => this.db.from('chunks').select('seq').eq('session_id', sessionId).order('seq').range(f, t), 'chunks');
    return rows.map((x) => x.seq);
  }

  async putObject(path: string, body: Buffer) {
    const r = await this.db.storage.from(this.bucket).upload(path, body, { upsert: false, contentType: 'application/gzip' });
    if (r.error) {
      const e = r.error as { statusCode?: string | number; message: string };
      if (String(e.statusCode) === '409' || /already exists|duplicate/i.test(e.message)) return 'exists';
      throw new Error(`storage upload: ${e.message}`);
    }
    return 'created';
  }

  async getObject(path: string) {
    const r = await this.db.storage.from(this.bucket).download(path);
    if (r.error || !r.data) return null;
    return Buffer.from(await r.data.arrayBuffer());
  }

  async removeObjects(paths: string[]) {
    let n = 0;
    for (let i = 0; i < paths.length; i += 100) {
      const r = await this.db.storage.from(this.bucket).remove(paths.slice(i, i + 100));
      n += (SupabaseBackend.check(r, 'storage remove') ?? []).length;
    }
    return n;
  }

  async listObjects(prefix: string) {
    const dir = prefix.replace(/\/$/, '');
    const out: string[] = [];
    for (let offset = 0; ; offset += 1000) {
      const r = await this.db.storage.from(this.bucket).list(dir, { limit: 1000, offset });
      const items = SupabaseBackend.check(r, 'storage list') ?? [];
      out.push(...items.filter((x) => x.id).map((x) => `${dir}/${x.name}`));
      if (items.length < 1000) break;
    }
    return out;
  }

  async signedUrls(paths: string[], expiresInSec: number) {
    const m = new Map<string, string>();
    for (let i = 0; i < paths.length; i += 500) {
      const r = await this.db.storage.from(this.bucket).createSignedUrls(paths.slice(i, i + 500), expiresInSec);
      for (const x of SupabaseBackend.check(r, 'signed urls') ?? []) if (x.path && x.signedUrl) m.set(x.path, x.signedUrl);
    }
    return m;
  }

  async deleteParticipant(participantId: string) {
    const r = await this.db.from('participants').delete().eq('id', participantId);
    SupabaseBackend.check(r, 'delete participant');
  }

  async audit(action: string, detail: Record<string, unknown>) {
    const r = await this.db.from('audit_log').insert({ action, detail });
    SupabaseBackend.check(r, 'audit');
  }
}
