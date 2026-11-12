import { z } from 'zod';

/** §5 sözlüğü — SDK enum'ları, API doğrulaması ve DB check kısıtlarıyla birebir aynı. */
export const LABEL_KIND = ['human', 'bot'] as const;
export const BOT_TOOL = ['adb_input', 'appium', 'uiautomator', 'espresso', 'maestro', 'autoclicker_app',
  'accessibility_app', 'scrcpy_remote', 'replay', 'other'] as const;
export const BOT_HOST = ['emulator_android_studio', 'emulator_genymotion', 'emulator_bluestacks', 'emulator_other',
  'real_device'] as const;
export const PLACEMENT = ['hand', 'table', 'holder', 'lap', 'other'] as const;
export const POSTURE = ['sitting', 'standing', 'walking', 'lying', 'na'] as const;
export const HANDS = ['one_left', 'one_right', 'two', 'na'] as const;
export const HANDEDNESS = ['left', 'right', 'both', 'unknown'] as const;
export const AGE_RANGE = ['18-24', '25-34', '35-44', '45-54', '55-64', '65+', 'unknown'] as const;
export const SESSION_STATUS = ['open', 'incomplete', 'complete'] as const;

export const PARTICIPANT_CODE = /^P\d{3,5}$/;

export const CreateSession = z
  .object({
    local_session_id: z.string().min(8).max(64),
    install_id: z.string().min(8).max(64),
    participant: z
      .object({
        code: z.string().regex(PARTICIPANT_CODE, 'participant.code must match ^P\\d{3,5}$'),
        handedness: z.enum(HANDEDNESS).nullish(),
        age_range: z.enum(AGE_RANGE).nullish(),
        gender: z.string().max(64).nullish(),
      })
      .nullish(),
    consent_version: z.string().min(1).max(64),
    label: z.object({
      kind: z.enum(LABEL_KIND),
      bot_tool: z.enum(BOT_TOOL).nullish(),
      bot_host: z.enum(BOT_HOST).nullish(),
    }),
    context: z.object({
      placement: z.enum(PLACEMENT),
      posture: z.enum(POSTURE),
      hands: z.enum(HANDS),
    }),
    notes: z.string().max(2000).nullish(),
    device: z.record(z.string(), z.unknown()),
    clock: z.object({
      t0_elapsed_ns: z.number().int(),
      t0_uptime_ns: z.number().int(),
      t0_wall_ms: z.number().int(),
      sensor_clock: z.record(z.string(), z.unknown()).optional(),
    }).loose(),
    sdk_version: z.string().min(1).max(32),
    app_version: z.string().max(64).nullish(),
    config_version: z.number().int().nullish(),
  })
  .superRefine((b, ctx) => {
    if (b.label.kind === 'human') {
      if (!b.participant?.code) ctx.addIssue({ code: 'custom', path: ['participant', 'code'], message: 'participant.code is required when label.kind = human' });
      if (b.label.bot_tool != null || b.label.bot_host != null) ctx.addIssue({ code: 'custom', path: ['label'], message: 'label.bot_tool and label.bot_host must be null when label.kind = human' });
    } else {
      if (b.label.bot_tool == null) ctx.addIssue({ code: 'custom', path: ['label', 'bot_tool'], message: 'label.bot_tool is required when label.kind = bot' });
      if (b.label.bot_host == null) ctx.addIssue({ code: 'custom', path: ['label', 'bot_host'], message: 'label.bot_host is required when label.kind = bot' });
    }
  });
export type CreateSessionBody = z.infer<typeof CreateSession>;

export const CompleteSession = z.object({
  total_chunks: z.number().int().min(0).max(1_000_000),
  ended_at_wall_ms: z.number().int().nullish(),
  stop_reason: z.string().max(32).nullish(),
  stats: z.record(z.string(), z.unknown()).nullish(),
  /** Uzantı: oturum sonunda kesinleşen saat bilgisi (sensor_clock). Verilirse `sessions.clock` güncellenir. */
  clock: z.record(z.string(), z.unknown()).nullish(),
});

const safeInt = z.coerce.number().refine(Number.isSafeInteger, 'must be a safe integer');

export const ChunkHeaders = z.object({
  'x-chunk-sha256': z.string().regex(/^[0-9a-f]{64}$/i, 'hex sha256 expected'),
  'x-chunk-t-start': safeInt,
  'x-chunk-t-end': safeInt,
  'x-chunk-streams': z.string().min(1).max(512),
  'x-chunk-records': safeInt.optional(),
});

export const ListSessionsQuery = z.object({
  label_kind: z.enum(LABEL_KIND).optional(),
  bot_tool: z.enum(BOT_TOOL).optional(),
  participant_code: z.string().regex(PARTICIPANT_CODE).optional(),
  status: z.enum(SESSION_STATUS).optional(),
  from: z.iso.datetime({ offset: true }).optional(),
  to: z.iso.datetime({ offset: true }).optional(),
  limit: z.coerce.number().int().min(1).max(500).default(100),
  cursor: z.string().max(200).optional(),
});

/** zod hatasını §6.3 `details` biçimine çevirir; ilk mesaj `message` olur. */
export function zodDetails(e: z.ZodError) {
  const issues = e.issues.map((i) => ({ path: i.path.join('.'), message: i.message }));
  const first = issues[0];
  return {
    message: first ? (first.message.includes(first.path) || !first.path ? first.message : `${first.path}: ${first.message}`) : 'invalid request',
    details: { issues },
  };
}
