/**
 * Vercel girişi. Vercel, Fastify projelerinde giriş dosyasını sırayla arar (app, index, server, src/app, src/index,
 * src/server) ve **default export** olarak bir istek işleyici ya da sunucu bekler. Bu dosya o işleyicidir.
 * Yerel/sunucu çalıştırma için `src/server.ts` (listen) kullanılır.
 *
 * Fastify örneği soğuk başlangıçta bir kez kurulur ve sıcak örnekte yeniden kullanılır.
 */
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { FastifyInstance } from 'fastify';
import { buildApp } from './createApp.js';
import { createBackend } from './backend/index.js';
import { loadConfig } from './config.js';

let ready: Promise<FastifyInstance> | null = null;

function getApp(): Promise<FastifyInstance> {
  ready ??= (async () => {
    const config = loadConfig();
    const backend = await createBackend(config);
    const app = await buildApp({ config, backend });
    await app.ready();
    return app;
  })().catch((e) => {
    ready = null; // ör. eksik ortam değişkeni: sonraki istekte yeniden dene
    throw e;
  });
  return ready;
}

export default async function handler(req: IncomingMessage, res: ServerResponse) {
  try {
    const app = await getApp();
    app.server.emit('request', req, res);
  } catch (e) {
    // Yapılandırma hatası (SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY eksik vb.): ayrıntı loglanır, istemciye sızdırılmaz.
    console.error('startup failed:', e instanceof Error ? e.message : e);
    res.statusCode = 500;
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ error: { code: 'INTERNAL', message: 'server misconfigured', details: {} } }));
  }
}
