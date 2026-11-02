// Device management: list and revoke devices linked to an account.

import { Env } from './types';
import { ApiError, json } from './util';
import { AuthContextWithHash } from './auth';

export async function handleListDevices(_request: Request, env: Env, auth: AuthContextWithHash): Promise<Response> {
  const { results } = await env.DB.prepare(
    'SELECT id, name, platform, created_at, last_seen_at FROM devices WHERE user_id = ? ORDER BY last_seen_at DESC',
  )
    .bind(auth.userId)
    .all<{ id: string; name: string; platform: string; created_at: number; last_seen_at: number }>();

  return json({
    devices: (results ?? []).map((d) => ({
      id: d.id,
      name: d.name,
      platform: d.platform,
      created_at: d.created_at,
      last_seen_at: d.last_seen_at,
      current: d.id === auth.deviceId,
    })),
  });
}

export async function handleRevokeDevice(request: Request, env: Env, auth: AuthContextWithHash, deviceId: string): Promise<Response> {
  if (deviceId === auth.deviceId) {
    throw new ApiError(400, 'invalid_request', 'Use /v1/auth/logout to sign out the current device');
  }

  const device = await env.DB.prepare('SELECT id FROM devices WHERE user_id = ? AND id = ?').bind(auth.userId, deviceId).first();
  if (!device) throw new ApiError(404, 'not_found', 'Device not found');

  // Data belongs to the user, not the device: revoking removes the device and
  // its sessions but keeps every synced item intact.
  await env.DB.batch([
    env.DB.prepare('DELETE FROM devices WHERE user_id = ? AND id = ?').bind(auth.userId, deviceId),
    env.DB.prepare('DELETE FROM sessions WHERE user_id = ? AND device_id = ?').bind(auth.userId, deviceId),
  ]);

  return json({ ok: true, revoked: deviceId });
}
