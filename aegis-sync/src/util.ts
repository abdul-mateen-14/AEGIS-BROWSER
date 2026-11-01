// Small shared helpers: JSON responses, base64, hashing, validation.

export class ApiError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export function json(data: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...extraHeaders },
  });
}

export function errResponse(e: unknown, corsHeaders: Record<string, string>): Response {
  if (e instanceof ApiError) {
    return json({ error: { code: e.code, message: e.message } }, e.status, corsHeaders);
  }
  console.error('internal error:', e);
  return json({ error: { code: 'internal', message: 'Internal server error' } }, 500, corsHeaders);
}

// ---------- encoding ----------

export function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function bytesToB64(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

export const utf8 = new TextEncoder();

export function nowSec(): number {
  return Math.floor(Date.now() / 1000);
}

// ---------- crypto ----------

export async function sha256(data: Uint8Array): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest('SHA-256', data as unknown as ArrayBuffer);
  return new Uint8Array(digest);
}

/** Constant-time comparison (prevents trivial timing oracles on token/verifier checks). */
export function constantTimeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

export function randomTokenB64(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return 'aeg_' + bytesToB64(bytes).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

// ---------- request parsing ----------

export async function readJson<T = Record<string, unknown>>(request: Request, maxBytes: number): Promise<T> {
  const lenHeader = parseInt(request.headers.get('Content-Length') ?? '0', 10);
  if (lenHeader > maxBytes) throw new ApiError(413, 'payload_too_large', 'Request body too large');
  const text = await request.text();
  if (text.length > maxBytes) throw new ApiError(413, 'payload_too_large', 'Request body too large');
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError(400, 'invalid_request', 'Body must be valid JSON');
  }
}

export function requireString(obj: object, field: string, maxLen = 320): string {
  const v = (obj as Record<string, unknown>)[field];
  if (typeof v !== 'string' || v.length === 0 || v.length > maxLen) {
    throw new ApiError(400, 'invalid_request', `Field "${field}" must be a non-empty string (max ${maxLen} chars)`);
  }
  return v;
}

export function requireB64(obj: object, field: string, maxBytes: number): Uint8Array {
  const v = (obj as Record<string, unknown>)[field];
  if (typeof v !== 'string' || v.length === 0) {
    throw new ApiError(400, 'invalid_request', `Field "${field}" must be a base64 string`);
  }
  let bytes: Uint8Array;
  try {
    bytes = b64ToBytes(v);
  } catch {
    throw new ApiError(400, 'invalid_request', `Field "${field}" is not valid base64`);
  }
  if (bytes.length > maxBytes) throw new ApiError(400, 'invalid_request', `Field "${field}" exceeds ${maxBytes} bytes`);
  return bytes;
}

export function normalizeEmail(raw: string): string {
  const email = raw.trim().toLowerCase();
  // Pragmatic check; a private sync server only needs to avoid obvious junk.
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254) {
    throw new ApiError(400, 'invalid_request', 'Field "email" must be a valid email address');
  }
  return email;
}
