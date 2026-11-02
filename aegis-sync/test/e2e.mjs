// End-to-end tests for the Aegis sync worker.
// Boots the real worker with wrangler (local D1), then exercises the API
// through the same client library that the browser integration will use.
//
// Run:  npm install && node test/e2e.mjs

import { unstable_dev } from 'wrangler';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { AegisClient, encryptItem, decryptItem, bytesToB64, b64ToBytes } from '../client-example/aegis-client.mjs';

let failures = 0;
function check(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${cond ? '' : '  ' + extra}`);
  if (!cond) failures++;
}

const PASSWORD = 'corr3ct-horse-battery-staple!';

async function main() {
  // Apply schema to the local D1 (idempotent) and clear rate-limit windows so
  // repeated test runs on the same local state start from a clean slate.
  const projDir = new URL('..', import.meta.url).pathname;
  execFileSync('npx', ['wrangler', 'd1', 'execute', 'DB', '--local', '--file=schema.sql'], {
    cwd: projDir,
    stdio: 'pipe',
  });
  execFileSync('npx', ['wrangler', 'd1', 'execute', 'DB', '--local', '--command', 'DELETE FROM rate_limits'], {
    cwd: projDir,
    stdio: 'pipe',
  });

  console.log('Starting wrangler dev (local mode)...');
  const worker = await unstable_dev('src/index.ts', {
    experimental: { disableExperimentalWarning: true },
    vars: { CORS_ORIGIN: '*', INVITE_CODE: '' },
  });
  const base = `http://127.0.0.1:${worker.port}`;
  console.log(`Worker up at ${base}\n`);

  const runId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  const email = `test-${runId}@example.com`;

  try {
    // ---------- health ----------
    const health = await fetch(`${base}/health`).then((r) => r.json());
    check('GET /health ok', health.ok === true && health.service === 'aegis-sync');

    // ---------- register ----------
    const device1 = { id: randomUUID(), name: 'Pixel Test', platform: 'android' };
    const c1 = new AegisClient(base, device1);
    await c1.register(email, PASSWORD);
    console.log('  (registered account)');

    // duplicate registration must fail
    let dup = null;
    try {
      await new AegisClient(base, { id: 'x', name: 'x', platform: 'other' }).register(email, 'other-pass');
    } catch (e) {
      dup = e;
    }
    check('duplicate register -> 409', dup?.status === 409, `got ${dup?.status}`);

    // ---------- login (wrong then right) ----------
    let wrong = null;
    try {
      await new AegisClient(base, { id: randomUUID(), name: 'n', platform: 'other' }).login(email, 'totally-wrong');
    } catch (e) {
      wrong = e;
    }
    check('wrong password login -> 401', wrong?.status === 401, `got ${wrong?.status} ${wrong?.code}`);

    const user1 = await c1.login(email, PASSWORD);
    check('login returns user', !!user1.id && !!user1.wrapped_vault_key_b64);
    check('vault key unlocked in memory', c1.vaultKey instanceof Uint8Array && c1.vaultKey.length === 32);

    // ---------- push ----------
    const bmKey = randomUUID();
    const push1 = await c1.push([
      { type: 'bookmark', key: bmKey, base_version: 0, plaintext: { title: 'Example', url: 'https://example.org' } },
      { type: 'tab', key: randomUUID(), base_version: 0, plaintext: { url: 'https://news.ycombinator.com', title: 'HN' } },
      { type: 'password', key: randomUUID(), base_version: 0, plaintext: { host: 'example.org', username: 'me', secret: 'hunter2-but-encrypted' } },
      { type: 'autofill', key: randomUUID(), base_version: 0, plaintext: { field: 'address', value: '221B Baker Street' } },
      { type: 'setting', key: 'pref.theme', base_version: 0, plaintext: { value: 'dark' } },
    ]);
    check('push 5 new items ok', push1.results.every((r) => r.status === 'ok' && r.version === 1), JSON.stringify(push1.results));

    // ---------- stale push -> conflict ----------
    const stale = await c1.push([
      { type: 'bookmark', key: bmKey, base_version: 0, plaintext: { title: 'Stale', url: 'https://stale.example' } },
    ]);
    check('stale base_version -> conflict with server item', stale.results[0].status === 'conflict' && stale.results[0].server_item?.version === 1);

    // ---------- correct update ----------
    const upd = await c1.push([
      { type: 'bookmark', key: bmKey, base_version: 1, plaintext: { title: 'Example v2', url: 'https://example.org/updated' } },
    ]);
    check('update with base_version 1 -> ok v2', upd.results[0].status === 'ok' && upd.results[0].version === 2);

    // ---------- validation ----------
    let badType = null;
    try {
      await c1.push([{ type: 'history', key: 'x', base_version: 0, plaintext: { a: 1 } }]);
    } catch (e) {
      badType = e;
    }
    check('push with unknown item_type -> 400', badType?.status === 400, `got ${badType?.status}`);

    // ---------- pull ----------
    const pull1 = await c1.pull(0);
    check('pull returns all 5 items', pull1.items.length === 5, `got ${pull1.items.length}`);
    const bmRow = pull1.items.find((i) => i.item_type === 'bookmark');
    check('pulled bookmark decrypts to updated plaintext', bmRow.plaintext.title === 'Example v2');
    check('pulled password plaintext round-trips', pull1.items.find((i) => i.item_type === 'password').plaintext.secret === 'hunter2-but-encrypted');

    const pullTyped = await c1.pull(0, ['password', 'autofill']);
    check('typed pull returns only passwords+autofill', pullTyped.items.length === 2 && pullTyped.items.every((i) => ['password', 'autofill'].includes(i.item_type)));

    // pagination: cursor at last event -> empty page
    const cur = await c1.cursor();
    const pullEmpty = await c1.pull(cur.cursor);
    check('pull at current cursor -> 0 items', pullEmpty.items.length === 0);

    // ---------- cross-device sync ----------
    const device2 = { id: randomUUID(), name: 'Windows Test Laptop', platform: 'windows' };
    const c2 = new AegisClient(base, device2);
    await c2.login(email, PASSWORD);
    check('second device login ok', !!c2.vaultKey);
    check('both devices derive the SAME vault key', bytesToB64(c2.vaultKey) === bytesToB64(c1.vaultKey));

    const pull2 = await c2.pull(0);
    check('device2 sees device1 items', pull2.items.length === 5);

    // device2 pushes; device1 pulls the delta
    const tabKey = randomUUID();
    await c2.push([{ type: 'tab', key: tabKey, base_version: 0, plaintext: { url: 'https://cloudflare.com', title: 'CF' } }]);
    const pull3 = await c1.pull(cur.cursor);
    check('device1 pulls device2 delta (1 new item)', pull3.items.length === 1 && pull3.items[0].item_key === tabKey);

    // ---------- devices ----------
    const devices = await c1.devices();
    check('device list has both devices', devices.devices.length === 2);
    check('current device flagged', devices.devices.filter((d) => d.current).length === 1);

    // revoke device2 from device1
    await c1.revokeDevice(device2.id);
    const devicesAfter = await c1.devices();
    check('device2 revoked', devicesAfter.devices.length === 1);
    let revoked401 = null;
    try {
      await c2.devices();
    } catch (e) {
      revoked401 = e;
    }
    check('revoked device gets 401', revoked401?.status === 401, `got ${revoked401?.status}`);

    // ---------- refresh rotation ----------
    const firstAccess = c1.accessToken;
    const originalRefresh = c1.refreshToken;

    // 1st rotation: original refresh token -> new pair
    const rot1 = await fetch(`${base}/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: originalRefresh }),
    }).then((r) => r.json());
    check('refresh rotates tokens', !!rot1.access_token && rot1.access_token !== firstAccess);
    c1.accessToken = rot1.access_token;
    c1.refreshToken = rot1.refresh_token;

    // reuse of the rotated-away token must fail
    const reuse = await fetch(`${base}/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: originalRefresh }),
    });
    check('rotated-away refresh token rejected (401)', reuse.status === 401);

    // the access token from BEFORE the rotation must be dead
    const oldAccessRes = await fetch(`${base}/v1/devices`, { headers: { Authorization: `Bearer ${firstAccess}` } });
    check('pre-rotation access token invalidated', oldAccessRes.status === 401);

    // ---------- conflict semantics: two devices write same key ----------
    const sharedKey = randomUUID();
    await c1.push([{ type: 'setting', key: sharedKey, base_version: 0, plaintext: { v: 'a' } }]);
    // device2 has stale view (base 0) but server is at v1
    const c2b = new AegisClient(base, device2);
    await c2b.login(email, PASSWORD);
    const conflict = await c2b.push([{ type: 'setting', key: sharedKey, base_version: 0, plaintext: { v: 'b' } }]);
    check('concurrent write -> conflict + server copy', conflict.results[0].status === 'conflict' && conflict.results[0].server_item.version === 1);
    const resolved = await c2b.push([
      { type: 'setting', key: sharedKey, base_version: conflict.results[0].server_item.version, plaintext: { v: 'merged' } },
    ]);
    check('resolve conflict with server version -> ok', resolved.results[0].status === 'ok' && resolved.results[0].version === 2);

    // ---------- tombstones ----------
    const delRes = await c1.push([{ type: 'setting', key: sharedKey, base_version: 2, deleted: true }]);
    check('tombstone push ok v3', delRes.results[0].status === 'ok' && delRes.results[0].version === 3);
    const pullDel = await c1.pull(0);
    const tomb = pullDel.items.find((i) => i.item_key === sharedKey);
    check('tombstone visible in pull', tomb?.deleted === true && tomb.ciphertext_b64 === null);

    // raw API call: the client lib strips ciphertext from tombstones, so hit
    // the server directly to prove its validation rejects malformed tombstones
    const tombBad = await fetch(`${base}/v1/sync/push`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${c1.accessToken}` },
      body: JSON.stringify({ items: [{ item_type: 'setting', item_key: 'other-key', base_version: 0, deleted: true, ciphertext_b64: 'AAAA' }] }),
    });
    check('tombstone with ciphertext -> 400', tombBad.status === 400, `got ${tombBad.status}`);

    // ---------- export ----------
    const exported = await c1._fetch('/v1/sync/export', { auth: true });
    check('export returns rows', Array.isArray(exported.items) && exported.items.length >= 6);

    // ---------- change password ----------
    const oldVaultKeyB64 = bytesToB64(c1.vaultKey);
    await c1.changePassword(PASSWORD, 'new-passphrase-42!');
    let oldLogin = null;
    try {
      await new AegisClient(base, { id: randomUUID(), name: 'n', platform: 'other' }).login(email, PASSWORD);
    } catch (e) {
      oldLogin = e;
    }
    check('old password rejected after change', oldLogin?.status === 401);

    const c3 = new AegisClient(base, { id: randomUUID(), name: 'Re-login', platform: 'android' });
    await c3.login(email, 'new-passphrase-42!');
    check('new password works', !!c3.vaultKey);
    check('vault key SURVIVED password change (same key)', bytesToB64(c3.vaultKey) === oldVaultKeyB64);
    const pullAfterPw = await c3.pull(0);
    check('data still decrypts after password change', pullAfterPw.items.find((i) => i.item_type === 'bookmark').plaintext.title === 'Example v2');

    // ---------- logout ----------
    await c3.logout();
    let loggedOut = null;
    try {
      await c3.cursor();
    } catch (e) {
      loggedOut = e;
    }
    check('token dead after logout', loggedOut?.status === 401);

    // ---------- unauthorized access ----------
    const noAuth = await fetch(`${base}/v1/sync/pull?cursor=0`);
    check('pull without token -> 401', noAuth.status === 401);
    const badAuth = await fetch(`${base}/v1/sync/pull?cursor=0`, { headers: { Authorization: 'Bearer aeg_bogus' } });
    check('pull with bogus token -> 401', badAuth.status === 401);

    // ---------- delete account ----------
    const c4 = new AegisClient(base, { id: randomUUID(), name: 'Final', platform: 'windows' });
    await c4.login(email, 'new-passphrase-42!');
    await c4.deleteAccount('new-passphrase-42!');
    let gone = null;
    try {
      await c4.login(email, 'new-passphrase-42!');
    } catch (e) {
      gone = e;
    }
    check('account deleted -> login fails', gone?.status === 401);

    // ---------- rate limiting (last, uses throwaway identity) ----------
    const rlEmail = `rl-${runId}@example.com`;
    let saw429 = false;
    for (let i = 0; i < 20; i++) {
      const res = await fetch(`${base}/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: rlEmail, verifier_b64: bytesToB64(new Uint8Array(32).fill(1)), device: { id: 'd', name: 'd', platform: 'other' } }),
      });
      if (res.status === 429) {
        saw429 = true;
        break;
      }
    }
    check('login rate limit kicks in (429)', saw429);
  } finally {
    await worker.stop();
  }

  console.log(failures === 0 ? '\nALL E2E TESTS PASSED' : `\n${failures} E2E TEST(S) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('E2E harness crashed:', e);
  process.exit(1);
});
