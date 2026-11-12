import { randomUUID } from 'node:crypto';
import type {
  ApiKeyRow, Backend, ChunkRow, DeviceInput, NewChunk, NewSession, ParticipantInput, Role, SessionFilter,
  SessionListItem, SessionRow,
} from './types.js';
import { DEFAULT_APP_CONFIG } from '../defaultConfig.js';

interface ParticipantRow extends ParticipantInput { id: string; consent_at: string; created_at: string }

/** Bellek içi Backend: testler ve `DATA_BACKEND=memory` ile yerel geliştirme için. Şema kısıtlarını taklit eder. */
export class MemoryBackend implements Backend {
  apiKeys = new Map<string, ApiKeyRow & { key_hash: string }>();
  participants = new Map<string, ParticipantRow>();
  devices = new Map<string, DeviceInput & { id: string; last_seen: string }>();
  sessions = new Map<string, SessionRow>();
  chunks = new Map<string, ChunkRow>(); // `${session}/${seq}`
  objects = new Map<string, Buffer>();
  auditLog: { at: string; action: string; detail: Record<string, unknown> }[] = [];
  appConfig: { config_version: number; config: Record<string, unknown> } | null = {
    config_version: 1,
    config: DEFAULT_APP_CONFIG,
  };
  /** Testler için hata enjeksiyonu. */
  failNext: Partial<Record<'ping' | 'insertChunk' | 'putObject', Error>> = {};
  private clock = 0;

  private now() {
    // Sıralama testleri için kesin artan zaman damgası.
    return new Date(Date.UTC(2026, 10, 12) + this.clock++ * 1000).toISOString();
  }

  private take(k: keyof MemoryBackend['failNext']) {
    const e = this.failNext[k];
    if (e) { delete this.failNext[k]; throw e; }
  }

  async ping() { this.take('ping'); }

  async findApiKeyByHash(hash: string) {
    for (const k of this.apiKeys.values()) if (k.key_hash === hash) return { id: k.id, name: k.name, role: k.role, revoked_at: k.revoked_at };
    return null;
  }

  async insertApiKey(name: string, hash: string, role: Role) {
    const row = { id: randomUUID(), name, role, revoked_at: null, key_hash: hash };
    this.apiKeys.set(row.id, row);
    return { id: row.id, name, role, revoked_at: null };
  }

  async getAppConfig() { return this.appConfig; }

  async upsertDevice(d: DeviceInput) {
    const ex = this.devices.get(d.install_id);
    const id = ex?.id ?? randomUUID();
    this.devices.set(d.install_id, { ...d, id, last_seen: this.now() });
    return id;
  }

  async upsertParticipant(p: ParticipantInput) {
    if (!/^P\d{3,5}$/.test(p.code)) throw new Error('check constraint participants_code_check');
    const ex = [...this.participants.values()].find((x) => x.code === p.code);
    if (ex) {
      ex.consent_version = p.consent_version;
      if (p.handedness != null) ex.handedness = p.handedness;
      if (p.age_range != null) ex.age_range = p.age_range;
      if (p.gender != null) ex.gender = p.gender;
      return ex.id;
    }
    const row: ParticipantRow = { ...p, id: randomUUID(), consent_at: this.now(), created_at: this.now() };
    this.participants.set(row.id, row);
    return row.id;
  }

  async findParticipantByCode(code: string) {
    const p = [...this.participants.values()].find((x) => x.code === code);
    return p ? { id: p.id } : null;
  }

  async findSessionByLocalId(localId: string) {
    return [...this.sessions.values()].find((s) => s.local_session_id === localId) ?? null;
  }

  async insertSession(s: NewSession) {
    if (await this.findSessionByLocalId(s.local_session_id)) return null;
    if (s.label_kind === 'human' && !s.participant_id) throw new Error('check constraint human_needs_participant');
    if (s.label_kind === 'bot' && (!s.bot_tool || !s.bot_host)) throw new Error('check constraint bot_needs_tool');
    const row: SessionRow = {
      ...s, id: randomUUID(), status: 'open', stop_reason: null, total_chunks: null, received_chunks: 0, stats: null,
      started_at: this.now(), ended_at: null,
    };
    this.sessions.set(row.id, row);
    return structuredClone(row);
  }

  async getSession(id: string) {
    const s = this.sessions.get(id);
    return s ? structuredClone(s) : null;
  }

  async updateSession(id: string, patch: Partial<SessionRow>) {
    const s = this.sessions.get(id);
    if (s) Object.assign(s, patch);
  }

  async listSessions(f: SessionFilter): Promise<SessionListItem[]> {
    const code = (pid: string | null) => (pid ? this.participants.get(pid)?.code ?? null : null);
    return [...this.sessions.values()]
      .map((s) => ({ ...structuredClone(s), participant_code: code(s.participant_id) }))
      .filter((s) =>
        (!f.label_kind || s.label_kind === f.label_kind) &&
        (!f.bot_tool || s.bot_tool === f.bot_tool) &&
        (!f.participant_code || s.participant_code === f.participant_code) &&
        (!f.status || s.status === f.status) &&
        (!f.from || s.started_at >= f.from) &&
        (!f.to || s.started_at < f.to) &&
        (!f.after || s.started_at < f.after.started_at || (s.started_at === f.after.started_at && s.id < f.after.id)))
      .sort((a, b) => (a.started_at === b.started_at ? (a.id < b.id ? 1 : -1) : a.started_at < b.started_at ? 1 : -1))
      .slice(0, f.limit);
  }

  async sessionIdsForParticipant(participantId: string) {
    return [...this.sessions.values()].filter((s) => s.participant_id === participantId).map((s) => s.id);
  }

  async getChunk(sessionId: string, seq: number) {
    return this.chunks.get(`${sessionId}/${seq}`) ?? null;
  }

  async insertChunk(c: NewChunk) {
    this.take('insertChunk');
    const k = `${c.session_id}/${c.seq}`;
    if (this.chunks.has(k)) return false;
    this.chunks.set(k, { ...c, received_at: this.now() });
    return true;
  }

  async incrementReceived(sessionId: string) {
    const s = this.sessions.get(sessionId);
    if (s) s.received_chunks++;
  }

  async listChunks(sessionId: string) {
    return [...this.chunks.values()].filter((c) => c.session_id === sessionId).sort((a, b) => a.seq - b.seq);
  }

  async chunkSeqs(sessionId: string) {
    return (await this.listChunks(sessionId)).map((c) => c.seq);
  }

  async putObject(path: string, body: Buffer) {
    this.take('putObject');
    if (this.objects.has(path)) return 'exists' as const;
    this.objects.set(path, Buffer.from(body));
    return 'created' as const;
  }

  async getObject(path: string) { return this.objects.get(path) ?? null; }

  async removeObjects(paths: string[]) {
    let n = 0;
    for (const p of paths) if (this.objects.delete(p)) n++;
    return n;
  }

  async listObjects(prefix: string) {
    return [...this.objects.keys()].filter((k) => k.startsWith(prefix));
  }

  async signedUrls(paths: string[], expiresInSec: number) {
    return new Map(paths.map((p) => [p, `memory://${p}?expires_in=${expiresInSec}`]));
  }

  async deleteParticipant(participantId: string) {
    this.participants.delete(participantId);
    for (const s of [...this.sessions.values()]) {
      if (s.participant_id !== participantId) continue;
      this.sessions.delete(s.id);
      for (const k of [...this.chunks.keys()]) if (k.startsWith(`${s.id}/`)) this.chunks.delete(k);
    }
  }

  async audit(action: string, detail: Record<string, unknown>) {
    this.auditLog.push({ at: this.now(), action, detail });
  }
}
