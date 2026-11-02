-- Aegis Browser sync backend - D1 (SQLite) schema
-- Apply with:  npx wrangler d1 execute DB --local --file=schema.sql   (local dev)
--              npx wrangler d1 execute DB --remote --file=schema.sql  (production)
--
-- Privacy model: every sync payload (ciphertext) is end-to-end encrypted on the
-- device with a vault key that the server never receives. The server only ever
-- sees: your email address, opaque ciphertext blobs, and non-identifying
-- metadata (item counts, timestamps, device names).

CREATE TABLE IF NOT EXISTS users (
  id                TEXT PRIMARY KEY,                -- UUID v4
  email             TEXT NOT NULL UNIQUE,            -- lower-cased
  kdf_salt          BLOB NOT NULL,                   -- 16 random bytes (public)
  kdf_algo          TEXT NOT NULL DEFAULT 'PBKDF2-SHA256',
  kdf_iterations    INTEGER NOT NULL DEFAULT 600000,
  verifier_hash     BLOB NOT NULL,                   -- SHA-256(verifier); verifier computed client-side
  wrapped_vault_key BLOB NOT NULL,                   -- AES-256-GCM(kek, vault_key)
  vault_nonce       BLOB NOT NULL,                   -- 12-byte GCM nonce for wrapped_vault_key
  created_at        INTEGER NOT NULL                 -- unix seconds
);

CREATE TABLE IF NOT EXISTS devices (
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  id           TEXT NOT NULL,                        -- client-generated UUID, stable per install
  name         TEXT NOT NULL,                        -- e.g. "Pixel 9"
  platform     TEXT NOT NULL CHECK (platform IN ('android','windows','linux','macos','other')),
  created_at   INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, id)
);

CREATE TABLE IF NOT EXISTS sessions (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id  TEXT,
  kind       TEXT NOT NULL CHECK (kind IN ('access','refresh')),
  token_hash BLOB NOT NULL UNIQUE,                   -- SHA-256(token); raw token never stored
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id, kind);

-- One row per synced object. Ciphertext is opaque to the server.
CREATE TABLE IF NOT EXISTS sync_items (
  event_id   INTEGER PRIMARY KEY AUTOINCREMENT,      -- the pull cursor
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  item_type  TEXT NOT NULL CHECK (item_type IN ('bookmark','tab','setting','password','autofill')),
  item_key   TEXT NOT NULL,                          -- client UUID for the object
  version    INTEGER NOT NULL DEFAULT 1,             -- optimistic-concurrency counter
  ciphertext BLOB,                                   -- NULL on tombstones
  nonce      BLOB,
  deleted    INTEGER NOT NULL DEFAULT 0,             -- 1 = tombstone
  device_id  TEXT,                                   -- device that last wrote this item
  updated_at INTEGER NOT NULL,
  UNIQUE (user_id, item_type, item_key)
);
CREATE INDEX IF NOT EXISTS idx_sync_user_cursor ON sync_items(user_id, event_id);

-- Simple fixed-window rate limiting (auth endpoints only). D1 instead of KV:
-- free KV allows only 1,000 writes/day, which rate limiting would exhaust.
CREATE TABLE IF NOT EXISTS rate_limits (
  key          TEXT PRIMARY KEY,                     -- e.g. "login|ip|1.2.3.4"
  window_start INTEGER NOT NULL,
  count        INTEGER NOT NULL
);
