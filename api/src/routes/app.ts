import type { FastifyInstance } from 'fastify';
import type { Backend, SessionRow } from '../backend/types.js';
import type { KeyAuth } from '../auth.js';
import { DEFAULT_APP_CONFIG } from '../defaultConfig.js';
import { ApiError, notFoundSession } from '../errors.js';
import { ChunkHeaders, CompleteSession, CreateSession } from '../schemas.js';
import { chunkPath, isUuid, missingSeqs, sha256Hex } from '../util.js';

interface Deps { backend: Backend; auth: KeyAuth; maxChunkBytes: number }

const str = (v: unknown) => (typeof v === 'string' ? v : null);
const int = (v: unknown) => (typeof v === 'number' && Number.isInteger(v) ? v : null);

export async function appRoutes(app: FastifyInstance, { backend, auth, maxChunkBytes }: Deps) {
  const requireApp = auth.require('app');

  // ---------------------------------------------------------------- GET /v1/config
  app.get('/config', { preHandler: requireApp }, async () => {
    const row = await backend.getAppConfig();
    if (!row) return DEFAULT_APP_CONFIG;
    return { ...row.config, config_version: row.config_version };
  });

  // ---------------------------------------------------------------- POST /v1/sessions
  app.post('/sessions', { preHandler: requireApp }, async (req, reply) => {
    const b = CreateSession.parse(req.body);

    // İdempotent: aynı local_session_id → mevcut oturum, 200.
    const existing = await backend.findSessionByLocalId(b.local_session_id);
    if (existing) return reply.status(200).send(sessionResponse(existing, maxChunkBytes));

    // 1. Cihaz (§3.7 anlık görüntüsünden özet alanlar)
    const dev = (b.device.device ?? b.device) as Record<string, unknown>;
    const deviceId = await backend.upsertDevice({
      install_id: b.install_id,
      manufacturer: str(dev.manufacturer),
      model: str(dev.model),
      android_sdk: int(dev.android_sdk),
      snapshot: b.device,
    });

    // 2. Katılımcı (bot oturumlarında olmayabilir)
    let participantId: string | null = null;
    if (b.participant?.code) {
      participantId = await backend.upsertParticipant({
        code: b.participant.code,
        handedness: b.participant.handedness ?? null,
        age_range: b.participant.age_range ?? null,
        gender: b.participant.gender ?? null,
        consent_version: b.consent_version,
      });
    }

    const row = await backend.insertSession({
      local_session_id: b.local_session_id,
      participant_id: participantId,
      device_id: deviceId,
      label_kind: b.label.kind,
      bot_tool: b.label.bot_tool ?? null,
      bot_host: b.label.bot_host ?? null,
      placement: b.context.placement,
      posture: b.context.posture,
      hands: b.context.hands,
      notes: b.notes ?? null,
      device_snapshot: b.device,
      clock: b.clock,
      sdk_version: b.sdk_version,
      app_version: b.app_version ?? null,
      config_version: b.config_version ?? null,
    });
    if (!row) {
      // Eşzamanlı tekrar: diğer istek kazandı.
      const again = await backend.findSessionByLocalId(b.local_session_id);
      if (!again) throw new ApiError(500, 'INTERNAL', 'session insert race');
      return reply.status(200).send(sessionResponse(again, maxChunkBytes));
    }
    return reply.status(201).send(sessionResponse(row, maxChunkBytes));
  });

  // ---------------------------------------------------------------- PUT /v1/sessions/:id/chunks/:seq
  app.put<{ Params: { sessionId: string; seq: string } }>(
    '/sessions/:sessionId/chunks/:seq',
    { preHandler: requireApp, bodyLimit: maxChunkBytes },
    async (req, reply) => {
      const { sessionId } = req.params;
      const seq = Number(req.params.seq);
      if (!/^\d+$/.test(req.params.seq) || !Number.isSafeInteger(seq) || seq > 2_147_483_647) {
        throw new ApiError(400, 'VALIDATION_ERROR', 'seq must be a non-negative integer');
      }
      const ct = String(req.headers['content-type'] ?? '').split(';')[0]?.trim();
      if (ct !== 'application/octet-stream') throw new ApiError(400, 'VALIDATION_ERROR', 'Content-Type must be application/octet-stream');
      if (req.headers['content-encoding']) throw new ApiError(400, 'VALIDATION_ERROR', 'Content-Encoding must not be used');
      const body = req.body as Buffer;
      if (!Buffer.isBuffer(body) || body.length === 0) throw new ApiError(400, 'VALIDATION_ERROR', 'empty body');
      const h = ChunkHeaders.parse(req.headers);

      // 1. Oturum
      if (!isUuid(sessionId)) throw notFoundSession();
      const session = await backend.getSession(sessionId);
      if (!session) throw notFoundSession();

      // 2. Checksum
      const sha = sha256Hex(body);
      if (sha !== h['x-chunk-sha256'].toLowerCase()) {
        throw new ApiError(400, 'BAD_CHECKSUM', 'X-Chunk-Sha256 does not match body', { expected: h['x-chunk-sha256'], actual: sha });
      }

      // 3. Var olan parça?
      const prior = await backend.getChunk(sessionId, seq);
      if (prior) {
        if (prior.sha256 === sha) return reply.status(200).send({ status: 'duplicate', seq });
        throw new ApiError(409, 'CHUNK_CONFLICT', `chunk ${seq} already exists with different content`);
      }
      if (session.status === 'complete') throw new ApiError(409, 'SESSION_CLOSED', 'session is complete');

      // 4. Storage (upsert: false). "Zaten var" → önceki denemede Storage başarılı, DB başarısız olmuş: devam.
      const path = chunkPath(sessionId, seq);
      await backend.putObject(path, body);

      // 5. DB satırı + sayaç
      const inserted = await backend.insertChunk({
        session_id: sessionId,
        seq,
        storage_path: path,
        size_bytes: body.length,
        sha256: sha,
        t_start_ns: h['x-chunk-t-start'],
        t_end_ns: h['x-chunk-t-end'],
        streams: h['x-chunk-streams'].split(',').map((s) => s.trim()).filter(Boolean),
        records: h['x-chunk-records'] ?? null,
      });
      if (!inserted) {
        // Eşzamanlı aynı seq: kazanan satıra göre karar ver.
        const winner = await backend.getChunk(sessionId, seq);
        if (winner?.sha256 === sha) return reply.status(200).send({ status: 'duplicate', seq });
        throw new ApiError(409, 'CHUNK_CONFLICT', `chunk ${seq} already exists with different content`);
      }
      await backend.incrementReceived(sessionId);

      // Eksikleri bekleyen 'incomplete' oturum: tüm parçalar geldiyse otomatik 'complete'.
      if (session.status === 'incomplete' && session.total_chunks != null) {
        const missing = missingSeqs(session.total_chunks, await backend.chunkSeqs(sessionId));
        if (missing.length === 0) await backend.updateSession(sessionId, { status: 'complete' });
      }
      return reply.status(201).send({ status: 'stored', seq });
    },
  );

  // ---------------------------------------------------------------- POST /v1/sessions/:id/complete
  app.post<{ Params: { sessionId: string } }>('/sessions/:sessionId/complete', { preHandler: requireApp }, async (req) => {
    const { sessionId } = req.params;
    const b = CompleteSession.parse(req.body ?? {});
    if (!isUuid(sessionId)) throw notFoundSession();
    const session = await backend.getSession(sessionId);
    if (!session) throw notFoundSession();

    const seqs = await backend.chunkSeqs(sessionId);
    const missing = missingSeqs(b.total_chunks, seqs);
    const status = missing.length === 0 ? 'complete' : 'incomplete';
    const patch: Partial<SessionRow> = {
      status,
      total_chunks: b.total_chunks,
      received_chunks: seqs.length, // sayaç gerçek satır sayısıyla düzeltilir
      ended_at: b.ended_at_wall_ms != null ? new Date(b.ended_at_wall_ms).toISOString() : session.ended_at ?? new Date().toISOString(),
    };
    if (b.stop_reason != null) patch.stop_reason = b.stop_reason;
    if (b.stats != null) patch.stats = b.stats;
    if (b.clock != null) patch.clock = { ...(session.clock as object), ...b.clock };
    await backend.updateSession(sessionId, patch);
    return status === 'complete' ? { status, missing: [] } : { status, missing };
  });

  // ---------------------------------------------------------------- POST /v1/score (Faz 2)
  app.post('/score', { preHandler: requireApp }, async (_req, reply) =>
    reply.status(501).send({ error: { code: 'NOT_IMPLEMENTED', message: 'scoring is planned for phase 2', details: {} } }),
  );
}

function sessionResponse(s: SessionRow, maxChunkBytes: number) {
  return { session_id: s.id, status: s.status, max_chunk_bytes: maxChunkBytes };
}
