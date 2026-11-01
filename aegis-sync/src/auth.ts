// Authentication: register, login, token refresh, password change, account
// deletion, and session verification.
//
// Design (E2E, server never learns the password):
//   masterKey  = PBKDF2-SHA256(password, kdf_salt, iterations)     [client-side]
//   authKey    = HKDF-SHA256(masterKey, info="aegis-auth-v1")      [client-side]
//   verifier   = PBKDF2-SHA256(authKey, "aegis-verifier-v1", 100k) [client-side]
//   server stores SHA-256(verifier) -> a DB leak cannot be used to log in
//   vaultKey   = 32 random bytes; wrapped with KEK = HKDF(masterKey, "aegis-kek-v1")
//   All sync payloads are AES-256-GCM encrypted with vaultKey on the device.
//
// The verifier travels over TLS. Server-side Argon2id is impossible on the
// free Workers tier (10 ms CPU cap), so the slowness is deliberately pushed
// into the client-side verifier derivation instead.

import { DeviceInfo, Env, KdfParams, LIMITS, VALID_PLATFORMS } from './types';
import {
  ApiError,
  bytesToB64,
  constantTimeEqual,
  json,
  normalizeEmail,
  nowSec,
  randomTokenB64,
  readJson,
  requireB64,
  requireString,
  sha256,
  utf8,
} from './util';
import { clientIp, enforceRateLimit } from './ratelimit';

interface RegisterBody {
  email: string;
  kdf_salt_b64: string;
  kdf: KdfParams;
  verifier_b64: string;
  wrapped_vault_key_b64: string;
  vault_nonce_b64: string;
  invite_code?: string;
}

interface LoginBody {
  email: string;
  verifier_b64: string;
  device: DeviceInfo;
}

export async function handleRegister(request: Request, env: Env): Promise<Response> {
  const body = await readJson<RegisterBody>(request, LIMITS.MAX_BODY_BYTES);
  const email = normalizeEmail(requireString(body, 'email'));
  const kdfSalt = requireB64(body, 'kdf_salt_b64', 64);
  const verifier = requireB64(body, 'verifier_b64', 64);
  const wrapped = requireB64(body, 'wrapped_vault_key_b64', 128);
  const vaultNonce = requireB64(body, 'vault_nonce_b64', 64);

  const kdf = body.kdf;
  if (!kdf || typeof kdf !== 'object' || kdf.algo !== 'PBKDF2-SHA256') {
    throw new ApiError(400, 'invalid_request', 'Field "kdf.algo" must be "PBKDF2-SHA256"');
  }
  const iterations = kdf.iterations;
  if (typeof iterations !== 'number' || iterations < 100000 || iterations > 2000000) {
    throw new ApiError(400, 'invalid_request', 'Field "kdf.iterations" must be between 100000 and 2000000');
  }

  // Optional invite-only registration.
  if (env.INVITE_CODE && env.INVITE_CODE.length > 0) {
    if (body.invite_code !== env.INVITE_CODE) {
      throw new ApiError(403, 'forbidden', 'Invalid invite code');
    }
  }

  await enforceRateLimit(env, 'register|ip', clientIp(request), 5, 900);

  const verifierHash = await sha256(verifier);
  const userId = crypto.randomUUID();
  const now = nowSec();

  try {
    await env.DB.prepare(
      `INSERT INTO users (id, email, kdf_salt, kdf_algo, kdf_iterations, verifier_hash, wrapped_vault_key, vault_nonce, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
      .bind(userId, email, kdfSalt, kdf.algo, iterations, verifierHash, wrapped, vaultNonce, now)
      .run();
  } catch {
    throw new ApiError(409, 'email_taken', 'An account with this email already exists');
  }

  return json({ user_id: userId, created: true }, 201);
}

/** Creates or refreshes a device row and mints a new session pair. */
async function createSession(env: Env, userId: string, device: DeviceInfo) {
  if (!device || typeof device !== 'object') {
    throw new ApiError(400, 'invalid_request', 'Field "device" is required');
  }
  const deviceId = requireString(device as unknown as Record<string, unknown>, 'id', 64);
  const deviceName = requireString(device as unknown as Record<string, unknown>, 'name', 100);
  const platform = String((device as unknown as Record<string, unknown>).platform ?? 'other');
  if (!VALID_PLATFORMS.includes(platform)) {
    throw new ApiError(400, 'invalid_request', `Field "device.platform" must be one of: ${VALID_PLATFORMS.join(', ')}`);
  }

  const now = nowSec();
  await env.DB.prepare(
    `INSERT INTO devices (user_id, id, name, platform, created_at, last_seen_at)
     VALUES (?, ?, ?, ?, ?, ?)
     ON CONFLICT(user_id, id) DO UPDATE SET name = excluded.name, platform = excluded.platform, last_seen_at = excluded.last_seen_at`,
  )
    .bind(userId, deviceId, deviceName, platform, now, now)
    .run();

  const accessToken = randomTokenB64();
  const refreshToken = randomTokenB64();
  const accessHash = await sha256(utf8.encode(accessToken));
  const refreshHash = await sha256(utf8.encode(refreshToken));

  await env.DB.batch([
    env.DB.prepare('DELETE FROM sessions WHERE user_id = ? AND device_id = ? AND kind = ?').bind(userId, deviceId, 'access'),
    env.DB.prepare('DELETE FROM sessions WHERE user_id = ? AND device_id = ? AND kind = ?').bind(userId, deviceId, 'refresh'),
    env.DB.prepare(
      'INSERT INTO sessions (user_id, device_id, kind, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(userId, deviceId, 'access', accessHash, now + LIMITS.ACCESS_TOKEN_TTL, now),
    env.DB.prepare(
      'INSERT INTO sessions (user_id, device_id, kind, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(userId, deviceId, 'refresh', refreshHash, now + LIMITS.REFRESH_TOKEN_TTL, now),
  ]);

  return { accessToken, refreshToken };
}

/**
 * Returns the KDF parameters for an email so the client can derive the login
 * verifier. The salt is public data (it is not secret); for unknown emails we
 * return a random salt so the response shape is identical either way.
 * Offline guessing is still blocked by the client-side PBKDF2 chain.
 */
export async function handleKdfParams(request: Request, env: Env, url: URL): Promise<Response> {
  const email = normalizeEmail(url.searchParams.get('email') ?? '');
  await enforceRateLimit(env, 'kdf|ip', clientIp(request), 60, 900);

  const user = await env.DB.prepare('SELECT kdf_salt, kdf_algo, kdf_iterations FROM users WHERE email = ?')
    .bind(email)
    .first<{ kdf_salt: Uint8Array; kdf_algo: string; kdf_iterations: number }>();

  if (user) {
    return json({ kdf_salt_b64: bytesToB64(user.kdf_salt), kdf: { algo: user.kdf_algo, iterations: user.kdf_iterations } });
  }
  const fakeSalt = new Uint8Array(16);
  crypto.getRandomValues(fakeSalt);
  return json({ kdf_salt_b64: bytesToB64(fakeSalt), kdf: { algo: 'PBKDF2-SHA256', iterations: 600000 } });
}

export async function handleLogin(request: Request, env: Env): Promise<Response> {
  const body = await readJson<LoginBody>(request, LIMITS.MAX_BODY_BYTES);
  const email = normalizeEmail(requireString(body, 'email'));
  const verifier = requireB64(body, 'verifier_b64', 64);

  // Rate limit per IP and per account to slow brute-force.
  await enforceRateLimit(env, 'login|ip', clientIp(request), 30, 900);
  await enforceRateLimit(env, 'login|em', await sha256Text(email), 15, 900);

  const user = await env.DB.prepare(
    'SELECT id, email, kdf_salt, kdf_algo, kdf_iterations, verifier_hash, wrapped_vault_key, vault_nonce FROM users WHERE email = ?',
  )
    .bind(email)
    .first<{ id: string; email: string; kdf_salt: Uint8Array; kdf_algo: string; kdf_iterations: number; verifier_hash: Uint8Array; wrapped_vault_key: Uint8Array; vault_nonce: Uint8Array }>();

  if (!user || !constantTimeEqual(await sha256(verifier), user.verifier_hash)) {
    // Same error for unknown email and wrong password (no account enumeration).
    throw new ApiError(401, 'invalid_credentials', 'Invalid email or verifier');
  }

  const device = body.device;
  const { accessToken, refreshToken } = await createSession(env, user.id, device);

  return json({
    access_token: accessToken,
    refresh_token: refreshToken,
    token_type: 'Bearer',
    expires_in: LIMITS.ACCESS_TOKEN_TTL,
    user: {
      id: user.id,
      email: user.email,
      kdf_salt_b64: bytesToB64(user.kdf_salt),
      kdf: { algo: user.kdf_algo, iterations: user.kdf_iterations },
      wrapped_vault_key_b64: bytesToB64(user.wrapped_vault_key),
      vault_nonce_b64: bytesToB64(user.vault_nonce),
    },
  });
}

async function sha256Text(text: string): Promise<string> {
  const digest = await sha256(utf8.encode(text));
  return bytesToB64(digest).slice(0, 22);
}

export interface RefreshBody {
  refresh_token: string;
}

export async function handleRefresh(request: Request, env: Env): Promise<Response> {
  const body = await readJson<RefreshBody>(request, LIMITS.MAX_BODY_BYTES);
  const raw = requireString(body, 'refresh_token', 128);
  const tokenHash = await sha256(utf8.encode(raw));

  const session = await env.DB.prepare(
    "SELECT id, user_id, device_id FROM sessions WHERE token_hash = ? AND kind = 'refresh' AND expires_at > ?",
  )
    .bind(tokenHash, nowSec())
    .first<{ id: number; user_id: string; device_id: string | null }>();

  if (!session) throw new ApiError(401, 'unauthorized', 'Invalid or expired refresh token');

  const now = nowSec();
  const accessToken = randomTokenB64();
  const refreshToken = randomTokenB64();

  // Rotation: the presented refresh token dies, a new pair is born.
  await env.DB.batch([
    env.DB.prepare('DELETE FROM sessions WHERE id = ?').bind(session.id),
    env.DB.prepare(
      "DELETE FROM sessions WHERE user_id = ? AND device_id = ? AND kind = 'access'",
    ).bind(session.user_id, session.device_id),
    env.DB.prepare(
      'INSERT INTO sessions (user_id, device_id, kind, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(session.user_id, session.device_id, 'access', await sha256(utf8.encode(accessToken)), now + LIMITS.ACCESS_TOKEN_TTL, now),
    env.DB.prepare(
      'INSERT INTO sessions (user_id, device_id, kind, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)',
    ).bind(session.user_id, session.device_id, 'refresh', await sha256(utf8.encode(refreshToken)), now + LIMITS.REFRESH_TOKEN_TTL, now),
    env.DB.prepare('UPDATE devices SET last_seen_at = ? WHERE user_id = ? AND id = ?').bind(now, session.user_id, session.device_id),
  ]);

  return json({ access_token: accessToken, refresh_token: refreshToken, token_type: 'Bearer', expires_in: LIMITS.ACCESS_TOKEN_TTL });
}

export async function handleLogout(request: Request, env: Env, auth: { userId: string; tokenHash: Uint8Array }): Promise<Response> {
  await env.DB.prepare('DELETE FROM sessions WHERE user_id = ? AND token_hash = ?').bind(auth.userId, auth.tokenHash).run();
  return json({ ok: true });
}

export interface ChangePasswordBody {
  old_verifier_b64: string;
  kdf_salt_b64: string;
  kdf: KdfParams;
  new_verifier_b64: string;
  wrapped_vault_key_b64: string;
  vault_nonce_b64: string;
}

export async function handleChangePassword(request: Request, env: Env, auth: AuthContextWithHash): Promise<Response> {
  const body = await readJson<ChangePasswordBody>(request, LIMITS.MAX_BODY_BYTES);
  const oldVerifier = requireB64(body, 'old_verifier_b64', 64);
  const newVerifier = requireB64(body, 'new_verifier_b64', 64);
  const newSalt = requireB64(body, 'kdf_salt_b64', 64);
  const wrapped = requireB64(body, 'wrapped_vault_key_b64', 128);
  const vaultNonce = requireB64(body, 'vault_nonce_b64', 64);

  const kdf = body.kdf;
  if (!kdf || typeof kdf !== 'object' || kdf.algo !== 'PBKDF2-SHA256') {
    throw new ApiError(400, 'invalid_request', 'Field "kdf.algo" must be "PBKDF2-SHA256"');
  }
  const iterations = kdf.iterations;
  if (typeof iterations !== 'number' || iterations < 100000 || iterations > 2000000) {
    throw new ApiError(400, 'invalid_request', 'Field "kdf.iterations" must be between 100000 and 2000000');
  }

  const user = await env.DB.prepare('SELECT verifier_hash FROM users WHERE id = ?').bind(auth.userId).first<{ verifier_hash: Uint8Array }>();
  if (!user || !constantTimeEqual(await sha256(oldVerifier), user.verifier_hash)) {
    throw new ApiError(403, 'forbidden', 'Old verifier is incorrect');
  }

  const newHash = await sha256(newVerifier);
  // Keep the current device logged in; all other devices must re-login.
  await env.DB.batch([
    env.DB.prepare(
      'UPDATE users SET kdf_salt = ?, kdf_algo = ?, kdf_iterations = ?, verifier_hash = ?, wrapped_vault_key = ?, vault_nonce = ? WHERE id = ?',
    ).bind(newSalt, kdf.algo, iterations, newHash, wrapped, vaultNonce, auth.userId),
    env.DB.prepare("DELETE FROM sessions WHERE user_id = ? AND device_id != ? AND kind = 'refresh'").bind(auth.userId, auth.deviceId ?? ''),
  ]);

  return json({ ok: true });
}

export async function handleDeleteAccount(request: Request, env: Env, auth: AuthContextWithHash): Promise<Response> {
  const body = await readJson<{ verifier_b64: string }>(request, LIMITS.MAX_BODY_BYTES);
  const verifier = requireB64(body, 'verifier_b64', 64);

  const user = await env.DB.prepare('SELECT verifier_hash FROM users WHERE id = ?').bind(auth.userId).first<{ verifier_hash: Uint8Array }>();
  if (!user || !constantTimeEqual(await sha256(verifier), user.verifier_hash)) {
    throw new ApiError(403, 'forbidden', 'Verifier is incorrect');
  }

  // Explicit deletes (cascade order) so this works regardless of FK pragma.
  await env.DB.batch([
    env.DB.prepare('DELETE FROM sync_items WHERE user_id = ?').bind(auth.userId),
    env.DB.prepare('DELETE FROM sessions WHERE user_id = ?').bind(auth.userId),
    env.DB.prepare('DELETE FROM devices WHERE user_id = ?').bind(auth.userId),
    env.DB.prepare('DELETE FROM users WHERE id = ?').bind(auth.userId),
  ]);

  return json({ ok: true, deleted: true });
}

// ---------- session middleware ----------

export interface AuthContextWithHash {
  userId: string;
  deviceId: string | null;
  tokenHash: Uint8Array;
}

/** Verifies the Authorization: Bearer token and returns the auth context. */
export async function requireAuth(request: Request, env: Env): Promise<AuthContextWithHash> {
  const header = request.headers.get('Authorization') ?? '';
  const m = /^Bearer\s+(.+)$/i.exec(header);
  if (!m) throw new ApiError(401, 'unauthorized', 'Missing bearer token');
  const raw = m[1];
  if (!raw.startsWith('aeg_') || raw.length > 128) throw new ApiError(401, 'unauthorized', 'Malformed token');

  const tokenHash = await sha256(utf8.encode(raw));
  const session = await env.DB.prepare(
    "SELECT user_id, device_id FROM sessions WHERE token_hash = ? AND kind = 'access' AND expires_at > ?",
  )
    .bind(tokenHash, nowSec())
    .first<{ user_id: string; device_id: string | null }>();

  if (!session) throw new ApiError(401, 'unauthorized', 'Invalid or expired token');
  return { userId: session.user_id, deviceId: session.device_id, tokenHash };
}
