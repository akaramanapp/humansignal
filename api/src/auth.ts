import type { FastifyReply, FastifyRequest } from 'fastify';
import type { ApiKeyRow, Backend, Role } from './backend/types.js';
import { ApiError } from './errors.js';
import { sha256Hex } from './util.js';

declare module 'fastify' {
  interface FastifyRequest {
    apiKey?: ApiKeyRow;
  }
}

/**
 * §6.3: `X-Api-Key` başlığının SHA-256 özeti `api_keys` tablosunda aranır. Doğrulanmış anahtarlar 60 sn önbelleğe
 * alınır (bulunamayan/iptal edilmiş anahtarlar da, kaba kuvvette DB'yi yormamak için kısa süre önbelleklenir).
 */
export class KeyAuth {
  private cache = new Map<string, { row: ApiKeyRow | null; until: number }>();

  constructor(private backend: Backend, private ttlMs = 60_000, private now = () => Date.now()) {}

  async resolve(rawKey: string | undefined): Promise<ApiKeyRow> {
    if (!rawKey) throw new ApiError(401, 'UNAUTHORIZED', 'missing X-Api-Key');
    const hash = sha256Hex(rawKey);
    const hit = this.cache.get(hash);
    let row: ApiKeyRow | null;
    if (hit && hit.until > this.now()) {
      row = hit.row;
    } else {
      row = await this.backend.findApiKeyByHash(hash);
      this.cache.set(hash, { row, until: this.now() + (row ? this.ttlMs : 5_000) });
      if (this.cache.size > 10_000) this.cache.clear();
    }
    if (!row || row.revoked_at) throw new ApiError(401, 'UNAUTHORIZED', 'invalid or revoked API key');
    return row;
  }

  /** Rol kontrolü yapan preHandler. admin anahtarı app uçlarını da çağırabilir. */
  require(role: Role) {
    return async (req: FastifyRequest, _reply: FastifyReply) => {
      const key = await this.resolve(req.headers['x-api-key'] as string | undefined);
      if (role === 'admin' && key.role !== 'admin') throw new ApiError(403, 'FORBIDDEN', 'admin role required');
      req.apiKey = key;
    };
  }
}
