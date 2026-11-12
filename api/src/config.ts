import { z } from 'zod';

const Env = z
  .object({
    PORT: z.coerce.number().int().positive().default(8080),
    HOST: z.string().default('0.0.0.0'),
    SUPABASE_URL: z.string().url().optional(),
    SUPABASE_SERVICE_ROLE_KEY: z.string().min(1).optional(),
    STORAGE_BUCKET: z.string().default('raw-chunks'),
    MAX_CHUNK_BYTES: z.coerce.number().int().positive().default(5 * 1024 * 1024),
    RATE_LIMIT_PER_MIN: z.coerce.number().int().positive().default(600),
    ADMIN_RATE_LIMIT_PER_MIN: z.coerce.number().int().positive().default(60),
    LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
    DATA_BACKEND: z.enum(['supabase', 'memory']).default('supabase'),
    /** Yalnızca memory backend: "app:anahtar,admin:anahtar" */
    DEV_API_KEYS: z.string().optional(),
  })
  .superRefine((e, ctx) => {
    if (e.DATA_BACKEND === 'supabase' && (!e.SUPABASE_URL || !e.SUPABASE_SERVICE_ROLE_KEY)) {
      ctx.addIssue({ code: 'custom', message: 'SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required' });
    }
  });

export type Config = z.infer<typeof Env>;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  return Env.parse(env);
}

export const API_VERSION = '1.0.0';
