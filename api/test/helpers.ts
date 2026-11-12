import { gzipSync } from 'node:zlib';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/createApp.js';
import { MemoryBackend } from '../src/backend/memory.js';
import { sha256Hex } from '../src/util.js';

export const APP_KEY = 'test-app-key';
export const ADMIN_KEY = 'test-admin-key';
export const REVOKED_KEY = 'test-revoked-key';

export async function setup(over: Partial<{ RATE_LIMIT_PER_MIN: number; ADMIN_RATE_LIMIT_PER_MIN: number; MAX_CHUNK_BYTES: number }> = {}) {
  const backend = new MemoryBackend();
  await backend.insertApiKey('app', sha256Hex(APP_KEY), 'app');
  await backend.insertApiKey('admin', sha256Hex(ADMIN_KEY), 'admin');
  const revoked = await backend.insertApiKey('old', sha256Hex(REVOKED_KEY), 'app');
  backend.apiKeys.get(revoked.id)!.revoked_at = new Date().toISOString();
  const app = await buildApp({
    backend,
    logger: false,
    config: {
      MAX_CHUNK_BYTES: over.MAX_CHUNK_BYTES ?? 5 * 1024 * 1024,
      RATE_LIMIT_PER_MIN: over.RATE_LIMIT_PER_MIN ?? 10_000,
      ADMIN_RATE_LIMIT_PER_MIN: over.ADMIN_RATE_LIMIT_PER_MIN ?? 10_000,
      LOG_LEVEL: 'silent',
    },
  });
  return { app, backend };
}

export function sessionBody(over: Record<string, unknown> = {}) {
  return {
    local_session_id: randomUUID(),
    install_id: 'b1e2c3d4-0000-4000-8000-000000000001',
    participant: { code: 'P007', handedness: 'right', age_range: '25-34', gender: 'female' },
    consent_version: '2026-11-v1',
    label: { kind: 'human', bot_tool: null, bot_host: null },
    context: { placement: 'hand', posture: 'sitting', hands: 'one_right' },
    notes: null,
    device: { device: { manufacturer: 'samsung', model: 'SM-A546B', android_sdk: 34 }, integrity: { adb_enabled: false } },
    clock: { t0_elapsed_ns: 912345678901, t0_uptime_ns: 812345678901, t0_wall_ms: 1762934400000, sensor_clock: { acc: { basis: 'elapsed', offset_ns: 0 } } },
    sdk_version: '1.0.0',
    app_version: '1.0.0 (12)',
    config_version: 1,
    ...over,
  };
}

export const botLabel = { kind: 'bot', bot_tool: 'adb_input', bot_host: 'emulator_android_studio' };

export async function createSession(app: FastifyInstance, over: Record<string, unknown> = {}) {
  const res = await app.inject({ method: 'POST', url: '/v1/sessions', headers: { 'x-api-key': APP_KEY }, payload: sessionBody(over) });
  if (res.statusCode !== 201) throw new Error(`create failed ${res.statusCode} ${res.body}`);
  return res.json().session_id as string;
}

export function chunkBytes(seq: number, extra = '') {
  return gzipSync(Buffer.from(`{"k":"header","v":1,"seq":${seq}}\n{"k":"marker","t":1,"name":"x${extra}"}\n`));
}

export function putChunk(app: FastifyInstance, sessionId: string, seq: number, body = chunkBytes(seq), headers: Record<string, string> = {}) {
  return app.inject({
    method: 'PUT',
    url: `/v1/sessions/${sessionId}/chunks/${seq}`,
    headers: {
      'x-api-key': APP_KEY,
      'content-type': 'application/octet-stream',
      'x-chunk-sha256': sha256Hex(body),
      'x-chunk-t-start': String(seq * 5_000_000_000),
      'x-chunk-t-end': String((seq + 1) * 5_000_000_000),
      'x-chunk-streams': 'sensor,marker',
      'x-chunk-records': '2',
      ...headers,
    },
    payload: body,
  });
}

export function complete(app: FastifyInstance, sessionId: string, total: number, extra: Record<string, unknown> = {}) {
  return app.inject({
    method: 'POST',
    url: `/v1/sessions/${sessionId}/complete`,
    headers: { 'x-api-key': APP_KEY },
    payload: { total_chunks: total, ended_at_wall_ms: 1762935000000, stop_reason: 'user', stats: { duration_s: 10 }, ...extra },
  });
}
