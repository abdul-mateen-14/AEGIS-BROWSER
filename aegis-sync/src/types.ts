// Shared types for the Aegis sync worker.

export interface Env {
  DB: D1Database;
  CORS_ORIGIN: string;
  INVITE_CODE: string;
}

export interface KdfParams {
  algo: string; // "PBKDF2-SHA256"
  iterations: number; // e.g. 600000
}

export interface DeviceInfo {
  id: string; // client-generated UUID, stable per install
  name: string;
  platform: string; // android | windows | linux | macos | other
}

export interface PushItem {
  item_type: string;
  item_key: string;
  base_version: number; // version the client saw; 0 = create
  ciphertext_b64: string | null; // null only with deleted=true
  nonce_b64: string | null;
  deleted: boolean;
}

export interface SyncItemRow {
  event_id: number;
  item_type: string;
  item_key: string;
  version: number;
  ciphertext: Uint8Array | null;
  nonce: Uint8Array | null;
  deleted: number;
  device_id: string | null;
  updated_at: number;
}

export interface AuthContext {
  userId: string;
  deviceId: string | null;
}

export const ALLOWED_ITEM_TYPES = ['bookmark', 'tab', 'setting', 'password', 'autofill'];
export const VALID_PLATFORMS = ['android', 'windows', 'linux', 'macos', 'other'];

// Limits tuned for the Cloudflare free tier.
export const LIMITS = {
  MAX_PUSH_ITEMS: 200,
  MAX_CIPHERTEXT_BYTES: 512 * 1024, // 512 KB per item
  MAX_BODY_BYTES: 4 * 1024 * 1024, // 4 MB per request
  PULL_PAGE_SIZE: 500,
  EXPORT_PAGE_SIZE: 1000,
  ACCESS_TOKEN_TTL: 24 * 3600, // 24 h
  REFRESH_TOKEN_TTL: 90 * 24 * 3600, // 90 d
};
