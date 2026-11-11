# PROJECT STATE — AEGIS-BROWSER (single source of truth)

> **READ THIS FIRST in every new session (human, agent, or cron watchdog).**
> Everything you need to resume work is in this file. If memory and this file disagree,
> trust the file, then verify against GitHub API. Update this file + commit + push after
> EVERY working pass so a context loss or sandbox recycle never loses state.
>
> Last updated: **2026-11-11 07:20 UTC (12:20 PKT)** — by main session.

---

## 1. Hard facts

| Item | Value |
|---|---|
| GitHub repo | `abdul-mateen-14/AEGIS-BROWSER` (branch `main`) |
| Chromium pin | `156.0.8078.79` — every dispatch MUST carry `chromium_version: 156.0.8078.79` |
| APK dispatch extra | `build_target: chrome_public_apk`, `ref: main` (only `ref=main` works; full-sha refs are rejected 422) |
| Build workflows | `.github/workflows/build-aegis-apk.yml`, `build-aegis-linux.yml`, `build-aegis-windows.yml` |
| Deploy workflows | `deploy-landing.yml`, `deploy-sync.yml` |
| aegis-sync backend | Cloudflare Worker `aegis-sync.abdulmateen14.workers.dev` (D1 + KV, routes `/v1/auth/*`, `/v1/devices*`, `/v1/sync/{cursor,pull,push,export}`) |
| Secrets (NEVER print) | `/home/z/my-project/.secrets/` → `gh_token`, `cf_token`, `cf_account_id` |
| Local mirror | `/home/z/my-project/download/aegis-repo` |
| Helper scripts | `/home/z/my-project/scripts/` — key ones: `status_all_lanes.py`, `run_verdict.py <run_id>`, `dispatch_flavor.py <apk\|linux\|windows>`, `patch_lint_includes.py`, `validate_patches.py`, `apply_test_108.py`, `check_progress_58_51.py`, `smoke_worker_username.py` |
| Patch chain | `aegis/patches/*.patch` (41 files, applied alphabetically: 010 → 110) |
| Plans in repo | `docs/DEPLOYMENT-PLAN.md` (program-wide) · `docs/ANDROID-DEPLOYMENT-PLAN.md` (Android lane) · this file |
| No `gh` CLI | Use python3 + urllib with the GH token for all API calls |
| Design specs | outside repo: `/home/z/my-project/download/aegis-design/` (mobile + desktop PDFs, HTML mockups, `DEGOOGLE-IDENTITY-CAPSULES-PLAN.md` = P1–P6 anchor file) |

## 2. Iron rules (NEVER break)

1. **NEVER cancel a GitHub Actions run.** Only the user cancels runs (past exceptions: #32-stale, #59/#60/#61 — all user-initiated).
2. **NEVER delete or publish `banking-*` draft releases** (they hold the build-tree bank volumes). NEVER publish feature draft releases unless the user says so.
3. **NEVER print/echo/log token contents.** Read them inside scripts only.
4. **Dispatch discipline:** use `scripts/dispatch_flavor.py <flavor>` (it enforces the 30-minute lane rule: never dispatch a lane <30 min after its previous run *started*; reschedule instead). Always on `ref=main`, always with `chromium_version`.
5. **Lane verdict protocol** (after a run concludes):
   - GREEN **with artifact** → lane delivery done for this head. No successor needed.
   - GREEN **cap-bank only** (no artifact; "Collect artifacts" step skipped) → dispatch successor on same/current HEAD.
   - RED → **fix-forward**: extract exact failing file+error from run log, fix in `aegis/patches/`, validate (`patch_lint_includes.py` + `validate_patches.py` + `apply_test_108.py` for patch-108-surface changes), commit, push, dispatch successor. **Before fixing, always re-list recent runs first** — a watchdog cron may already have dispatched the successor (avoid double-fixing).
   - Infra flake (network, runner death, OOM-runner) → re-dispatch once, do not touch patches.
6. **Before fixing/duplicating work:** list crons (`cron` tool) and recent runs — another session or watchdog may have already acted.
7. **Docs pushes are bank-safe.** `aegis/patches/*`, `ci/*`, branding, and workflow yml changes trigger bank invalidation by design — never `git add -A`; add explicit files only.
8. **UA rule:** the `Chrome/` token in the User-Agent stays forever (site-compat; Edge/Brave do the same). User-visible strings must say Aegis.

## 3. Cold-start checklist (fresh session / sandbox recycle)

```bash
cd /home/z/my-project
python3 scripts/status_all_lanes.py        # last 4 runs per lane: status/conclusion/head/id
git -C download/aegis-repo log --oneline -5 && git -C download/aegis-repo status -sb
# check armed watchdog crons via cron tool (list) — they may already handle everything
# read §4 lane status + §10 next actions below, then continue the queue
```

Verdict detail for one run: `python3 scripts/run_verdict.py <run_id>` (prints step-level
bank/artifact outcome). Run logs: `scripts/job_<job_id>.log` when saved, else fetch via API.

## 4. Lane status — snapshot 2026-11-11 07:20Z

| Lane | Latest run | State | Head | Meaning |
|---|---|---|---|---|
| **APK** | #62 id=42974545503 (started 06:44:27Z) | **in_progress** | `e8d86ce` | Real next APK delivery: icons v2 + home/dock fixes + patch 110 local password manager + all aegis_id crypto fixes. #58 (c70a066) was GREEN → draft r58 (318.9 MB) already banked as fallback. |
| **Linux** | #52 id=42972326181 (started 06:25:30Z) | **in_progress** | `e8d86ce` | Fix-forward for #51 RED (aegis_id_crypto CHECK_GT missing include). First Linux compile of patches 108/110 surface. |
| **Windows** | #32 id=42855429095 (GREEN 2026-11-10 16:31Z) | idle, cap-bank | `7cdd37b` | #32 banked (artifacts skipped) → latest Windows **artifact** is draft r31. Next Windows run (#33) = first artifact build with username+avatar patch 108 v2 + patch 110. Gated on Linux #52 GREEN (cron 523439). |

Armed watchdog crons (verify with `cron list`, re-arm if missing):

| Job | Fires (Asia/Shanghai) | Does |
|---|---|---|
| 523439 | 2026-11-11 16:50 CST (= 08:50Z) | `run_verdict.py 42972326181`. In-progress → re-arm +40 min. GREEN → dispatch **Windows r33** then **Linux #53** (both pick up `1d7265d` username+avatar). RED → fix-forward Linux only. |
| 523412 | 2026-11-11 18:20 CST (= 10:20Z) | `run_verdict.py 42974545503`. In-progress → re-arm +45 min. GREEN → verify draft r62 asset exists, report. RED → fix-forward APK + dispatch. |

**Note:** both in-flight runs (#52, #62) checked out `e8d86ce` — one commit BEFORE `1d7265d`
(username + avatar desktop work). They cannot fail on avatar code. Linux #53 / Windows #33
are the first runs that validate `1d7265d`.

## 5. Deliverables ledger (verified via API 2026-11-11 07:55Z)

Published releases (user decides these; never delete unprompted):
- `linux-156.0.8078.79-r45` (.deb 110.5 MB + .zip 160.0 MB) — **best published Linux**
- `linux-...-r31`, `linux-...-r24` (older)
- `build-156.0.8078.79-r28` (APK 511.3 MB, older fat build)

Draft releases (NEVER publish/delete without user):
- `build-...-r58` APK 318.9 MB (GREEN #58 @ c70a066; NO icons v2 — superseded by r62 when it lands) — **latest APK artifact draft**
- `windows-...-r31` .exe 116.1 MB + .zip 199.9 MB — **latest/best Windows artifact draft**
- `banking-windows` — bank vols: runs 42764643911 (#30), 42855429095 (#32) — 2.90 GB
- `banking-linux` — bank vols: runs 41870785941 (orphan txt), 42957091996 (#50), 42965777019 (#51) — 3.42 GB
- `banking-android` — bank vols: runs 42797034779, 42848316691 — 4.59 GB

**Cleanup 2026-11-11 07:5xZ (user request):** deleted 6 old/superseded/bugged drafts via
`scripts/delete_old_drafts.py` (guard: never banking-*, never published, never latest
artifact drafts) — `build-...-r57`, `build-...-r43`, `build-...-r29` (fat 511 MB),
`windows-...-r12` (marker-bug era), `linux-...-r29`, `linux-...-r28`. Freed **1.96 GiB**.
Banks byte-identical before/after. Re-run the same script pattern for future purges.

## 6. Commit chain (what each recent commit is)

Oldest → newest (2026-11-10/11 wave):

```
6a56c90  fix(android): compile chrome_java (dead sheetContent, Profile API, clockTick)
1e6f103  feat(aegis-id): desktop Aegis ID account surface replaces GAIA sign-in promo
4639f49  fix(linux): deb packaging InstallerConfig desktop_icon
942db2c  fix(desktop): aegis_id_crypto.cc as_bytes span idiom + base/logging.h
fd17a8a  docs(ui): r57 vs Liquid-Glass-Dock-proposal gap analysis
c70a066  fix: aegis_id_crypto.cc for Chromium 156 APIs          ← head of APK r58
38ec03f  launcher icons v2 (rounded tile, centered mark, safe margins)
9e137ef  regen extra/ product_mark tiles for .deb branding_dir_100/200
c46ebde  fix Aegis home z-order/NTP detection, dock recall, resource stripping
c324b16  Aegis Passwords: local on-device backend on Android (no GMS) = patch 110
22cbbd4  fix aegis_id_manager.cc + aegis_id_dialog.cc for Chromium 156 (run #49)
61f083c  fix manager/dialog 156 API drift + DialogDelegateView passkey (run #49)
8ff19f0  fix patch 110: restore missing closing quote on built_in_backend include
e8d86ce  fix aegis_id_crypto CHECK_GT: include base/check_op.h (run #51 error)  ← head of #52/#62
1d7265d  Aegis ID username field + E2E profile avatar via sync (patch 108 v2)  ← main HEAD
```

## 7. Feature roadmap (user requests P1–P6)

| ID | Feature | Status | Where |
|---|---|---|---|
| P3 | Tab marks de-Chrome (Android + desktop) | **DONE** | earlier wave (logo asset swap, Android tab-card mark; desktop had no Chrome literal) |
| P4 | Aegis ID username | **DONE** (backend + desktop client) | worker `/v1/auth/register` `username` (3–24 `a-z0-9_-`, unique, 409 `username_taken`, live in D1) + patch 108 v2 (`1d7265d`). **Left: Android-side username field** (AegisIdManager.java / AegisEcosystemSettings.java, patch 087 surface) |
| P5 | Aegis ID avatar (upload→scale→use) | **DONE desktop client**, worker needs nothing | patch 108 v2: file picker → SkCodec decode → skia resize fit 256 → PNG → AesGcmSeal(vault key) → push item_type=setting item_key=profile/avatar; pull path decrypts+caches in `aegis_id_state.json`. **Left: Android-side avatar** |
| P6 | Passwords sync via aegis-sync | **Backend ready, client NOT started** | worker already allows `item_type=password` (allowed: bookmark/tab/setting/password/autofill; versioned push/pull/cursor/export, tombstones, 409 conflict). **Left = patch 114**: desktop LoginDatabase → E2E encrypt → push/pull + settings toggle; then Android counterpart. Next authoring session. |
| P1 | Total de-Google identity (paths, version page, installer, strings) | **NOT STARTED** — patch 112 | anchors in `download/aegis-design/DEGOOGLE-IDENTITY-CAPSULES-PLAN.md` §P1 (chromium_strings.grd:288-303/509-522/1185-1191, chrome_constants.cc:34-53, installer linux files, version_ui, components_chromium_strings.grd; profile dir → `~/.config/aegis-browser`; **UA `Chrome/` token DO NOT CHANGE**) |
| P2 | Capsules = native tab groups (Edge-style) | **NOT STARTED** — patch 113, ship after 112 | anchors in same file §P2 (TabStripModel.h:547/684/693/756/713, TabStripController.h:104/108/116/124/162-184, TabGroupModel.h:42/47/57, tab_group_color.h:39-51 (9 colors), tab_group_editor_bubble_view.h:44/489, saved_tab_groups, tab_context_menu_command.h:24-30; NO TabGroupController at 156; tab_strip_action_container is Glic-only → group UI is ours) |

## 8. Fix-forward log (2026-11-11 wave — most recent first)

| Run | Error | Fix commit | Lesson |
|---|---|---|---|
| Linux #51 (8ff19f0) | `aegis_id_crypto.cc` CHECK_GT: `base/check_op.h` not included | `e8d86ce` | CHECK macros need check_op.h, not just logging.h |
| Linux #49/#50 | aegis_id manager/dialog 156 API drift; DialogDelegateView passkey | `22cbbd4`, `61f083c` | verify every 156 API against pinned tag sources before patching |
| Patch 110 | missing closing quote on `built_in_backend` include | `8ff19f0` | include-quote lint = `patch_lint_includes.py` (now green across all 41 patches) |
| APK #58 | — GREEN (draft r58) | — | first APK with patch 110 compiled |
| Windows #32 | — cap-bank GREEN, no artifact | — | Windows next run must be artifact run (cron 523439 gates it on Linux #52) |

Historical (older waves, summarized — details in git log): marker-bug staleness
(gn-gen overwrote `.aegis_head` → 094-098 never compiled into r31/r12; fixed 7425d9e +
`invalidation_extra.txt`), drop_tree half-wipe (fixed 0746e88), bank volume OOM
(PART_MB=700), 093 duplicate-field + Profile API, 080 Supplier/isLoaded, 087 JSONObject.

**NOTE — Actions history cleared 2026-11-11 ~07:40Z (user request):** all 46
completed/cancelled/failed runs deleted via `scripts/clean_workflow_runs.py`; only live
runs remain. The Actions list will look almost empty — that is normal. Run-level history
is NOT evidence of anything; drafts + banks (releases) are the real ledger. If you need
old run logs, they are gone — rely on this file and git log instead. Re-run the same
script any time the list gets cluttered (never deletes live runs, never touches releases).

## 9. Known traps (do not rediscover these)

1. **Cron one_time format:** pure-digit expr is parsed as epoch-millis → year 58814. ALWAYS
   `"expr": "yyyy-MM-dd HH:mm:ss", "tz": "Asia/Shanghai"`. Server validates in +08.
2. **CF API quirks:** account-scoped `cfat_` token works on `/accounts/{id}/workers/...` but
   `/user/tokens/verify` 401s (expected). Worker download = multipart/form-data, parse manually.
   Worker source recovered from sourcemap `sourcesContent` → local `scripts/aegis-sync-worker/index.js.js`.
   CF Browser Integrity Check 403/1010s bare-UA probes → send browser UA from scripts.
3. **GitHub API:** `workflow_dispatch` requires `ref=main` (full-sha 422s). Bank uploads:
   never single >1 GB volume (curl OOM) — volume split stays at PART_MB=700.
4. **Patches:** alphabetically applied; hunk counts are asserted by validators; never edit
   patch by hand without `apply_test_108.py` (real `git apply --check` vs 3 upstream 156 files).
   Patch 108 is regenerated programmatically by `scripts/build_patch108.py` from
   `download/aegis-p108-src/` — do not hand-edit the .patch file.
5. **Backend contract (aegis-sync):** register body may carry optional `username`
   (3–24 `a-z0-9_-`, 409 `username_taken`); login reply includes `user.username`.
   Push shape `{items:[{item_type,item_key,ciphertext_b64,nonce_b64,base_version,deleted}]}`
   → `{results:[{status:ok|conflict,version}]}`; pull returns `{items:[...],next_cursor}`.
6. **Aegis ID crypto chain** (byte-exact ports in patch 087/108): PBKDF2-SHA256 600k →
   HKDF auth key → PBKDF2 verifier / KEK → AES-256-GCM vault. Token/vault keys stay
   memory-only; state file `aegis_id_state.json` carries email/username/avatar cache only.
7. **Timing:** APK runs ~1.5–5 h to verdict; Linux full ~4–6 h with cap-bank windows;
   Windows ~5–6 h. Don't extrapolate ETAs from one window (runner variance up to 2.3×).

## 10. Next actions queue (in order)

1. **[cron 523439 armed]** Linux #52 verdict → GREEN: dispatch Windows r33 + Linux #53
   (both pick up `1d7265d` = first username+avatar validation). RED: fix-forward Linux.
2. **[cron 523412 armed]** APK #62 verdict → GREEN: verify draft r62 asset, report delivery.
   RED: fix-forward APK.
3. **After 1+2 settle:** author **patch 114 (P6 client)** — desktop LoginDatabase →
   E2E encrypt → push/pull via `/v1/sync/*` + settings toggle. Reuse avatar's
   setting-item/versioned/conflict patterns from patch 108 v2.
4. Author **patch 112 (P1 de-Google identity)** from ready anchors, then **patch 113 (P2
   Capsules)** — dispatch as one desktop wave after Linux #53 GREEN (per user's
   one-wave-per-lane preference).
5. **Android parity pass** (small patch 115): username field + avatar in Android Aegis ID
   (AegisIdManager.java / AegisEcosystemSettings.java), riding the next APK wave.
6. When user asks: publish chosen drafts (user decides which; never unprompted).

---

*Maintenance protocol: after every pass, update §4 snapshot, §5 ledger if releases changed,
§7 roadmap statuses, §8 fix log, §10 queue — then `git add docs/PROJECT-STATE.md docs/DEPLOYMENT-PLAN.md && git commit && git push`.*
