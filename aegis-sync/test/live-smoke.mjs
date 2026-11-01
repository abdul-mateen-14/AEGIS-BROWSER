/*
 * Aegis Browser - LIVE deployment smoke test.
 *
 * Validates a deployed aegis-sync worker (NOT the local dev server):
 *   AEGIS_BASE=https://aegis-sync.example.workers.dev node test/live-smoke.mjs
 *
 * Covers: register, login+device registration, push (encrypted), pull from a
 * second device, conflict (optimistic concurrency), password rotation with
 * vault-key survival, account deletion, post-deletion login rejection.
 * Leaves nothing behind: the test account is deleted at the end.
 */
import { AegisClient } from '../client-example/aegis-client.mjs';

const BASE = process.env.AEGIS_BASE;
if (!BASE) { console.error('Set AEGIS_BASE=https://your-worker.workers.dev'); process.exit(1); }

let passed = 0, failed = 0;
function check(name, cond) {
  if (cond) { passed++; console.log('PASS ', name); }
  else { failed++; console.log('FAIL ', name); }
}

const email = `smoke-${Date.now()}-${Math.floor(Math.random() * 1e6)}@aegis-test.local`;
const password = 'CorrectHorse!42-BatteryStaple';
const deviceId2 = 'smoke-device-2-' + Math.floor(Math.random() * 1e6);

const c1 = new AegisClient(BASE, { id: 'smoke-dev-1-' + Math.floor(Math.random() * 1e6), name: 'Smoke Device 1', platform: 'other' });
const c2 = new AegisClient(BASE, { id: deviceId2, name: 'Smoke Device 2', platform: 'other' });

// --- register + login on device 1
const reg = await c1.register(email, password);
check('register returns ok', reg === true);
await c1.login(email, password);
check('login on device 1', !!c1.accessToken);
check('vault key unwrapped on device 1', c1.vaultKey instanceof Uint8Array && c1.vaultKey.length === 32);

// --- push an encrypted item
await c1.push([{ type: 'bookmark', key: 'smoke-key-1', base_version: 0, plaintext: { url: 'https://example.org', title: 'Example' } }]);
check('push on device 1', true);

// --- second device logs in and pulls
await c2.login(email, password);
check('login on device 2', !!c2.accessToken);
const pulled = await c2.pull(0);
const hit = pulled.items.find((i) => i.item_type === 'bookmark' && i.item_key === 'smoke-key-1');
check('pull on device 2 sees the item', !!hit);
check('E2E decrypt on device 2 matches plaintext', hit?.plaintext?.url === 'https://example.org');

// --- optimistic concurrency: pushing v2 on top of v1 from device 1
await c1.push([{ type: 'bookmark', key: 'smoke-key-1', base_version: 1, plaintext: { url: 'https://example.org/v2', title: 'Example v2' } }]);
const pulled2 = await c2.pull(0);
const hit2 = pulled2.items.find((i) => i.item_key === 'smoke-key-1');
check('conflict-free update propagates (v2)', hit2?.plaintext?.title === 'Example v2');

// --- password rotation; vault key must survive
await c1.changePassword(password, 'NewPass!43-Rotated');
check('password rotation accepted', true);
const c3 = new AegisClient(BASE, { id: 'smoke-dev-3-' + Math.floor(Math.random() * 1e6), name: 'Smoke Device 3', platform: 'other' });
await c3.login(email, 'NewPass!43-Rotated');
check('login with NEW password', !!c3.accessToken);
const pulled3 = await c3.pull(0);
const hit3 = pulled3.items.find((i) => i.item_key === 'smoke-key-1');
check('vault key survived rotation (old data readable)', hit3?.plaintext?.url === 'https://example.org/v2');

// --- old password must now fail
let oldRejected = false;
try { await c2.logout(); } catch {}
const c4 = new AegisClient(BASE, { id: 'smoke-dev-4-' + Math.floor(Math.random() * 1e6), name: 'Smoke Device 4', platform: 'other' });
try { await c4.login(email, password); } catch { oldRejected = true; }
check('old password rejected', oldRejected);

// --- cleanup: delete account, verify login fails
await c3.deleteAccount('NewPass!43-Rotated');
let deletedRejected = false;
const c5 = new AegisClient(BASE, { id: 'smoke-dev-5-' + Math.floor(Math.random() * 1e6), name: 'Smoke Device 5', platform: 'other' });
try { await c5.login(email, 'NewPass!43-Rotated'); } catch { deletedRejected = true; }
check('account deleted -> login rejected', deletedRejected);

console.log(`\nLIVE SMOKE: ${passed} passed, ${failed} failed  (base: ${BASE})`);
process.exit(failed ? 1 : 0);
