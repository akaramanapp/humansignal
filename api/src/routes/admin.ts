import type { FastifyInstance } from 'fastify';
import type { Backend } from '../backend/types.js';
import type { KeyAuth } from '../auth.js';
import { ApiError, notFoundSession } from '../errors.js';
import { ListSessionsQuery, PARTICIPANT_CODE } from '../schemas.js';
import { isUuid } from '../util.js';

interface Deps { backend: Backend; auth: KeyAuth; rateLimitPerMin: number }

const SIGNED_URL_TTL_SEC = 3600;

const encodeCursor = (c: { started_at: string; id: string }) => Buffer.from(`${c.started_at}|${c.id}`).toString('base64url');
function decodeCursor(s: string) {
  const [started_at, id] = Buffer.from(s, 'base64url').toString().split('|');
  if (!started_at || !id || !isUuid(id) || Number.isNaN(Date.parse(started_at))) throw new ApiError(400, 'VALIDATION_ERROR', 'invalid cursor');
  return { started_at, id };
}

export async function adminRoutes(app: FastifyInstance, { backend, auth, rateLimitPerMin }: Deps) {
  const requireAdmin = auth.require('admin');
  const rl = { rateLimit: { max: rateLimitPerMin, timeWindow: '1 minute' } };

  // ---------------------------------------------------------------- GET /v1/admin/sessions
  app.get('/admin/sessions', { preHandler: requireAdmin, config: rl }, async (req) => {
    const q = ListSessionsQuery.parse(req.query);
    const rows = await backend.listSessions({
      label_kind: q.label_kind, bot_tool: q.bot_tool, participant_code: q.participant_code, status: q.status,
      from: q.from, to: q.to, limit: q.limit, after: q.cursor ? decodeCursor(q.cursor) : undefined,
    });
    const last = rows[rows.length - 1];
    return {
      sessions: rows.map((s) => ({
        session_id: s.id,
        local_session_id: s.local_session_id,
        participant_code: s.participant_code,
        label: { kind: s.label_kind, bot_tool: s.bot_tool, bot_host: s.bot_host },
        context: { placement: s.placement, posture: s.posture, hands: s.hands },
        status: s.status,
        stop_reason: s.stop_reason,
        total_chunks: s.total_chunks,
        received_chunks: s.received_chunks,
        sdk_version: s.sdk_version,
        app_version: s.app_version,
        started_at: s.started_at,
        ended_at: s.ended_at,
        stats: s.stats,
      })),
      next_cursor: rows.length === q.limit && last ? encodeCursor(last) : null,
    };
  });

  // ---------------------------------------------------------------- GET /v1/admin/sessions/:id/chunks
  app.get<{ Params: { sessionId: string } }>('/admin/sessions/:sessionId/chunks', { preHandler: requireAdmin, config: rl }, async (req) => {
    const { sessionId } = req.params;
    if (!isUuid(sessionId)) throw notFoundSession();
    const session = await backend.getSession(sessionId);
    if (!session) throw notFoundSession();
    const chunks = await backend.listChunks(sessionId);
    const urls = await backend.signedUrls(chunks.map((c) => c.storage_path), SIGNED_URL_TTL_SEC);
    return {
      session_id: sessionId,
      status: session.status,
      total_chunks: session.total_chunks,
      expires_in: SIGNED_URL_TTL_SEC,
      chunks: chunks.map((c) => ({
        seq: c.seq, size_bytes: c.size_bytes, sha256: c.sha256, t_start_ns: c.t_start_ns, t_end_ns: c.t_end_ns,
        streams: c.streams, records: c.records, received_at: c.received_at, url: urls.get(c.storage_path) ?? null,
      })),
    };
  });

  // ---------------------------------------------------------------- DELETE /v1/admin/participants/:code (KVKK)
  app.delete<{ Params: { code: string } }>('/admin/participants/:code', { preHandler: requireAdmin, config: rl }, async (req) => {
    const { code } = req.params;
    if (!PARTICIPANT_CODE.test(code)) throw new ApiError(400, 'VALIDATION_ERROR', 'participant code must match ^P\\d{3,5}$');
    const p = await backend.findParticipantByCode(code);
    if (!p) throw new ApiError(404, 'PARTICIPANT_NOT_FOUND', 'participant not found');

    const sessionIds = await backend.sessionIdsForParticipant(p.id);
    let deletedChunks = 0;
    // Önce Storage: DB satırı olmayan (yarım kalmış) nesneler de klasörden listelenip silinir.
    for (const sid of sessionIds) {
      const paths = new Set([
        ...(await backend.listChunks(sid)).map((c) => c.storage_path),
        ...(await backend.listObjects(`sessions/${sid}/`)),
      ]);
      deletedChunks += await backend.removeObjects([...paths]);
    }
    // Sonra DB: participants → sessions → chunks (cascade).
    await backend.deleteParticipant(p.id);
    // Kişisel veri yok: kod bile yazılmaz.
    await backend.audit('delete_participant', { deleted_sessions: sessionIds.length, deleted_chunks: deletedChunks });
    req.log.info({ deleted_sessions: sessionIds.length, deleted_chunks: deletedChunks }, 'participant deleted');
    return { deleted_sessions: sessionIds.length, deleted_chunks: deletedChunks };
  });
}
