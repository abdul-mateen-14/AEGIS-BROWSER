// Sync API: push (optimistic concurrency), pull (cursor-paginated), cursor,
// and full export. The server stores only opaque ciphertext — item contents
// are invisible here by design.

import { ALLOWED_ITEM_TYPES, Env, LIMITS, PushItem, SyncItemRow } from './types';
import { ApiError, b64ToBytes, bytesToB64, json, nowSec, readJson } from './util';
import { AuthContextWithHash } from './auth';

interface PushBody {
  items: PushItem[];
}

function toBytes(v: Uint8Array | ArrayBuffer | null): Uint8Array | null {
  if (v == null) return null;
  return v instanceof Uint8Array ? v : new Uint8Array(v);
}

function rowToJson(row: SyncItemRow) {
  return {
    item_type: row.item_type,
    item_key: row.item_key,
    version: row.version,
    deleted: row.deleted === 1,
    nonce_b64: row.nonce ? bytesToB64(toBytes(row.nonce)!) : null,
    ciphertext_b64: row.ciphertext ? bytesToB64(toBytes(row.ciphertext)!) : null,
    device_id: row.device_id,
    updated_at: row.updated_at,
  };
}

function validatePushItem(raw: unknown): PushItem {
  if (!raw || typeof raw !== 'object') throw new ApiError(400, 'invalid_request', 'Each item must be an object');
  const it = raw as Record<string, unknown>;

  const itemType = it.item_type;
  if (typeof itemType !== 'string' || !ALLOWED_ITEM_TYPES.includes(itemType)) {
    throw new ApiError(400, 'invalid_request', `item_type must be one of: ${ALLOWED_ITEM_TYPES.join(', ')}`);
  }
  const itemKey = it.item_key;
  if (typeof itemKey !== 'string' || itemKey.length === 0 || itemKey.length > 64) {
    throw new ApiError(400, 'invalid_request', 'item_key must be a non-empty string (max 64 chars)');
  }
  const baseVersion = it.base_version;
  if (typeof baseVersion !== 'number' || !Number.isInteger(baseVersion) || baseVersion < 0) {
    throw new ApiError(400, 'invalid_request', 'base_version must be a non-negative integer');
  }
  const deleted = it.deleted === true;

  let ciphertext: string | null = null;
  let nonce: string | null = null;
  if (deleted) {
    if (it.ciphertext_b64 != null || it.nonce_b64 != null) {
      throw new ApiError(400, 'invalid_request', 'Tombstones must not carry ciphertext or nonce');
    }
  } else {
    if (typeof it.ciphertext_b64 !== 'string' || typeof it.nonce_b64 !== 'string') {
      throw new ApiError(400, 'invalid_request', 'Non-deleted items require ciphertext_b64 and nonce_b64');
    }
    let ct: Uint8Array;
    let nc: Uint8Array;
    try {
      ct = b64ToBytes(it.ciphertext_b64);
      nc = b64ToBytes(it.nonce_b64);
    } catch {
      throw new ApiError(400, 'invalid_request', 'ciphertext_b64/nonce_b64 must be valid base64');
    }
    if (ct.length > LIMITS.MAX_CIPHERTEXT_BYTES) {
      throw new ApiError(413, 'payload_too_large', `Ciphertext exceeds ${LIMITS.MAX_CIPHERTEXT_BYTES} bytes`);
    }
    if (nc.length < 8 || nc.length > 32) throw new ApiError(400, 'invalid_request', 'nonce_b64 must be 8-32 bytes');
    ciphertext = it.ciphertext_b64;
    nonce = it.nonce_b64;
  }

  return { item_type: itemType, item_key: itemKey, base_version: baseVersion, ciphertext_b64: ciphertext, nonce_b64: nonce, deleted };
}

export async function handlePush(request: Request, env: Env, auth: AuthContextWithHash): Promise<Response> {
  const body = await readJson<PushBody>(request, LIMITS.MAX_BODY_BYTES);
  if (!Array.isArray(body.items) || body.items.length === 0) {
    throw new ApiError(400, 'invalid_request', 'Field "items" must be a non-empty array');
  }
  if (body.items.length > LIMITS.MAX_PUSH_ITEMS) {
    throw new ApiError(413, 'payload_too_large', `Max ${LIMITS.MAX_PUSH_ITEMS} items per push`);
  }

  const items = body.items.map(validatePushItem);
  const seen = new Set<string>();
  for (const it of items) {
    const k = `${it.item_type}/${it.item_key}`;
    if (seen.has(k)) throw new ApiError(400, 'invalid_request', `Duplicate item in request: ${k}`);
    seen.add(k);
  }

  // ---- read phase: current server state for these keys ----
  const conditions = items.map(() => '(item_type = ? AND item_key = ?)').join(' OR ');
  const binds: unknown[] = [auth.userId];
  for (const it of items) binds.push(it.item_type, it.item_key);
  const { results: current } = await env.DB.prepare(
    `SELECT item_type, item_key, version, deleted FROM sync_items WHERE user_id = ? AND (${conditions})`,
  )
    .bind(...binds)
    .all<{ item_type: string; item_key: string; version: number; deleted: number }>();
  const serverMap = new Map<string, { version: number; deleted: number }>();
  for (const r of current ?? []) serverMap.set(`${r.item_type}/${r.item_key}`, { version: r.version, deleted: r.deleted });

  // ---- decide per item ----
  const now = nowSec();
  type Decision =
    | { kind: 'write'; item: PushItem; sql: string; binds: unknown[] }
    | { kind: 'noop'; item: PushItem; version: number }
    | { kind: 'conflict'; item: PushItem };
  const decisions: Decision[] = [];

  for (const it of items) {
    const key = `${it.item_type}/${it.item_key}`;
    const server = serverMap.get(key);

    if (!server) {
      if (it.base_version !== 0) {
        decisions.push({ kind: 'conflict', item: it }); // stale client; item may have been deleted server-side
        continue;
      }
      if (it.deleted) {
        decisions.push({ kind: 'noop', item: it, version: 0 }); // tombstone for something never synced: done
        continue;
      }
      decisions.push({
        kind: 'write',
        item: it,
        sql: `INSERT INTO sync_items (user_id, item_type, item_key, version, ciphertext, nonce, deleted, device_id, updated_at)
              VALUES (?, ?, ?, 1, ?, ?, 0, ?, ?)`,
        binds: [auth.userId, it.item_type, it.item_key, b64ToBytes(it.ciphertext_b64!), b64ToBytes(it.nonce_b64!), auth.deviceId, now],
      });
      continue;
    }

    if (server.version !== it.base_version) {
      decisions.push({ kind: 'conflict', item: it });
      continue;
    }
    if (it.deleted && server.deleted === 1) {
      decisions.push({ kind: 'noop', item: it, version: server.version });
      continue;
    }
    decisions.push({
      kind: 'write',
      item: it,
      sql: `UPDATE sync_items
            SET version = version + 1, ciphertext = ?, nonce = ?, deleted = ?, device_id = ?, updated_at = ?
            WHERE user_id = ? AND item_type = ? AND item_key = ? AND version = ?`,
      binds: [
        it.deleted ? null : b64ToBytes(it.ciphertext_b64!),
        it.deleted ? null : b64ToBytes(it.nonce_b64!),
        it.deleted ? 1 : 0,
        auth.deviceId,
        now,
        auth.userId,
        it.item_type,
        it.item_key,
        it.base_version,
      ],
    });
  }

  // ---- write phase: single atomic batch ----
  const writes = decisions.filter((d): d is Extract<Decision, { kind: 'write' }> => d.kind === 'write');
  if (writes.length > 0) {
    const stmts = writes.map((d) => env.DB.prepare(d.sql).bind(...d.binds));
    let results: D1Result[];
    try {
      results = await env.DB.batch(stmts);
    } catch {
      // e.g. a concurrent push created the same key between read and write phase
      throw new ApiError(409, 'conflict', 'Concurrent modification detected; pull, merge, then retry');
    }
    // A conditional UPDATE that matched 0 rows means another device raced us.
    const racedIdx: number[] = [];
    results.forEach((r, i) => {
      if ((r.meta?.changes ?? 0) === 0) racedIdx.push(i);
    });
    if (racedIdx.length > 0) {
      for (let i = decisions.length - 1; i >= 0; i--) {
        const d = decisions[i];
        if (d.kind === 'write' && racedIdx.includes(writes.indexOf(d))) decisions[i] = { kind: 'conflict', item: d.item };
      }
    }
  }

  // ---- response; fetch server copies for conflicts ----
  const conflictKeys = decisions.filter((d) => d.kind === 'conflict').map((d) => d.item);
  const conflictRows = new Map<string, SyncItemRow>();
  if (conflictKeys.length > 0) {
    const cBinds: unknown[] = [auth.userId];
    const cConds = conflictKeys.map(() => '(item_type = ? AND item_key = ?)').join(' OR ');
    for (const it of conflictKeys) cBinds.push(it.item_type, it.item_key);
    const { results: cRows } = await env.DB.prepare(
      `SELECT event_id, item_type, item_key, version, ciphertext, nonce, deleted, device_id, updated_at
       FROM sync_items WHERE user_id = ? AND (${cConds})`,
    )
      .bind(...cBinds)
      .all<SyncItemRow>();
    for (const r of cRows ?? []) conflictRows.set(`${r.item_type}/${r.item_key}`, r);
  }

  const out = decisions.map((d) => {
    const key = `${d.item.item_type}/${d.item.item_key}`;
    if (d.kind === 'conflict') {
      const row = conflictRows.get(key);
      return {
        item_type: d.item.item_type,
        item_key: d.item.item_key,
        status: 'conflict',
        server_item: row ? rowToJson(row) : null, // null = item no longer exists; safe to re-push as new
      };
    }
    const version = d.kind === 'write' ? (d.item.base_version === 0 ? 1 : d.item.base_version + 1) : d.kind === 'noop' ? d.version : 0;
    return { item_type: d.item.item_type, item_key: d.item.item_key, status: 'ok', version };
  });

  return json({ results: out });
}

export async function handlePull(request: Request, env: Env, auth: AuthContextWithHash, url: URL): Promise<Response> {
  const cursorRaw = url.searchParams.get('cursor') ?? '0';
  const cursor = Number.parseInt(cursorRaw, 10);
  if (!Number.isInteger(cursor) || cursor < 0) throw new ApiError(400, 'invalid_request', 'cursor must be a non-negative integer');

  const typesParam = url.searchParams.get('types');
  let types: string[] | null = null;
  if (typesParam) {
    types = typesParam.split(',').map((t) => t.trim()).filter(Boolean);
    for (const t of types) {
      if (!ALLOWED_ITEM_TYPES.includes(t)) throw new ApiError(400, 'invalid_request', `Unknown item type: ${t}`);
    }
    if (types.length === 0) types = null;
  }

  let sql = `SELECT event_id, item_type, item_key, version, ciphertext, nonce, deleted, device_id, updated_at
             FROM sync_items WHERE user_id = ? AND event_id > ?`;
  const binds: unknown[] = [auth.userId, cursor];
  if (types) {
    sql += ` AND item_type IN (${types.map(() => '?').join(',')})`;
    binds.push(...types);
  }
  sql += ' ORDER BY event_id ASC LIMIT ?';
  binds.push(LIMITS.PULL_PAGE_SIZE + 1);

  const { results } = await env.DB.prepare(sql).bind(...binds).all<SyncItemRow>();
  const rows = results ?? [];
  const hasMore = rows.length > LIMITS.PULL_PAGE_SIZE;
  const page = hasMore ? rows.slice(0, LIMITS.PULL_PAGE_SIZE) : rows;
  const nextCursor = page.length > 0 ? page[page.length - 1].event_id : cursor;

  return json({
    items: page.map(rowToJson),
    next_cursor: hasMore ? nextCursor : null,
    has_more: hasMore,
  });
}

export async function handleCursor(_request: Request, env: Env, auth: AuthContextWithHash): Promise<Response> {
  const row = await env.DB.prepare('SELECT COALESCE(MAX(event_id), 0) AS cursor FROM sync_items WHERE user_id = ?')
    .bind(auth.userId)
    .first<{ cursor: number }>();
  return json({ cursor: row?.cursor ?? 0 });
}

export async function handleExport(request: Request, env: Env, auth: AuthContextWithHash, url: URL): Promise<Response> {
  const cursorRaw = url.searchParams.get('cursor') ?? '0';
  const cursor = Number.parseInt(cursorRaw, 10);
  if (!Number.isInteger(cursor) || cursor < 0) throw new ApiError(400, 'invalid_request', 'cursor must be a non-negative integer');

  const { results } = await env.DB.prepare(
    `SELECT event_id, item_type, item_key, version, ciphertext, nonce, deleted, device_id, updated_at
     FROM sync_items WHERE user_id = ? AND event_id > ? ORDER BY event_id ASC LIMIT ?`,
  )
    .bind(auth.userId, cursor, LIMITS.EXPORT_PAGE_SIZE + 1)
    .all<SyncItemRow>();

  const rows = results ?? [];
  const hasMore = rows.length > LIMITS.EXPORT_PAGE_SIZE;
  const page = hasMore ? rows.slice(0, LIMITS.EXPORT_PAGE_SIZE) : rows;
  return json({
    items: page.map(rowToJson),
    next_cursor: hasMore ? page[page.length - 1].event_id : null,
  });
}

// ---------- nightly maintenance (cron trigger) ----------

export async function prune(env: Env): Promise<void> {
  const now = nowSec();
  await env.DB.batch([
    env.DB.prepare('DELETE FROM sessions WHERE expires_at < ?').bind(now),
    env.DB.prepare('DELETE FROM sync_items WHERE deleted = 1 AND updated_at < ?').bind(now - 90 * 24 * 3600),
    env.DB.prepare('DELETE FROM rate_limits WHERE window_start < ?').bind(now - 3600),
  ]);
}
