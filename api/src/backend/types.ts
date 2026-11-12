/**
 * Veri katmanı soyutlaması. Üretimde Supabase (Postgres + Storage), testlerde ve yerel geliştirmede bellek içi.
 * Satır şekilleri §7 şemasıyla birebir aynıdır.
 */

export type Role = 'app' | 'admin';
export type SessionStatus = 'open' | 'incomplete' | 'complete';

export interface ApiKeyRow {
  id: string;
  name: string;
  role: Role;
  revoked_at: string | null;
}

export interface ParticipantInput {
  code: string;
  handedness?: string | null;
  age_range?: string | null;
  gender?: string | null;
  consent_version: string;
}

export interface DeviceInput {
  install_id: string;
  manufacturer: string | null;
  model: string | null;
  android_sdk: number | null;
  snapshot: unknown;
}

export interface SessionRow {
  id: string;
  local_session_id: string;
  participant_id: string | null;
  device_id: string;
  label_kind: 'human' | 'bot';
  bot_tool: string | null;
  bot_host: string | null;
  placement: string;
  posture: string;
  hands: string;
  notes: string | null;
  device_snapshot: unknown;
  clock: unknown;
  sdk_version: string;
  app_version: string | null;
  config_version: number | null;
  status: SessionStatus;
  stop_reason: string | null;
  total_chunks: number | null;
  received_chunks: number;
  stats: unknown;
  started_at: string;
  ended_at: string | null;
}

export type NewSession = Omit<SessionRow, 'id' | 'status' | 'stop_reason' | 'total_chunks' | 'received_chunks' | 'stats' | 'started_at' | 'ended_at'>;

export interface ChunkRow {
  session_id: string;
  seq: number;
  storage_path: string;
  size_bytes: number;
  sha256: string;
  t_start_ns: number;
  t_end_ns: number;
  streams: string[];
  records: number | null;
  received_at: string;
}

export type NewChunk = Omit<ChunkRow, 'received_at'>;

export interface SessionFilter {
  label_kind?: string;
  bot_tool?: string;
  participant_code?: string;
  status?: string;
  from?: string;
  to?: string;
  limit: number;
  /** Keyset: bir önceki sayfanın son satırı (started_at, id). */
  after?: { started_at: string; id: string };
}

export interface SessionListItem extends SessionRow {
  participant_code: string | null;
}

export interface Backend {
  ping(): Promise<void>;

  findApiKeyByHash(hash: string): Promise<ApiKeyRow | null>;
  insertApiKey(name: string, hash: string, role: Role): Promise<ApiKeyRow>;

  getAppConfig(): Promise<{ config_version: number; config: Record<string, unknown> } | null>;

  upsertDevice(d: DeviceInput): Promise<string>;
  /** Yoksa oluşturur; varsa consent_version'ı (ve verilmişse diğer alanları) günceller. */
  upsertParticipant(p: ParticipantInput): Promise<string>;
  findParticipantByCode(code: string): Promise<{ id: string } | null>;

  findSessionByLocalId(localId: string): Promise<SessionRow | null>;
  /** `local_session_id` benzersizlik çakışmasında `null` döner (eşzamanlı tekrar). */
  insertSession(s: NewSession): Promise<SessionRow | null>;
  getSession(id: string): Promise<SessionRow | null>;
  updateSession(id: string, patch: Partial<SessionRow>): Promise<void>;
  listSessions(f: SessionFilter): Promise<SessionListItem[]>;
  sessionIdsForParticipant(participantId: string): Promise<string[]>;

  getChunk(sessionId: string, seq: number): Promise<ChunkRow | null>;
  /** PK çakışmasında `false` döner. */
  insertChunk(c: NewChunk): Promise<boolean>;
  incrementReceived(sessionId: string): Promise<void>;
  listChunks(sessionId: string): Promise<ChunkRow[]>;
  chunkSeqs(sessionId: string): Promise<number[]>;

  /** `upsert: false`. Nesne zaten varsa `'exists'` döner. */
  putObject(path: string, body: Buffer): Promise<'created' | 'exists'>;
  getObject(path: string): Promise<Buffer | null>;
  removeObjects(paths: string[]): Promise<number>;
  /** `prefix` altındaki tüm nesne yolları (DB'de satırı olmayanlar dahil). */
  listObjects(prefix: string): Promise<string[]>;
  signedUrls(paths: string[], expiresInSec: number): Promise<Map<string, string>>;

  /** Katılımcıyı siler; oturumlar ve parçalar FK cascade ile silinir. */
  deleteParticipant(participantId: string): Promise<void>;
  audit(action: string, detail: Record<string, unknown>): Promise<void>;
}
