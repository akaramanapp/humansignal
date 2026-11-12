import Fastify, { type FastifyInstance, type FastifyError } from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { ZodError } from 'zod';
import type { Backend } from './backend/types.js';
import { API_VERSION, type Config } from './config.js';
import { ApiError } from './errors.js';
import { KeyAuth } from './auth.js';
import { sha256Hex } from './util.js';
import { zodDetails } from './schemas.js';
import { appRoutes } from './routes/app.js';
import { adminRoutes } from './routes/admin.js';

export interface AppDeps {
  config: Pick<Config, 'MAX_CHUNK_BYTES' | 'RATE_LIMIT_PER_MIN' | 'ADMIN_RATE_LIMIT_PER_MIN' | 'LOG_LEVEL'>;
  backend: Backend;
  /** Testlerde logger kapatılabilir. */
  logger?: boolean;
}

const JSON_BODY_LIMIT = 256 * 1024; // §6.3

export async function buildApp({ config, backend, logger = true }: AppDeps): Promise<FastifyInstance> {
  const app = Fastify({
    bodyLimit: JSON_BODY_LIMIT,
    logger: logger
      ? {
          level: config.LOG_LEVEL,
          // IP adresleri ve başlıklar (API anahtarı) loglanmaz (§6.1, §3.12).
          serializers: {
            req: (req) => ({ method: req.method, url: req.url, id: req.id }),
          },
        }
      : false,
    trustProxy: false,
  });

  const auth = new KeyAuth(backend);

  // Parça gövdesi: ham gzip baytları, açılmadan saklanır (§6.4).
  app.addContentTypeParser('application/octet-stream', { parseAs: 'buffer', bodyLimit: config.MAX_CHUNK_BYTES }, (_req, body, done) =>
    done(null, body),
  );

  await app.register(rateLimit, {
    global: true,
    max: config.RATE_LIMIT_PER_MIN,
    timeWindow: '1 minute',
    // Anahtar başına (IP değil). Anahtarın kendisi bellekte bile tutulmaz; özetinin ilk 16 karakteri kullanılır.
    keyGenerator: (req) => {
      const k = req.headers['x-api-key'];
      return typeof k === 'string' && k ? `k:${sha256Hex(k).slice(0, 16)}` : 'anon';
    },
    allowList: (req) => req.url === '/health',
    errorResponseBuilder: (_req, ctx) => {
      const e = new ApiError(429, 'RATE_LIMITED', `rate limit exceeded, retry in ${ctx.after}`);
      return Object.assign(e, { statusCode: 429 });
    },
  });

  app.setErrorHandler((err: FastifyError | ApiError | ZodError | Error, req, reply) => {
    if (err instanceof ApiError) return reply.status(err.statusCode).send(err.toBody());
    if (err instanceof ZodError) {
      const { message, details } = zodDetails(err);
      return reply.status(400).send(new ApiError(400, 'VALIDATION_ERROR', message, details).toBody());
    }
    const fe = err as FastifyError;
    if (fe.code === 'FST_ERR_CTP_BODY_TOO_LARGE' || fe.statusCode === 413) {
      return reply.status(413).send(new ApiError(413, 'PAYLOAD_TOO_LARGE', 'payload too large').toBody());
    }
    if (fe.code === 'FST_ERR_CTP_INVALID_MEDIA_TYPE' || fe.statusCode === 415) {
      return reply.status(400).send(new ApiError(400, 'VALIDATION_ERROR', 'unsupported content type').toBody());
    }
    if (fe.statusCode && fe.statusCode >= 400 && fe.statusCode < 500) {
      return reply.status(400).send(new ApiError(400, 'VALIDATION_ERROR', fe.message).toBody());
    }
    req.log.error({ err }, 'unhandled error');
    return reply.status(500).send(new ApiError(500, 'INTERNAL', 'internal error').toBody());
  });

  app.setNotFoundHandler((_req, reply) => reply.status(404).send(new ApiError(404, 'NOT_FOUND', 'route not found').toBody()));

  app.get('/health', async (_req, reply) => {
    try {
      await backend.ping();
      return { ok: true, version: API_VERSION };
    } catch {
      return reply.status(503).send({ ok: false, version: API_VERSION });
    }
  });

  await app.register(async (v1) => {
    await appRoutes(v1, { backend, auth, maxChunkBytes: config.MAX_CHUNK_BYTES });
    await adminRoutes(v1, { backend, auth, rateLimitPerMin: config.ADMIN_RATE_LIMIT_PER_MIN });
  }, { prefix: '/v1' });

  return app;
}
