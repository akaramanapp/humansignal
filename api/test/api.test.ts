import { describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import { buildApp } from '../src/app.js';
import { KeyAuth } from '../src/auth.js';
import { MemoryBackend } from '../src/backend/memory.js';
import { sha256Hex } from '../src/util.js';
import {
  ADMIN_KEY, APP_KEY, REVOKED_KEY, botLabel, chunkBytes, complete, createSession, putChunk, sessionBody, setup,
} from './helpers.js';

describe('health', () => {
  it('returns ok and version', async () => {
    const { app } = await setup();
    const r = await app.inject('/health');
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({ ok: true, version: '1.0.0' });
  });

  it('returns 503 when the database is unreachable', async () => {
    const { app, backend } = await setup();
    backend.failNext.ping = new Error('down');
    expect((await app.inject('/health')).statusCode).toBe(503);
  });
});

describe('auth (§6.3)', () => {
  it('rejects missing, unknown and revoked keys with 401', async () => {
    const { app } = await setup();
    for (const key of [undefined, 'nope', REVOKED_KEY]) {
      const r = await app.inject({ url: '/v1/config', headers: key ? { 'x-api-key': key } : {} });
      expect(r.statusCode).toBe(401);
      expect(r.json().error.code).toBe('UNAUTHORIZED');
    }
  });

  it('rejects app keys on admin routes with 403', async () => {
    const { app } = await setup();
    const r = await app.inject({ url: '/v1/admin/sessions', headers: { 'x-api-key': APP_KEY } });
    expect(r.statusCode).toBe(403);
    expect(r.json().error.code).toBe('FORBIDDEN');
  });

  it('caches validated keys for 60 s', async () => {
    const backend = new MemoryBackend();
    await backend.insertApiKey('a', sha256Hex('k'), 'app');
    let now = 0;
    let lookups = 0;
    const orig = backend.findApiKeyByHash.bind(backend);
    backend.findApiKeyByHash = async (h) => { lookups++; return orig(h); };
    const auth = new KeyAuth(backend, 60_000, () => now);
    await auth.resolve('k'); await auth.resolve('k');
    expect(lookups).toBe(1);
    now = 61_000;
    await auth.resolve('k');
    expect(lookups).toBe(2);
  });
});

describe('GET /v1/config', () => {
  it('returns the app_config row', async () => {
    const { app } = await setup();
    const r = await app.inject({ url: '/v1/config', headers: { 'x-api-key': APP_KEY } });
    expect(r.statusCode).toBe(200);
    const c = r.json();
    expect(c.config_version).toBe(1);
    expect(c.chunk_seconds).toBe(5);
    expect(c.sensors.acc).toEqual({ enabled: true, rate_hz: 100 });
    expect(c.upload).toEqual({ wifi_only: false, max_local_mb: 200 });
  });
});

describe('POST /v1/sessions', () => {
  it('creates a human session, device and participant', async () => {
    const { app, backend } = await setup();
    const body = sessionBody();
    const r = await app.inject({ method: 'POST', url: '/v1/sessions', headers: { 'x-api-key': APP_KEY }, payload: body });
    expect(r.statusCode).toBe(201);
    const j = r.json();
    expect(j).toEqual({ session_id: expect.any(String), status: 'open', max_chunk_bytes: 5242880 });
    const s = backend.sessions.get(j.session_id)!;
    expect(s.label_kind).toBe('human');
    expect(s.placement).toBe('hand');
    expect((s.clock as any).t0_elapsed_ns).toBe(912345678901);
    const d = backend.devices.get(body.install_id)!;
    expect(d.manufacturer).toBe('samsung');
    expect(d.android_sdk).toBe(34);
    const p = [...backend.participants.values()][0]!;
    expect(p).toMatchObject({ code: 'P007', handedness: 'right', age_range: '25-34', consent_version: '2026-11-v1' });
  });

  it('is idempotent on local_session_id (200, same id)', async () => {
    const { app, backend } = await setup();
    const body = sessionBody();
    const a = await app.inject({ method: 'POST', url: '/v1/sessions', headers: { 'x-api-key': APP_KEY }, payload: body });
    const b = await app.inject({ method: 'POST', url: '/v1/sessions', headers: { 'x-api-key': APP_KEY }, payload: body });
    expect(a.statusCode).toBe(201);
    expect(b.statusCode).toBe(200);
    expect(b.json().session_id).toBe(a.json().session_id);
    expect(backend.sessions.size).toBe(1);
  });

  it('updates consent_version for an existing participant, keeping other fields', async () => {
    const { app, backend } = await setup();
    await createSession(app);
    await createSession(app, { participant: { code: 'P007' }, consent_version: '2026-12-v2' });
    expect(backend.participants.size).toBe(1);
    expect([...backend.participants.values()][0]).toMatchObject({ consent_version: '2026-12-v2', handedness: 'right' });
  });

  it('accepts bot sessions without a participant', async () => {
    const { app, backend } = await setup();
    const id = await createSession(app, { participant: null, label: botLabel });
    expect(backend.sessions.get(id)).toMatchObject({ participant_id: null, bot_tool: 'adb_input', bot_host: 'emulator_android_studio' });
  });

  it.each([
    [{ label: { kind: 'bot', bot_tool: null, bot_host: 'real_device' }, participant: null }, 'label.bot_tool is required when label.kind = bot'],
    [{ label: { kind: 'bot', bot_tool: 'appium', bot_host: null }, participant: null }, 'label.bot_host is required when label.kind = bot'],
    [{ participant: null }, 'participant.code is required when label.kind = human'],
    [{ label: { kind: 'human', bot_tool: 'appium', bot_host: null } }, 'label.bot_tool and label.bot_host must be null when label.kind = human'],
    [{ participant: { code: 'X12' } }, 'participant.code'],
    [{ context: { placement: 'pocket', posture: 'sitting', hands: 'two' } }, 'context.placement'],
    [{ label: { kind: 'bot', bot_tool: 'selenium', bot_host: 'real_device' }, participant: null }, 'label.bot_tool'],
  ])('rejects invalid body %#', async (over, msg) => {
    const { app, backend } = await setup();
    const r = await app.inject({ method: 'POST', url: '/v1/sessions', headers: { 'x-api-key': APP_KEY }, payload: sessionBody(over) });
    expect(r.statusCode).toBe(400);
    expect(r.json().error.code).toBe('VALIDATION_ERROR');
    expect(r.json().error.message).toContain(msg);
    expect(backend.sessions.size).toBe(0);
  });

  it('rejects JSON bodies above 256 KB with 413', async () => {
    const { app } = await setup();
    const r = await app.inject({
      method: 'POST', url: '/v1/sessions', headers: { 'x-api-key': APP_KEY },
      payload: sessionBody({ device: { blob: 'x'.repeat(300 * 1024) } }),
    });
    expect(r.statusCode).toBe(413);
    expect(r.json().error.code).toBe('PAYLOAD_TOO_LARGE');
  });
});

describe('PUT /v1/sessions/:id/chunks/:seq', () => {
  it('stores the gzip bytes untouched at the spec path', async () => {
    const { app, backend } = await setup();
    const id = await createSession(app);
    const body = chunkBytes(17);
    const r = await putChunk(app, id, 17, body);
    expect(r.statusCode).toBe(201);
    expect(r.json()).toEqual({ status: 'stored', seq: 17 });
    expect(backend.objects.get(`sessions/${id}/000017.ndjson.gz`)).toEqual(body);
    const row = backend.chunks.get(`${id}/17`)!;
    expect(row).toMatchObject({ size_bytes: body.length, sha256: sha256Hex(body), t_start_ns: 85_000_000_000, streams: ['sensor', 'marker'], records: 2 });
    expect(backend.sessions.get(id)!.received_chunks).toBe(1);
  });

  it('A4: re-uploading the same chunk returns duplicate and does not double count', async () => {
    const { app, backend } = await setup();
    const id = await createSession(app);
    await putChunk(app, id, 0);
    const r = await putChunk(app, id, 0);
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({ status: 'duplicate', seq: 0 });
    expect(backend.sessions.get(id)!.received_chunks).toBe(1);
    expect(backend.chunks.size).toBe(1);
  });

  it('returns 409 CHUNK_CONFLICT for the same seq with different content', async () => {
    const { app } = await setup();
    const id = await createSession(app);
    await putChunk(app, id, 3);
    const r = await putChunk(app, id, 3, chunkBytes(3, 'other'));
    expect(r.statusCode).toBe(409);
    expect(r.json().error.code).toBe('CHUNK_CONFLICT');
  });

  it('returns 400 BAD_CHECKSUM when the header does not match', async () => {
    const { app, backend } = await setup();
    const id = await createSession(app);
    const r = await putChunk(app, id, 0, chunkBytes(0), { 'x-chunk-sha256': 'a'.repeat(64) });
    expect(r.statusCode).toBe(400);
    expect(r.json().error.code).toBe('BAD_CHECKSUM');
    expect(backend.objects.size).toBe(0);
  });

  it('returns 404 for unknown sessions', async () => {
    const { app } = await setup();
    for (const id of [randomUUID(), 'not-a-uuid']) {
      const r = await putChunk(app, id, 0);
      expect(r.statusCode).toBe(404);
      expect(r.json().error.code).toBe('SESSION_NOT_FOUND');
    }
  });

  it('validates headers, seq, content type and content encoding', async () => {
    const { app } = await setup();
    const id = await createSession(app);
    expect((await putChunk(app, id, 0, chunkBytes(0), { 'x-chunk-streams': '' })).statusCode).toBe(400);
    expect((await putChunk(app, id, 0, chunkBytes(0), { 'x-chunk-t-start': 'abc' })).statusCode).toBe(400);
    expect((await putChunk(app, id, 0, chunkBytes(0), { 'content-encoding': 'gzip' })).statusCode).toBe(400);
    expect((await putChunk(app, id, 0, chunkBytes(0), { 'content-type': 'application/json' })).statusCode).toBe(400);
    const neg = await app.inject({ method: 'PUT', url: `/v1/sessions/${id}/chunks/-1`, headers: { 'x-api-key': APP_KEY, 'content-type': 'application/octet-stream' }, payload: Buffer.from('x') });
    expect(neg.statusCode).toBe(400);
  });

  it('returns 413 PAYLOAD_TOO_LARGE above MAX_CHUNK_BYTES', async () => {
    const { app } = await setup({ MAX_CHUNK_BYTES: 1024 });
    const id = await createSession(app);
    const big = Buffer.alloc(4096, 1);
    const r = await putChunk(app, id, 0, big);
    expect(r.statusCode).toBe(413);
    expect(r.json().error.code).toBe('PAYLOAD_TOO_LARGE');
  });

  it('recovers when Storage succeeded but the DB insert failed (§6.4 step 5)', async () => {
    const { app, backend } = await setup();
    const id = await createSession(app);
    backend.failNext.insertChunk = new Error('db timeout');
    const first = await putChunk(app, id, 0);
    expect(first.statusCode).toBe(500);
    expect(first.json().error.code).toBe('INTERNAL');
    expect(backend.objects.size).toBe(1);
    expect(backend.chunks.size).toBe(0);
    const retry = await putChunk(app, id, 0);
    expect(retry.statusCode).toBe(201);
    expect(backend.chunks.size).toBe(1);
    expect(backend.sessions.get(id)!.received_chunks).toBe(1);
  });

  it('returns 409 SESSION_CLOSED for new seqs after completion, but still accepts duplicates', async () => {
    const { app } = await setup();
    const id = await createSession(app);
    await putChunk(app, id, 0);
    expect((await complete(app, id, 1)).json().status).toBe('complete');
    const r = await putChunk(app, id, 1);
    expect(r.statusCode).toBe(409);
    expect(r.json().error.code).toBe('SESSION_CLOSED');
    expect((await putChunk(app, id, 0)).statusCode).toBe(200);
  });
});

describe('POST /v1/sessions/:id/complete', () => {
  it('reports missing seqs, then auto-completes when they arrive', async () => {
    const { app, backend } = await setup();
    const id = await createSession(app);
    for (const s of [0, 1, 3, 5]) await putChunk(app, id, s);
    const r = await complete(app, id, 6, { stats: { duration_s: 30, dropped_chunks: 0 }, clock: { sensor_clock: { gyr: { basis: 'elapsed', offset_ns: 0 } } } });
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({ status: 'incomplete', missing: [2, 4] });
    const s = backend.sessions.get(id)!;
    expect(s).toMatchObject({ status: 'incomplete', total_chunks: 6, stop_reason: 'user', ended_at: '2025-11-12T08:10:00.000Z' });
    expect((s.clock as any).t0_elapsed_ns).toBe(912345678901);
    expect((s.clock as any).sensor_clock.gyr.basis).toBe('elapsed');

    await putChunk(app, id, 2);
    expect(backend.sessions.get(id)!.status).toBe('incomplete');
    await putChunk(app, id, 4);
    expect(backend.sessions.get(id)!.status).toBe('complete');
    expect(backend.sessions.get(id)!.received_chunks).toBe(6);
  });

  it('is idempotent', async () => {
    const { app } = await setup();
    const id = await createSession(app);
    await putChunk(app, id, 0); await putChunk(app, id, 1);
    expect((await complete(app, id, 2)).json()).toEqual({ status: 'complete', missing: [] });
    expect((await complete(app, id, 2)).json()).toEqual({ status: 'complete', missing: [] });
  });

  it('accepts an empty session and validates the body', async () => {
    const { app } = await setup();
    const id = await createSession(app);
    expect((await complete(app, id, 0)).json()).toEqual({ status: 'complete', missing: [] });
    const bad = await app.inject({ method: 'POST', url: `/v1/sessions/${id}/complete`, headers: { 'x-api-key': APP_KEY }, payload: { total_chunks: -1 } });
    expect(bad.statusCode).toBe(400);
    expect((await complete(app, randomUUID(), 1)).statusCode).toBe(404);
  });
});

describe('admin', () => {
  async function seed() {
    const ctx = await setup();
    const a = await createSession(ctx.app); // P007 human
    const b = await createSession(ctx.app, { participant: null, label: botLabel });
    const c = await createSession(ctx.app, { participant: { code: 'P008' } });
    return { ...ctx, ids: { a, b, c } };
  }
  const admin = { 'x-api-key': ADMIN_KEY };

  it('lists sessions newest first with filters', async () => {
    const { app, ids } = await seed();
    const all = (await app.inject({ url: '/v1/admin/sessions', headers: admin })).json();
    expect(all.sessions.map((s: any) => s.session_id)).toEqual([ids.c, ids.b, ids.a]);
    expect(all.next_cursor).toBeNull();
    const bots = (await app.inject({ url: '/v1/admin/sessions?label_kind=bot', headers: admin })).json();
    expect(bots.sessions.map((s: any) => s.session_id)).toEqual([ids.b]);
    expect(bots.sessions[0].label).toEqual({ kind: 'bot', bot_tool: 'adb_input', bot_host: 'emulator_android_studio' });
    const p8 = (await app.inject({ url: '/v1/admin/sessions?participant_code=P008', headers: admin })).json();
    expect(p8.sessions.map((s: any) => s.participant_code)).toEqual(['P008']);
    expect((await app.inject({ url: '/v1/admin/sessions?limit=501', headers: admin })).statusCode).toBe(400);
  });

  it('paginates with a cursor', async () => {
    const { app, ids } = await seed();
    const p1 = (await app.inject({ url: '/v1/admin/sessions?limit=2', headers: admin })).json();
    expect(p1.sessions.map((s: any) => s.session_id)).toEqual([ids.c, ids.b]);
    const p2 = (await app.inject({ url: `/v1/admin/sessions?limit=2&cursor=${p1.next_cursor}`, headers: admin })).json();
    expect(p2.sessions.map((s: any) => s.session_id)).toEqual([ids.a]);
    expect(p2.next_cursor).toBeNull();
    expect((await app.inject({ url: '/v1/admin/sessions?cursor=garbage', headers: admin })).statusCode).toBe(400);
  });

  it('lists chunks with signed URLs valid for one hour', async () => {
    const { app, ids } = await seed();
    await putChunk(app, ids.a, 0); await putChunk(app, ids.a, 1);
    const r = (await app.inject({ url: `/v1/admin/sessions/${ids.a}/chunks`, headers: admin })).json();
    expect(r.expires_in).toBe(3600);
    expect(r.chunks.map((c: any) => c.seq)).toEqual([0, 1]);
    expect(r.chunks[0].url).toContain(`sessions/${ids.a}/000000.ndjson.gz`);
  });

  it('A10: deleting a participant removes all their storage objects and rows, and audits without personal data', async () => {
    const { app, backend, ids } = await seed();
    const second = await createSession(app, { participant: { code: 'P007' } });
    for (const s of [0, 1, 2]) await putChunk(app, ids.a, s);
    await putChunk(app, second, 0);
    await putChunk(app, ids.c, 0); // başka katılımcı
    // DB satırı olmayan yetim nesne (yarım kalmış yükleme):
    backend.objects.set(`sessions/${second}/000009.ndjson.gz`, Buffer.from('x'));

    const r = await app.inject({ method: 'DELETE', url: '/v1/admin/participants/P007', headers: admin });
    expect(r.statusCode).toBe(200);
    expect(r.json()).toEqual({ deleted_sessions: 2, deleted_chunks: 5 });

    expect([...backend.objects.keys()].filter((k) => k.includes(ids.a) || k.includes(second))).toEqual([]);
    expect(backend.objects.has(`sessions/${ids.c}/000000.ndjson.gz`)).toBe(true);
    expect(backend.sessions.has(ids.a)).toBe(false);
    expect(backend.sessions.has(second)).toBe(false);
    expect(backend.sessions.has(ids.c)).toBe(true);
    expect([...backend.chunks.keys()].every((k) => k.startsWith(ids.c))).toBe(true);
    expect(await backend.findParticipantByCode('P007')).toBeNull();
    expect(backend.auditLog).toEqual([{ at: expect.any(String), action: 'delete_participant', detail: { deleted_sessions: 2, deleted_chunks: 5 } }]);
    expect(JSON.stringify(backend.auditLog)).not.toContain('P007');

    expect((await app.inject({ method: 'DELETE', url: '/v1/admin/participants/P007', headers: admin })).statusCode).toBe(404);
  });
});

describe('misc', () => {
  it('POST /v1/score is a 501 stub', async () => {
    const { app } = await setup();
    const r = await app.inject({ method: 'POST', url: '/v1/score', headers: { 'x-api-key': APP_KEY }, payload: {} });
    expect(r.statusCode).toBe(501);
    expect(r.json().error.code).toBe('NOT_IMPLEMENTED');
  });

  it('rate limits per key (app) and more strictly on admin routes', async () => {
    const { app } = await setup({ RATE_LIMIT_PER_MIN: 3, ADMIN_RATE_LIMIT_PER_MIN: 1 });
    const codes = [];
    for (let i = 0; i < 4; i++) codes.push((await app.inject({ url: '/v1/config', headers: { 'x-api-key': APP_KEY } })).statusCode);
    expect(codes).toEqual([200, 200, 200, 429]);
    const limited = await app.inject({ url: '/v1/config', headers: { 'x-api-key': APP_KEY } });
    expect(limited.json().error.code).toBe('RATE_LIMITED');
    // Başka anahtar etkilenmez
    expect((await app.inject({ url: '/v1/config', headers: { 'x-api-key': ADMIN_KEY } })).statusCode).toBe(200);
    const a1 = await app.inject({ url: '/v1/admin/sessions', headers: { 'x-api-key': ADMIN_KEY } });
    const a2 = await app.inject({ url: '/v1/admin/sessions', headers: { 'x-api-key': ADMIN_KEY } });
    expect([a1.statusCode, a2.statusCode]).toEqual([200, 429]);
  });

  it('does not log IP addresses or API keys', async () => {
    const backend = new MemoryBackend();
    await backend.insertApiKey('app', sha256Hex(APP_KEY), 'app');
    const app = await buildApp({ backend, config: { MAX_CHUNK_BYTES: 1e6, RATE_LIMIT_PER_MIN: 100, ADMIN_RATE_LIMIT_PER_MIN: 100, LOG_LEVEL: 'info' } });
    // Fastify'ın pino örneğindeki istek serializer'ı doğrudan sınanır.
    const ser = (app.log as any)[Symbol.for('pino.serializers')]?.req;
    expect(ser).toBeTypeOf('function');
    const out = ser({ method: 'GET', url: '/v1/config', id: 'req-1', ip: '203.0.113.9', headers: { 'x-api-key': APP_KEY }, socket: { remoteAddress: '203.0.113.9' } });
    expect(out).toEqual({ method: 'GET', url: '/v1/config', id: 'req-1' });
    expect(JSON.stringify(out)).not.toContain('203.0.113.9');
    expect(JSON.stringify(out)).not.toContain(APP_KEY);
  });

  it('returns errors in the §6.3 format for unknown routes', async () => {
    const { app } = await setup();
    const r = await app.inject('/v1/nope');
    expect(r.statusCode).toBe(404);
    expect(r.json()).toEqual({ error: { code: 'NOT_FOUND', message: 'route not found', details: {} } });
  });
});
