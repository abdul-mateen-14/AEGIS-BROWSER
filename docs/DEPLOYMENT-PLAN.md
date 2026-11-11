# DEPLOYMENT PLAN — AEGIS-BROWSER (revised 2026-11-11)

> This replaces the original build plan. The original assumed near-linear build waves;
> reality produced an incremental fix-forward pipeline (first-compile errors on Chromium 156
> APIs, bank restore bugs, cap-bank windows). This document states what is actually done,
> what is left, and the exact execution order. Companion ledger: `docs/PROJECT-STATE.md`.

## 1. What "shipping Aegis" consists of (5 deployable pieces)

| # | Piece | Vehicle | Status |
|---|---|---|---|
| 1 | Android APK | `build-aegis-apk.yml` → draft release `build-...-rNN` (arm64) | r57/r58 drafts live; **r62 in flight** (best to date) |
| 2 | Linux desktop | `build-aegis-linux.yml` → draft `linux-...-rNN` (.deb/.zip) | **r45 published**; newer drafts r28/r29 stale; #52 validating 108/110 |
| 3 | Windows desktop | `build-aegis-windows.yml` → draft `windows-...-rNN` (.exe/.zip) | r31 draft best; #32 cap-banked; #33 pending Linux GREEN |
| 4 | aegis-sync backend | Cloudflare Worker + D1 + KV (`deploy-sync.yml`) | **LIVE with username + password item types** |
| 5 | Landing / identity | `landing/`, `deploy-landing.yml` | live; strings still contain Chromium references (P1 pending) |

## 2. Original plan vs what actually happened

| Original assumption | Reality | Consequence |
|---|---|---|
| One build wave per patch batch, artifacts on first pass | First compile of any patch surface hits Chromium-156 API drift → RED | Fix-forward loop is the normal loop; every RED = 1 fix commit + 1 successor run |
| Build trees survive restarts trivially | Bank/restore needed: volumes, invalidation markers, drop-tree bugs | banking-* drafts hold multi-700MB volumes; 3 restore bugs found+fixed (marker overwrite, half-wipe, >1GB OOM) |
| Desktop identity work independent of Android | Shared patches (108/110) compile first on Linux lane | Linux is now the **gate** for desktop features; Windows waits for Linux GREEN |
| Feature plan linear (P1→P6) | P3/P4/P5 done first (smaller, unblock backend UX); P1/P2 deferred | Actual order: P3 → P4 → P5 → r-wave rebuilds → P6 → P1 → P2 |
| Artifacts ≈ releases | Everything stays DRAFT until user publishes | Only linux r45/r31/r24 + APK r28 are published; user decides publishing |

## 3. Where we are (2026-11-11)

**Done:**
- 41-patch chain (010–110) lints green (`patch_lint_includes.py`, `validate_patches.py`)
- Android: icons v2, Aegis home/dock fixes, local on-device password manager (patch 110, no GMS), Aegis ID sign-in (087), tab marks de-Chromed (P3)
- Desktop: Aegis ID account surface (108), username field + E2E avatar sync (108 v2, `1d7265d`), manager/dialog 156 fixes
- Backend: username live (D1 unique, 409 contract), `password` item_type allowed, versioned sync engine with tombstones + conflict
- Linux lane: best artifact r45 published; Windows r31 draft

**In flight (armed + automatic):**
- Linux #52 (`e8d86ce`) — validates 108/110 desktop compile; cron 523439 → on GREEN dispatches Windows r33 + Linux #53 (first `1d7265d` username+avatar validation)
- APK #62 (`e8d86ce`) — icons v2 + dock fixes + patch 110 delivery; cron 523412 sweeps verdict

**Left (in order):**
1. Linux #52 verdict → Windows r33 + Linux #53 (automatic via cron)
2. APK r62 verdict → delivery report (automatic via cron)
3. **Patch 114 (P6 client)**: desktop LoginDatabase → E2E encrypt → push/pull + settings toggle
4. **Patch 112 (P1 de-Google identity)** + **patch 113 (P2 Capsules)** → one desktop wave
5. **Patch 115 (Android parity)**: username + avatar in Android Aegis ID UI
6. Publish policy execution (user picks which drafts go public)

## 4. Lane plans

### Android (APK lane)
- **Now:** r62 lands → report (icons v2 + home/dock + real local password manager).
- **Next wave:** patch 115 (Android username + avatar via existing 087 AegisIdManager + AegisSyncClient — same worker endpoints; avatar = setting item `profile/avatar`, same as desktop) + any 114 Android password-sync counterpart.
- **Then:** one dispatch per wave; APK lane is independent of desktop verdicts (own concurrency group).
- Risk: first javac of new surfaces historically surfaces 1–3 deterministic errors → fix-forward as usual.

### Desktop (Linux gate + Windows follower)
- **Linux = gate lane** (compiles new desktop patches first, ~4–6 h runs, cap-bank windows).
- **Windows follows Linux GREEN** (never dispatch Windows onto an unvalidated desktop head).
- Next validation: #52 (108/110), then #53 (108 v2 username/avatar).
- After GREEN: author 112 (identity sweep — installer/constants/strings/version page/profile dir) → 113 (Capsules on native tab groups) → dispatch as **one wave** per user directive.
- Risk: 112 touches installer + constants + strings (bank invalidates by design — fine); binary rename must stay consistent installer↔constants↔desktop Exec symlink. 113 is presentation layer over real tab groups; editor bubble + 9-color palette reused, no TabGroupController at 156.

### Backend (aegis-sync)
- Already deployed with everything P4/P5/P6 need. No redeploy planned.
- Optional hardening later: rate limits on /v1/sync/push, prune policy review, export encryption audit.

## 5. Release policy

1. Every artifact lands as a **draft** (`<flavor>-156.0.8078.79-rNN`).
2. Drafts are never deleted; superseded drafts stay for history.
3. Publishing is **user-decided only**. Suggested publish order when asked: linux r53+ (post-108 v2) → windows r33 → apk r62/r64+.
4. banking-* drafts are infrastructure: touch only to add volumes.
5. Landing page + version strings follow patch 112; landing re-deploy after 112 merges.

## 6. Verification protocol (before calling anything "done")

- **Desktop artifact:** install/extract → version page shows Aegis identity + correct rev → patch-095 credits → Aegis Search default (098) → Aegis ID sign-in/register with username → avatar pick→display → local passwords UI (110) → CDP smoke script pattern (`scripts/cdp_verify_r31*.py` reusable).
- **APK:** install arm64 → launcher icon v2 → Aegis home/dock behavior → Settings → Aegis Ecosystem → Aegis ID sign-in → Aegis Passwords local UI → tab marks.
- **Backend:** `scripts/smoke_worker_username.py` (register/login/dup/cleanup) green; health endpoint 200.
- **Patches:** `patch_lint_includes.py` + `validate_patches.py` (+ `apply_test_108.py` for 108-surface) before every push.

## 7. Milestones

| M | Definition | Depends on | ETA (est.) |
|---|---|---|---|
| M1 | Linux + Windows artifacts with 108 v2 (username+avatar) + 110 | #52 GREEN, #33/#53 | in motion |
| M2 | APK r62 delivered | #62 verdict | in motion |
| M3 | Passwords sync end-to-end desktop (114) + settings toggle | M1 GREEN | next authoring session |
| M4 | De-Google identity sweep shipped (112) | M1 GREEN | after 114 |
| M5 | Capsules (113) + Android parity (115) | M4 | after 112 |
| M6 | User-selected drafts published + landing refresh | user decision | on request |

## 8. Risk register (top items)

| Risk | Mitigation |
|---|---|
| New desktop patch surface compiles RED on 156 | fix-forward loop is standard; validators pre-push; Linux gates Windows |
| Bank invalidation from wide sweeps (112) | invalidation_extra.txt mechanism; cap-bank windows resume cleanly |
| Token expiry (gh PAT ~2026-12-07) | user rotates; `.secrets/` only; never print |
| Cron service misses a one_time fire | verdict sweeps re-arm themselves +40/45 min; cold-start checklist finds gaps |
| Context loss mid-fix | this file + PROJECT-STATE.md updated after EVERY pass |
| Double-dispatch race between sessions/watchdogs | 30-min lane rule inside dispatch_flavor.py + "list recent runs first" protocol |

## 9. Non-goals / frozen decisions

- UA `Chrome/` token stays (compat; Edge/Brave precedent).
- No GMS dependency on Android (patch 110 is fully local; sync is E2E via own worker).
- No run cancels by agents; no draft publishes by agents; no token printing.
- Patches stay byte-anchored at 156.0.8078.79; rebase only on explicit user decision to bump Chromium.
