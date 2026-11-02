/**
 * Aegis Browser - client-side crypto + sync reference library.
 * ============================================================
 * Zero-dependency ES module that runs in browsers, Node 18+, and can be
 * ported 1:1 to C++/Java inside the Aegis Chromium client.
 *
 * THREAT MODEL
 * - The server (Cloudflare Workers + D1) stores only opaque ciphertext.
 * - The user's password never leaves the device.
 * - masterKey derives from the password with PBKDF2-SHA256 (600k iterations).
 * - The vault key (which encrypts all sync data) is generated on-device and
 *   only ever stored server-side wrapped (AES-256-GCM) under a key derived
 *   from the master key. Lose the password = lose the data (by design).
 *
 * KEY HIERARCHY
 *   password + kdf_salt --PBKDF2-SHA256 x600k--> masterKey (32B)   [never leaves device]
 *   masterKey --HKDF-SHA256 info="aegis-auth-v1"--> authKey        [never leaves device]
 *   authKey  --PBKDF2-SHA256 "aegis-verifier-v1" x100k--> verifier [sent over TLS at login only]
 *   masterKey --HKDF-SHA256 info="aegis-kek-v1"--> kek             [never leaves device]
 *   vaultKey (random 32B) --AES-256-GCM(kek)--> wrapped_vault_key  [stored on server]
 *   every item --AES-256-GCM(vaultKey, aad=type|key|version)--> ciphertext [stored on server]
 */

const VERIFIER_INFO = 'aegis-verifier-v1';
const AUTH_INFO = 'aegis-auth-v1';
const KEK_INFO = 'aegis-kek-v1';

export const DEFAULT_KDF = { algo: 'PBKDF2-SHA256', iterations: 600000 };

// ---------- encoding helpers ----------

export const utf8 = new TextEncoder();
const decoder = new TextDecoder();

export function bytesToB64(bytes) {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

export function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function concat(...arrays) {
  const total = arrays.reduce((n, a) => n + a.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const a of arrays) {
    out.set(a, off);
    off += a.length;
  }
  return out;
}

function randomBytes(n) {
  const b = new Uint8Array(n);
  crypto.getRandomValues(b);
  return b;
}

// ---------- key derivation ----------

/** masterKey = PBKDF2-SHA256(password, kdf_salt, iterations) -> 32 bytes */
export async function deriveMasterKey(password, kdfSaltB64, kdf = DEFAULT_KDF) {
  if (kdf.algo !== 'PBKDF2-SHA256') throw new Error(`Unsupported KDF: ${kdf.algo}`);
  const keyMaterial = await crypto.subtle.importKey('raw', utf8.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: b64ToBytes(kdfSaltB64), iterations: kdf.iterations },
    keyMaterial,
    256,
  );
  return new Uint8Array(bits);
}

/** hkdf(masterKey, info) -> 32 bytes */
export async function hkdf(masterKey, info) {
  const key = await crypto.subtle.importKey('raw', masterKey, 'HKDF', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: utf8.encode(info) },
    key,
    256,
  );
  return new Uint8Array(bits);
}

/** verifier = PBKDF2-SHA256(authKey, "aegis-verifier-v1", 100000) -> 32 bytes */
export async function computeVerifier(authKey) {
  const key = await crypto.subtle.importKey('raw', authKey, 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: utf8.encode(VERIFIER_INFO), iterations: 100000 },
    key,
    256,
  );
  return new Uint8Array(bits);
}

/** Full derivation chain password -> { masterKey, verifier } */
export async function deriveCredentials(password, kdfSaltB64, kdf = DEFAULT_KDF) {
  const masterKey = await deriveMasterKey(password, kdfSaltB64, kdf);
  const authKey = await hkdf(masterKey, AUTH_INFO);
  const verifier = await computeVerifier(authKey);
  const kek = await hkdf(masterKey, KEK_INFO);
  return { masterKey, verifier, kek };
}

// ---------- vault key handling ----------

export function generateVaultKey() {
  return randomBytes(32);
}

export async function wrapVaultKey(vaultKey, kek) {
  const nonce = randomBytes(12);
  const key = await crypto.subtle.importKey('raw', kek, 'AES-GCM', false, ['encrypt']);
  const wrapped = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, tagLength: 128 }, key, vaultKey),
  );
  return { wrapped, nonce };
}

export async function unwrapVaultKey(wrapped, nonceB64, kek) {
  const key = await crypto.subtle.importKey('raw', kek, 'AES-GCM', false, ['decrypt']);
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: b64ToBytes(nonceB64), tagLength: 128 },
    key,
    wrapped,
  );
  return new Uint8Array(plain);
}

// ---------- item encryption (AES-256-GCM with AAD binding) ----------

/** Encrypts a JSON-serializable item. aad binds ciphertext to type+key+version. */
export async function encryptItem(vaultKey, itemType, itemKey, version, plaintextObj) {
  const nonce = randomBytes(12);
  const aad = utf8.encode(`${itemType}|${itemKey}|${version}`);
  const key = await crypto.subtle.importKey('raw', vaultKey, 'AES-GCM', false, ['encrypt']);
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: nonce, additionalData: aad, tagLength: 128 },
      key,
      utf8.encode(JSON.stringify(plaintextObj)),
    ),
  );
  return { nonce_b64: bytesToB64(nonce), ciphertext_b64: bytesToB64(ciphertext) };
}

/** Decrypts a server row. Throws if the key is wrong or the AAD was tampered with. */
export async function decryptItem(vaultKey, row) {
  const aad = utf8.encode(`${row.item_type}|${row.item_key}|${row.version}`);
  const key = await crypto.subtle.importKey('raw', vaultKey, 'AES-GCM', false, ['decrypt']);
  const plain = await crypto.subtle.decrypt(
    {
      name: 'AES-GCM',
      iv: b64ToBytes(row.nonce_b64),
      additionalData: aad,
      tagLength: 128,
    },
    key,
    b64ToBytes(row.ciphertext_b64),
  );
  return JSON.parse(decoder.decode(plain));
}

// ---------- API client ----------

export class AegisClient {
  /**
   * @param {string} baseUrl   e.g. "https://aegis-sync.<your-subdomain>.workers.dev"
   * @param {object} device    { id: <stable UUID>, name, platform }
   */
  constructor(baseUrl, device) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.device = device;
    this.accessToken = null;
    this.refreshToken = null;
    this.vaultKey = null;
    this.user = null;
  }

  async _fetch(path, { method = 'GET', body, auth = false, retry = true } = {}) {
    const headers = { 'Content-Type': 'application/json' };
    if (auth && this.accessToken) headers.Authorization = `Bearer ${this.accessToken}`;
    const res = await fetch(this.baseUrl + path, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });
    const data = await res.json().catch(() => ({}));

    if (res.status === 401 && auth && retry && this.refreshToken) {
      const refreshed = await this._refresh();
      if (refreshed) return this._fetch(path, { method, body, auth, retry: false });
    }
    if (!res.ok) {
      const err = new Error(data?.error?.message || `HTTP ${res.status}`);
      err.status = res.status;
      err.code = data?.error?.code;
      throw err;
    }
    return data;
  }

  async _refresh() {
    if (!this.refreshToken) return false;
    try {
      const data = await this._fetch('/v1/auth/refresh', {
        method: 'POST',
        body: { refresh_token: this.refreshToken },
      });
      this.accessToken = data.access_token;
      this.refreshToken = data.refresh_token;
      return true;
    } catch {
      this.accessToken = null;
      this.refreshToken = null;
      return false;
    }
  }

  /** Creates a new account and returns true. KDF salt is generated here. */
  async register(email, password, inviteCode) {
    const kdfSaltB64 = bytesToB64(randomBytes(16));
    const { verifier, kek } = await deriveCredentials(password, kdfSaltB64);
    const vaultKey = generateVaultKey();
    const { wrapped, nonce } = await wrapVaultKey(vaultKey, kek);

    await this._fetch('/v1/auth/register', {
      method: 'POST',
      body: {
        email,
        kdf_salt_b64: kdfSaltB64,
        kdf: DEFAULT_KDF,
        verifier_b64: bytesToB64(verifier),
        wrapped_vault_key_b64: bytesToB64(wrapped),
        vault_nonce_b64: bytesToB64(nonce),
        ...(inviteCode ? { invite_code: inviteCode } : {}),
      },
    });
    return true;
  }

  /**
   * Logs in, unlocks the vault key in memory, registers/updates this device.
   * Two steps: fetch public KDF params (salt), derive the key chain ONCE,
   * then send the verifier.
   */
  async login(email, password) {
    const params = await this._fetch(`/v1/auth/kdf-params?email=${encodeURIComponent(email)}`);
    const { masterKey, verifier, kek } = await deriveCredentials(password, params.kdf_salt_b64, params.kdf);

    const data = await this._fetch('/v1/auth/login', {
      method: 'POST',
      body: {
        email,
        verifier_b64: bytesToB64(verifier),
        device: this.device,
      },
    });

    this.accessToken = data.access_token;
    this.refreshToken = data.refresh_token;
    this.user = data.user;

    // Server-confirmed params must match what we derived with; if the account
    // was migrated to new KDF params, re-derive before unwrapping.
    if (data.user.kdf.algo !== params.kdf.algo || data.user.kdf.iterations !== params.kdf.iterations) {
      const creds = await deriveCredentials(password, data.user.kdf_salt_b64, data.user.kdf);
      const kek2 = await hkdf(creds.masterKey, KEK_INFO);
      this.vaultKey = await unwrapVaultKey(b64ToBytes(data.user.wrapped_vault_key_b64), data.user.vault_nonce_b64, kek2);
    } else {
      this.vaultKey = await unwrapVaultKey(b64ToBytes(data.user.wrapped_vault_key_b64), data.user.vault_nonce_b64, kek);
    }
    return data.user;
  }

  /** Push items. Auto-encrypts with the vault key. base version = clientVersionSeen. */
  async push(changes) {
    const items = [];
    for (const c of changes) {
      const newVersion = c.base_version + 1;
      if (c.deleted) {
        items.push({ item_type: c.type, item_key: c.key, base_version: c.base_version, deleted: true });
      } else {
        const { nonce_b64, ciphertext_b64 } = await encryptItem(this.vaultKey, c.type, c.key, newVersion, c.plaintext);
        items.push({
          item_type: c.type,
          item_key: c.key,
          base_version: c.base_version,
          nonce_b64,
          ciphertext_b64,
          deleted: false,
        });
      }
    }
    return this._fetch('/v1/sync/push', { method: 'POST', body: { items }, auth: true });
  }

  /** Pull items newer than cursor. Auto-decrypts. */
  async pull(cursor = 0, types = null) {
    const q = new URLSearchParams({ cursor: String(cursor) });
    if (types) q.set('types', types.join(','));
    const data = await this._fetch(`/v1/sync/pull?${q}`, { auth: true });
    const items = [];
    for (const row of data.items) {
      if (row.deleted) {
        items.push({ ...row, plaintext: null });
      } else {
        items.push({ ...row, plaintext: await decryptItem(this.vaultKey, row) });
      }
    }
    return { items, next_cursor: data.next_cursor, has_more: data.has_more };
  }

  async cursor() {
    return this._fetch('/v1/sync/cursor', { auth: true });
  }

  async devices() {
    return this._fetch('/v1/devices', { auth: true });
  }

  async revokeDevice(deviceId) {
    return this._fetch(`/v1/devices/${encodeURIComponent(deviceId)}/revoke`, { method: 'POST', auth: true });
  }

  /** Rotates the password: re-derives keys, re-wraps the SAME vault key. */
  async changePassword(oldPassword, newPassword) {
    const oldSalt = this.user.kdf_salt_b64;
    const oldCreds = await deriveCredentials(oldPassword, oldSalt, this.user.kdf);
    const newSaltB64 = bytesToB64(randomBytes(16));
    const { verifier: newVerifier, kek: newKek } = await deriveCredentials(newPassword, newSaltB64);
    const { wrapped, nonce } = await wrapVaultKey(this.vaultKey, newKek);

    const out = await this._fetch('/v1/auth/change-password', {
      method: 'POST',
      auth: true,
      body: {
        old_verifier_b64: bytesToB64(oldCreds.verifier),
        kdf_salt_b64: newSaltB64,
        kdf: DEFAULT_KDF,
        new_verifier_b64: bytesToB64(newVerifier),
        wrapped_vault_key_b64: bytesToB64(wrapped),
        vault_nonce_b64: bytesToB64(nonce),
      },
    });
    this.user = { ...this.user, kdf_salt_b64: newSaltB64, kdf: DEFAULT_KDF, wrapped_vault_key_b64: bytesToB64(wrapped), vault_nonce_b64: bytesToB64(nonce) };
    return out;
  }

  async logout() {
    try {
      await this._fetch('/v1/auth/logout', { method: 'POST', auth: true });
    } finally {
      this.accessToken = null;
      this.refreshToken = null;
      this.vaultKey = null;
    }
  }

  async deleteAccount(password) {
    const { verifier } = await deriveCredentials(password, this.user.kdf_salt_b64, this.user.kdf);
    return this._fetch('/v1/auth/account', { method: 'DELETE', auth: true, body: { verifier_b64: bytesToB64(verifier) } });
  }
}
