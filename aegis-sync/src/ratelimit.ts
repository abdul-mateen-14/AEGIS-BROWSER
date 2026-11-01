// D1-based fixed-window rate limiting. Used on auth endpoints only, so the
// write volume stays far below the free-tier D1 write allowance (100k/day).

import { Env } from './types';
import { ApiError, nowSec } from './util';

export async function enforceRateLimit(
  env: Env,
  bucket: string,
  identity: string,
  limit: number,
  windowSec: number,
): Promise<void> {
  const key = `${bucket}|${identity}`;
  const now = nowSec();

  const row = await env.DB.prepare('SELECT window_start, count FROM rate_limits WHERE key = ?')
    .bind(key)
    .first<{ window_start: number; count: number }>();

  if (!row || row.window_start + windowSec <= now) {
    await env.DB.prepare(
      'INSERT INTO rate_limits (key, window_start, count) VALUES (?, ?, 1) ON CONFLICT(key) DO UPDATE SET window_start = excluded.window_start, count = 1',
    )
      .bind(key, now)
      .run();
    return;
  }

  if (row.count >= limit) {
    throw new ApiError(429, 'rate_limited', 'Too many attempts. Try again later.');
  }

  await env.DB.prepare('UPDATE rate_limits SET count = count + 1 WHERE key = ?').bind(key).run();
}

export function clientIp(request: Request): string {
  return request.headers.get('CF-Connecting-IP') ?? 'unknown';
}
