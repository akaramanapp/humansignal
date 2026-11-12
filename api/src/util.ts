import { createHash } from 'node:crypto';

export const sha256Hex = (b: Buffer | string) => createHash('sha256').update(b).digest('hex');

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isUuid = (s: string) => UUID_RE.test(s);

export const chunkPath = (sessionId: string, seq: number) =>
  `sessions/${sessionId}/${String(seq).padStart(6, '0')}.ndjson.gz`;

/** 0..total-1 içinde `have`'de olmayanlar. */
export function missingSeqs(total: number, have: Iterable<number>): number[] {
  const s = new Set(have);
  const out: number[] = [];
  for (let i = 0; i < total; i++) if (!s.has(i)) out.push(i);
  return out;
}
