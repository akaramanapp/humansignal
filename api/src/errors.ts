export type ErrorCode =
  | 'VALIDATION_ERROR'
  | 'BAD_CHECKSUM'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'SESSION_NOT_FOUND'
  | 'PARTICIPANT_NOT_FOUND'
  | 'NOT_FOUND'
  | 'CHUNK_CONFLICT'
  | 'SESSION_CLOSED'
  | 'PAYLOAD_TOO_LARGE'
  | 'RATE_LIMITED'
  | 'NOT_IMPLEMENTED'
  | 'UNAVAILABLE'
  | 'INTERNAL';

/** §6.3 hata formatı: `{ "error": { "code", "message", "details" } }` */
export class ApiError extends Error {
  constructor(
    readonly statusCode: number,
    readonly code: ErrorCode,
    message: string,
    readonly details: Record<string, unknown> = {},
  ) {
    super(message);
  }

  toBody() {
    return { error: { code: this.code, message: this.message, details: this.details } };
  }
}

export const notFoundSession = () => new ApiError(404, 'SESSION_NOT_FOUND', 'session not found');
