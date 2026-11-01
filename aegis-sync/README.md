# aegis-sync — sync backend for Aegis Browser

End-to-end-encrypted sync backend for the Aegis Browser project, built on
**Cloudflare Workers + D1**. Runs on the **free tier**.

The server stores only **opaque ciphertext**. Your password and your vault key
never leave your devices. What the server can see is limited to:

- your email address,
- device names you choose,
- item counts, types, versions and timestamps,
- blob sizes (metadata leakage — see "Threat model" below).

Verified by `test/crypto.test.mjs` (15 tests) and `test/e2e.mjs` (40 tests:
registration → login → multi-device sync → conflicts → tombstones → password
change → device revocation → account deletion → rate limiting).

---

## 1. Deploy from scratch (works fine on an 8 GB PC — it's ~5 commands)

Prerequisites: Node.js 18+ (LTS), npm, a free Cloudflare account.

```bash
npm install

# 1. log in to Cloudflare (opens a browser)
npx wrangler login

# 2. create the D1 database — note the database_id it prints
npx wrangler d1 create aegis-sync

# 3. put the id into wrangler.jsonc (replace REPLACE_WITH_YOUR_D1_ID)

# 4. create the tables in the remote database
npx wrangler d1 execute DB --remote --file=schema.sql

# 5. deploy the worker
npx wrangler deploy
```

`wrangler deploy` prints your endpoint, e.g.
`https://aegis-sync.<your-subdomain>.workers.dev`. Health check:

```bash
curl https://aegis-sync.<your-subdomain>.workers.dev/health
# {"ok":true,"service":"aegis-sync","version":"1.0.0"}
```

### Optional hardening

- **Invite-only registration:** `npx wrangler secret put INVITE_CODE` and set a
  long random string; registration then requires `invite_code` in the body.
  (The empty var in `wrangler.jsonc` means open registration.)
- **Custom domain:** add a route in the Cloudflare dashboard so the worker sits
  on `sync.yourdomain.com`.

## 2. Local development

```bash
npx wrangler d1 execute DB --local --file=schema.sql
npx wrangler dev            # http://127.0.0.1:8787
npm test                    # crypto tests + full E2E suite against local runtime
```

## 3. Trying it without a browser build

`client-example/aegis-client.mjs` is a zero-dependency reference client (the
same code the future Chromium integration will port to C++/Java):

```js
import { AegisClient } from './client-example/aegis-client.mjs';

const phone = new AegisClient('https://aegis-sync.<sub>.workers.dev', {
  id: crypto.randomUUID(),      // stable per install — store it
  name: 'My Phone', platform: 'android',
});
await phone.register('me@example.com', 'master password');
await phone.login('me@example.com', 'master password');

await phone.push([{ type: 'bookmark', key: crypto.randomUUID(), base_version: 0,
  plaintext: { title: 'Cloudflare', url: 'https://cloudflare.com' } }]);

const laptop = new AegisClient(BASE_URL, { id: crypto.randomUUID(), name: 'Laptop', platform: 'windows' });
await laptop.login('me@example.com', 'master password');
console.log((await laptop.pull(0)).items); // decrypted on the laptop
```

## 4. API summary

All bodies are JSON. Auth: `Authorization: Bearer <access_token>`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/health` | – | liveness |
| POST | `/v1/auth/register` | – | create account (email, kdf salt, verifier, wrapped vault key) |
| GET | `/v1/auth/kdf-params?email=` | – | fetch public KDF salt/params for deriving the login verifier |
| POST | `/v1/auth/login` | – | verifier in → access+refresh tokens + wrapped vault key out |
| POST | `/v1/auth/refresh` | – | rotate tokens (old refresh dies) |
| POST | `/v1/auth/logout` | ✓ | kill current session |
| POST | `/v1/auth/change-password` | ✓ | new salt/verifier + re-wrapped SAME vault key |
| DELETE | `/v1/auth/account` | ✓ | wipe account + all data (needs verifier) |
| GET | `/v1/devices` | ✓ | list devices |
| POST | `/v1/devices/{id}/revoke` | ✓ | unlink a device (data stays) |
| GET | `/v1/sync/cursor` | ✓ | latest event id (for initial sync) |
| GET | `/v1/sync/pull?cursor=&types=` | ✓ | incremental pull, 500 items/page, includes tombstones |
| POST | `/v1/sync/push` | ✓ | push ≤200 items with optimistic concurrency |
| GET | `/v1/sync/export` | ✓ | full dump for backup/migration |

Push semantics: each item carries `base_version` (0 = create). The server
accepts the write only if the server version still equals `base_version`;
otherwise the response carries `status: "conflict"` plus the current server
item, and the client merges and retries. Deletes are tombstones
(`deleted: true`, no ciphertext) so other devices learn about the removal.

Item types: `bookmark`, `tab`, `setting`, `password`, `autofill`.

## 5. Crypto specification (v1)

```
kdf_salt     = 16 random bytes                      (public, stored on server)
masterKey    = PBKDF2-SHA256(password, kdf_salt, 600000)   [device-only]
authKey      = HKDF-SHA256(masterKey, info="aegis-auth-v1") [device-only]
verifier     = PBKDF2-SHA256(authKey, "aegis-verifier-v1", 100000)  [sent once over TLS]
server stores SHA-256(verifier)                     (DB leak cannot log in)
kek          = HKDF-SHA256(masterKey, info="aegis-kek-v1")  [device-only]
vaultKey     = 32 random bytes                      [device-only, generated once]
wrapped      = AES-256-GCM(kek, vaultKey)           (stored on server)
item blob    = AES-256-GCM(vaultKey, plaintext, aad = "type|key|version")
```

- Changing the password re-wraps the SAME vault key → other devices keep their data.
- AAD binding means a ciphertext moved to another item/version fails to decrypt.
- Free-tier note: server-side Argon2id is impossible (10 ms CPU cap), so the
  slow KDF work is deliberately client-side. Upgrading `kdf.algo` to Argon2id
  later only needs a client change + KDF-param versioning, both already modeled.
- **Password loss = data loss.** There is no reset path by design. If you want
  recovery, add a printable recovery key that wraps a copy of the vault key
  (roadmap item in the blueprint).

## 6. Threat model (honest)

| Attacker | Outcome |
|---|---|
| Cloudflare reads D1 at rest | sees ciphertext, salts, wrapped keys — nothing usable |
| DB dumped (SQL injection, leak) | same as above; tokens stored as SHA-256; cannot log in or decrypt |
| Full server compromise (running worker) | attacker can read traffic to the server; still cannot decrypt items (vault key never transmitted); can denial-of-service you |
| Malicious insider at Cloudflare | same as full server compromise |
| Your devices compromised | game over (true for every browser) |
| Metadata analysis | possible: item counts, sizes, timing, device names. Mitigate by batching pushes and using neutral device names if you care |

## 7. Free-tier budget (approximate)

| Resource | Free allowance | Aegis usage (1 user, 3 devices) |
|---|---|---|
| Worker requests | 100k/day | sync every 5 min/device ≈ 900/day |
| D1 rows read | 5M/day | pull 500 items ≈ 500 reads → ~1.5k/day |
| D1 rows written | 100k/day | pushes + tokens ≈ few hundred/day |
| D1 storage | 5 GB | bookmarks/tabs/passwords are tiny; ≪ 100 MB |

Real-time tab push (WebSockets) would need Durable Objects (Workers Paid,
$5/mo) — the current design polls instead, which fits the free tier.
