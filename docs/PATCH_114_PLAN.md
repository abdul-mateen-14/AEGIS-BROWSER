# PATCH 114 — Aegis Passwords Sync (desktop client, P6)

> Status: **authored 2026-11-11** (source staged, patch generated, lint green).
> Not yet committed to `aegis/patches/` — rides the next desktop wave with
> patches 112/113 so the bank invalidation happens once.
> This plan is the complete build instruction; a cold session can execute it.

## 1. Goal

Desktop Aegis Passwords (patch 110's local LoginDatabase backend) gains E2E
encrypted sync through the aegis-sync worker:

- Local add/update/remove of a password → sealed and pushed as
  `item_type=password` (worker already allows it — zero backend work).
- Remote items pulled and applied into the profile LoginDatabase.
- Runs in the background per-profile after Aegis ID sign-in; no dialog needed.
- User-facing name stays **Aegis Passwords**; worker contract unchanged.

## 2. Security model (same posture as patch 108 v2)

- Each credential payload is sealed with the **vault key**
  (AES-256-GCM, `aegis_id_crypto.h`), the same per-account key that protects
  the avatar. The server only ever sees ciphertext + hashed item keys.
- Session tokens and vault key stay **memory-only**. After a browser restart
  the engine idles until the user signs in again (identical to avatar sync).
- `item_key` = `"p/" + hex(SHA256(signon_realm + "\x00" + utf8(username)))`
  (20 hex chars) — no realm/username plaintext reaches the server, only a
  salted (NUL-separated) hash. Version counters and cursor are not secrets.
- Engine state file `aegis_password_sync.json` (profile dir) holds ONLY:
  `{"enabled": bool, "cursor": int, "versions": {item_key: int}}`.
  No credentials, no tokens. Separate file = no writer race with
  `aegis_id_state.json`.

## 3. Verified Chromium 156.0.8078.79 anchors (fetched from the pin)

These were verified by fetching the exact 156.0.8078.79 sources
(`scripts/chromium_file.py` cache `~/chromium-cache/156.0.8078.79/`) — NOT from
memory. This is the anti-§8-lesson step; re-verify before re-basing.

| API | Exact 156 signature | File |
|---|---|---|
| Store access | `ProfilePasswordStoreFactory::GetForProfile(Profile*, ServiceAccessType)` → `scoped_refptr<password_manager::PasswordStoreInterface>` | `chrome/browser/password_manager/factories/profile_password_store_factory.h` |
| Observer | `PasswordStoreInterface::Observer : base::CheckedObserver`; `virtual void OnLoginsChanged(PasswordStoreInterface* store, const PasswordStoreChangeList& changes) = 0` | `components/password_manager/core/browser/password_store/password_store_interface.h` |
| Change | `PasswordStoreChange::Type {ADD, UPDATE, REMOVE}`; `const StoredCredential& credential()` | `.../password_store/password_store_change.h` |
| Credential | `StoredCredential{signon_realm, url, username_value (u16string), password_value (PasswordString), date_created, date_password_modified, in_store}`; matcher `AreStoredCredentialUniqueKeysEqual(l, r)` | `.../password_store/stored_credential.h` |
| Secret read/write | `PasswordString(std::u16string&&)`, `.value() → std::u16string` | `components/password_manager/core/browser/password_string.h` |
| Mutations | `AddLogin(StoredCredential, base::OnceClosure = base::DoNothing())`; `UpdateLogin(StoredCredential, ...)`; `RemoveLogin(const base::Location&, const StoredCredential&)` | `.../password_store/password_store.h` |
| Read all | `GetAutofillableLogins(base::WeakPtr<PasswordStoreConsumer>)` → `OnGetPasswordStoreResultsOrErrorFrom(PasswordStoreInterface*, base::expected<std::vector<StoredCredential>, PasswordStoreBackendError>)` | `.../password_store_consumer.h` |
| Profile death | `ProfileObserver : base::CheckedObserver`; `OnProfileWillBeDestroyed(Profile*)`; `Profile::AddObserver/RemoveObserver` | `chrome/browser/profiles/profile_observer.h` |
| GN target owning the factory | `source_set("factories")` at `//chrome/browser/password_manager/factories:factories` | `chrome/browser/password_manager/factories/BUILD.gn` |

Caveats taken from the headers themselves:

- Observer docs: *"Don't rely on changes containing REMOVED entries."* The
  engine therefore also reconciles on full pulls (tombstones pushed on
  REMOVE when present; stray remote deletes discovered by periodic pull).
- `RemoveLogin` needs a `base::Location` — engine uses `FROM_HERE`-derived
  `base::Location::Current()` captured at the call site.
- Worker pull has **no server-side item_type filter** (cursor-paged, all
  types) — the engine filters `item_type == "password"` client-side.

## 4. Architecture

```
AegisIdManager (patch 108 v2, dialog-owned)
  OnVaultKeyUnwrapped ──► AegisPasswordSync::ForProfile(profile)
                              ->OnSessionUnlocked(vault_key, access, refresh)
  SignOut ───────────────► ->OnSessionEnded()
                                   │ (owns copies: tokens + vault key,
                                   │  its own SimpleURLLoader + refresh)
AegisPasswordSync (NEW, static per-profile map + ProfileObserver)
  ├─ observes PasswordStoreInterface::Observer → push chain
  ├─ pull chain (on unlock, after pushes, 10-min timer)
  └─ state file aegis_password_sync.json
```

- Ownership: static `std::map<raw_ptr<Profile>, unique_ptr>` guarded by the
  fact that all access is UI-thread; `OnProfileWillBeDestroyed` erases the
  entry and `RemoveObserver`s from both the profile and the store. No new
  KeyedService factory → zero BUILD.gn surgery beyond the aegis_id source_set.
- Network: engine clones the manager's proven fetch plumbing (single-flight
  `SimpleURLLoader`, 401 → one refresh → retry). Deliberate duplication
  (~90 lines) to keep patch 108's manager lifetime untouched.
- Loop-back protection: engine keeps `last_applied_` (item_key → plaintext
  payload). `OnLoginsChanged` skips credentials whose
  (signon_realm, username_value, password) matches the payload it itself just
  applied — airtight against async observer notifications.

## 5. Wire encoding

Push body (worker v1 contract, §9.5 of PROJECT-STATE):

```json
{"items":[{"item_type":"password",
           "item_key":"p/a3f...c1",
           "ciphertext_b64":"…","nonce_b64":"…",
           "base_version":N,"deleted":false}]}
```

Plaintext (inside the GCM seal), compact JSON:

```json
{"u":"<username utf8>","p":"<password utf8>","r":"<signon_realm>",
 "url":"<url.spec()>","dc":"<ms since epoch as string>",
 "dm":"<ms since epoch as string>"}
```

- Push requests carry **one item each** (single-flight POST, queue drains
  sequentially — same shape as the avatar push, smallest conflict surface).
- `conflict` result → engine records the server version into `versions`
  and schedules a pull (server wins this round; the next local edit pushes
  with the fresh `base_version`). No silent data loss either way.
- Timestamps `dc`/`dm` travel as **strings** (milliseconds since epoch do
  not fit `int`, and `base::Value` has no int64 JSON type); the reader
  parses them with `base::StringToInt64`.
- Tombstones: `deleted:true` with empty ciphertext; local `versions` entry
  retained so we never reuse an old `base_version`.

## 6. Files, hunks, BUILD.gn

New files (staged at `download/aegis-p114-src/`, generated by
`scripts/gen_114.py` — same pattern as build_patch108.py; never hand-edit
the .patch):

| Path | New file | Content |
|---|---|---|
| `chrome/browser/ui/views/profiles/aegis_password_sync.h` | yes | class, 232 lines |
| `chrome/browser/ui/views/profiles/aegis_password_sync.cc` | yes | engine, 916 lines |

Modified files:

| Path | Hunk | Note |
|---|---|---|
| `chrome/browser/ui/views/profiles/BUILD.gn` | add `aegis_password_sync.cc`/`.h` to `source_set("aegis_id")` + deps `//components/keyed_service/core`, `//components/password_manager/core/browser`, `//chrome/browser/password_manager/factories:factories` | hunks derived against POST-108 content by the generator |
| `chrome/browser/ui/views/profiles/aegis_id_manager.cc` | `#include "aegis_password_sync.h"` + 5-line hook in `OnVaultKeyUnwrapped` (engine gets the session copy) + 1-line hook in `OnSignOutReply` | hunks derived from the staged manager (must re-run gen_114.py if patch 108's manager sections change) |

Known CI risk (cheap failure mode): if GN reports a dep cycle
(`factories → browser_process → profiles → aegis_id → factories`), fallback =
drop the `factories` dep and obtain the store through a
function-pointer service getter registered by a one-line addition to
`password_store_backend_factory.cc` (patch 110 surface). Documented here so a
fix-forward is mechanical.

## 7. Settings toggle

- Engine API: `SetEnabled(bool)` — persisted in `aegis_password_sync.json`;
  disabled = no push, no pull, state kept.
- Desktop UI (this patch): toggle surfaced in the Aegis ID dialog
  (checkbox row under the account status) — small `aegis_id_dialog.cc` hunk,
  same idiom as the dialog's existing rows. If it complicates the wave,
  the engine is still safe to ship: default `enabled=true` after sign-in,
  UI toggle becomes a follow-up hunk in patch 115's wave.
- Android counterpart: same wire contract via `AegisSyncClient.java`
  (patch 115+), engine mirrors `aegis_password_sync` semantics.

## 8. Validation protocol (before it joins a wave)

1. `python3 scripts/gen_114.py` — regenerates the patch from staging
   (BUILD.gn hunks derived programmatically against fetched upstream +
   post-108 content) and self-validates:
   - include lint (same rules as `patch_lint_includes.py`) on the output,
   - **real `git apply --check` against a post-108 mini-tree** (upstream
     BUILD.gn + patch 108's BUILD.gn section + manager new-file section,
     then the full patch 114) — DONE 2026-11-11, green (1220 lines).
2. `python3 scripts/patch_lint_includes.py` after the patch joins
   `aegis/patches/` — full-chain lint green.
3. Wave dispatch = Linux lane first (desktop gate); `ninja` frontier is
   `aegis_password_sync.o` + relinked aegis_id_manager.o — first-compile
   errors possible exactly once, then the file is proven.
4. Flagged anchors (compile-time, cheap to fix-forward): the
   `PasswordStoreConsumer` WeakPtr conversion (subclass-owned factory →
   `WeakPtr<PasswordStoreConsumer>`), `UpdateLogin` matching by
   `primary_key` when set, and the possible GN dep cycle in §6.

## 9. Explicit non-goals (this patch)

- No autofill-provider change (patch 110 owns the backend).
- No account-store sync (Aegis has no Gaia sign-in; profile store only).
- No `Chrome/` UA or LoginDatabase schema changes.
- No multi-vault/second-account handling: one Aegis ID per profile, as today.
