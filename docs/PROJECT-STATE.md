# PROJECT STATE — AEGIS-BROWSER (single source of truth)

> **READ THIS FIRST in every new session (human, agent, or cron watchdog).**
> Everything you need to resume work is in this file. If memory and this file disagree,
> trust the file, then verify against GitHub API. Update this file + commit + push after
> EVERY working pass so a context loss or sandbox recycle never loses state.
>
> Last updated: **2026-11-12 06:1x UTC (11:1x PKT)** — by main session.

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
| Helper scripts | `/home/z/my-project/scripts/` — key ones: `status_all_lanes.py`, `run_verdict.py <run_id>`, `dispatch_flavor.py <apk\|linux\|windows>`, `patch_lint_includes.py`, `validate_patches.py`, `apply_test_108.py`, `check_progress_58_51.py`, `smoke_worker_username.py`, `gen_114.py` (patch 114 generator) |
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

## 4. Lane status — snapshot 2026-11-12 06:1xZ (manual checkpoint, no crons)

**WATCHDOG CRONS DISCONTINUED (user order: "cancel all the cron jobs", 2026-11-11).
FINAL deletion sweep 2026-11-12 00:13–00:16 CST removed SIX jobs: 513824 + 513929 (the
20-min fixed_rate monitors alive since Nov 8 — 513929 alone had fired 249 passes) and
523412, 523593, 524122, 524163 (one_time verdict jobs). The earlier "~13:05Z ALL deleted
(523412/524122/524163)" note was WRONG — all six stayed armed ~11 more hours. LESSON:
the cron list API returns total=0 even while jobs are live (never trust it); a delete is
only proven by a follow-up per-ID check (record updated_at bump / absent next firing).
NONE armed now.** Lane verdicts are MANUAL from here: check on user ping or at session
start with `python3 scripts/run_verdict.py <run_id>`. Dispatch discipline (iron rules
4/5) unchanged — just nobody is watching automatically.

| Lane | Latest run | State | Head | Meaning |
|---|---|---|---|---|
| **APK** | #66 id=43143077453 (started 2026-11-12T02:40:54Z) | **in_progress** (build step, ~3h15m elapsed at 05:55Z) | `35a8e90` | Fix-forward for #65 RED (2 errors — Android-local login-db file-name constants for the un-guarded CreateLoginDatabase, see §8). Resumes the 2.19 GB #65 bank (182,018 edges); only the factory_util TU + dependents recompile. NO watchdog — check manually. |
| **Linux** | #59 id=43150901156 (GREEN 2026-11-12T05:5xZ) | **DONE — artifact** | `da82b21` | FIRST 156-CHAIN LINUX ARTIFACT. Restored #58's 1.61 GB bank (gen 43133968988), only 1,386 dirty edges (patch-108 delta), ninja rc=0 after 52 min, .deb 116 MB. Draft `linux-156.0.8078.79-r59` (.deb 116 MB + portable .zip 167 MB). gfx::PNGCodec fix validated end-to-end. Desktop gate for Windows satisfied. |
| **Windows** | #33 id=43164506963 (dispatched 2026-11-12T05:54:46Z) | **in_progress** | `b8b1ab0` | First artifact build with the full desktop wave (gate: Linux #59 GREEN artifact — satisfied). Restores banking-windows gen 42855429095 (#32, ~1.45 GB). Builds chrome + mini_installer, 280-min cap, streams bank on cap. NO watchdog — check manually. |

**Note:** Linux #59 built `da82b21` (= `e0824f0` + gfx::PNGCodec avatar encode); APK #66
builds `35a8e90` = `e0824f0` + Android-only login-db constants — da82b21 does not touch
Android code. Windows #33 builds `b8b1ab0` (docs-only on top of da82b21) and is the first
desktop run validating `1d7265d` + `2aff576` + `fd981ff` + `c3870e0` + `e0824f0` +
`da82b21` together.

**Verdicts 2026-11-11 18:0xZ:** APK #63 = cap-bank GREEN (ninja rc=1 at 279 min
classified cap, 146,671 edges, 1.96 GB banked) — successor #64 dispatched. Linux #55 =
RED with ONE error: aegis_id_dialog.cc:169 unsafe pointer arithmetic in the avatar PNG
vector ctor (same plugin class as e943d44); fixed by c3870e0 (base::span(ptr,size) +
iterator pair + span.h include), validated (lint + validate + apply_test all OK),
successor #56 dispatched. Both lanes now on `c3870e0`.

**Verdicts 2026-11-12 01:1xZ:** Linux #57 = RED with ONE error — dialog.cc:170:35
"function introduces unsafe buffer manipulation": the c3870e0 `base::span(ptr, size)`
ctor is itself flagged (uncounted-pointer provenance); fixed in e0824f0 with the
sanctioned UNSAFE_TODO vector ctor + compiler_specific.h include (span.h dropped).
APK #64 = RED with 10 errors — ALL in stock password_store_built_in_backend.cc compiled
on Android for the FIRST time (patch 110's BUILD.gn hunk force-includes it; #58/#62/#63
never reached the TU). Stock guards desktop-only enum members (!IS_ANDROID) and prefs;
fix = reconcile six switch guards + wrap two pref uses, generated by
scripts/fix110_backend_guards.py (difflib hunks, desktop+iOS semantics unchanged),
verified by NEW scripts/apply_test_110.py (git apply vs 156 tag tree, 7 files, OK).
Both lanes now on `e0824f0`.

**Verdicts 2026-11-12 02:4xZ:** APK #65 = RED with ONE TU / 2 errors —
password_store_factory_util.cc:48/49 "use of undeclared identifier
kLoginDataForAccountFileName / kLoginDataForProfileFileName": e0824f0 un-guarded
CreateLoginDatabase on Android (built-in backend needs it), but upstream declares+defines
those two constants in password_manager_constants.{h,cc} under `#if !BUILDFLAG(IS_ANDROID)`
only. Fix `35a8e90` = anonymous-namespace internal-linkage Android-only copies inside the
.cc (values mirror upstream: "Login Data" / "Login Data For Account"); factory_util.cc
section regenerated by scripts/fix110_factory_util_consts.py (difflib, with transform
self-check: current-patch + block == new content), apply_test_110 OK (git apply vs 156 tag
tree), validate_patches OK (21 hunks). #66 dispatched id=43143077453. Linux #58 UNAFFECTED
(block is Android-guarded; desktop TU byte-identical).

**Verdicts 2026-11-12 03:5xZ:** Linux #58 = RED at the LINK STAGE (first ever) — all
compile TUs green, `LINK ./chrome` at edge 1318/1329: `undefined symbol:
SkPngEncoder::Encode(SkPixmap const&, SkPngEncoder::Options const&)` referenced by
aegis_id_dialog.cc LoadAndNormalizeAvatar. Root cause: at 156 //skia builds PNG encode
from the RUST backend (skia/BUILD.gn `skia_encode_rust_png_srcs`);
`SkPngEncoder.cpp` (libpng impl of that static) is never compiled, but the header still
declares it → compiles, fails final link. Fix `da82b21` = `gfx::PNGCodec::
EncodeBGRASkBitmap` (//ui/gfx, already a dep, always linked; 156 API returns
`std::optional<std::vector<uint8_t>>` → the UNSAFE_TODO/SkData raw-pointer escape
DELETED) + force `kN32_SkColorType` in DecodeAndFitAvatar (EncodeBGRASkBitmap contract).
Validated (lint OK / validate OK 12 hunks / apply_test_108 OK), diff audit = dialog only.
Linux #59 dispatched id=43150901156.

**Verdicts 2026-11-12 06:1xZ:** Linux #59 = **GREEN ARTIFACT** (first of the 156 chain):
restored #58's 1.61 GB bank in ~1 min, restamp 1,187,780 files, AEGIS-DIRTY edges=1386,
ninja rc=0 after 52 min (280-min budget untouched), BUILD_DONE=1, .deb 115,654,836 B →
artifact `aegis-linux-x64-59` (282.4 MB) + draft `linux-156.0.8078.79-r59` (.deb 116 MB
+ .zip 167 MB). da82b21 gfx::PNGCodec fix validated end-to-end. Windows gate satisfied →
**Windows #33 dispatched id=43164506963 @ b8b1ab0** (05:54:46Z, via dispatch_flavor.py
windows; 30-min rule passed — lane idle since #32). APK #66 still in build step at
05:55Z (~3h15m elapsed, 280-min cap not reached).

## 5. Deliverables ledger (verified via API 2026-11-12 06:1xZ)

Published releases (user decides these; never delete unprompted):
- `linux-156.0.8078.79-r45` (.deb 110.5 MB + .zip 160.0 MB, tag @ `a548d4c`) — **the only published release**

Draft releases (NEVER publish/delete without user):
- `linux-...-r59` .deb 116 MB + .zip 167 MB (GREEN #59 @ da82b21) — **latest Linux artifact draft, first of the 156 chain**
- `build-...-r58` APK 318.9 MB (GREEN #58 @ c70a066; NO icons v2 — superseded by the next APK artifact run (#66) when it lands) — **latest APK artifact draft**
- `windows-...-r31` .exe 116.1 MB + .zip 199.9 MB — **latest/best Windows artifact draft** (r33 building now)
- `banking-windows` — bank vols: runs 42764643911 (#30), 42855429095 (#32) — 2.90 GB; #33 restores 42855429095
- `banking-linux` — bank vols: runs 41870785941 (orphan txt), 43016173134 (#54), 43026595992 (#55, 1.71 GB), 43080734791 (#57, 1.71 GB), **43133968988 (#58, 1.61 GB latest gen — restored by GREEN #59, no newer bank: artifact runs don't bank)**
- `banking-android` — bank vols: runs 42797034779, 42848316691, 42974545503 (#62, 1.89 GB), 43019369532 (#63, 1.96 GB — rotated away by #65), 43073661607 (#64, 2.18 GB) + **43133995506 (#65, 2.19 GB latest gen, 182,018 edges — restores into #66)**; draft holds both latest gens (4.37 GB total)

**Cleanup 2026-11-11 07:5xZ (user request):** deleted 6 old/superseded/bugged drafts via
`scripts/delete_old_drafts.py` (guard: never banking-*, never published, never latest
artifact drafts) — `build-...-r57`, `build-...-r43`, `build-...-r29` (fat 511 MB),
`windows-...-r12` (marker-bug era), `linux-...-r29`, `linux-...-r28`. Freed **1.96 GiB**.
Banks byte-identical before/after. Re-run the same script pattern for future purges.

**Cleanup 2026-11-11 08:1xZ (user):** user deleted 3 old PUBLISHED releases —
`linux-...-r31`, `linux-...-r24`, `build-...-r28` (APK). Watchdog then deleted their 3
orphan git tags (same names, commits `e35a99d`/`e36e494`/`e36e494`). Remaining tag:
`linux-156.0.8078.79-r45` → `a548d4c` (release alive).

**Tagging convention going forward:** exactly one git tag per published release
(`<flavor>-156.0.8078.79-rNN` @ the build's head commit) = provenance of what shipped.
When a release is deleted, delete its tag too (they serve no purpose once the release is
gone and only offer confusing source zips). Draft releases never need tags; published
ones get theirs at publish time.

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
1d7265d  Aegis ID username field + E2E profile avatar via sync (patch 108 v2)
e943d44  fix(patch-108): crypto InfoSpan as_bytes idiom (run #52 error)
2aff576  fix(patch-108): drop //third_party/skia dep — gn gen dup skia_use_dawn (run #53)
fd981ff  fix(patch-108): compile 156 avatar/username code (run #54 errors, 13+2)
c3870e0  fix(patch-108): dialog avatar encode span idiom (run #55 unsafe-buffer)
e0824f0  fix(patch-108/110): dialog UNSAFE_TODO escape (#57) + 110 android guard
         reconciliation (#64, 10 errors)
35a8e90  fix(patch-110): Android-local login-db file-name constants (run #65, 2 errors)
da82b21  fix(patch-108): avatar PNG encode via gfx::PNGCodec (run #58 link error)
b8b1ab0  docs: Linux #58 link-stage verdict + lane snapshot (docs-only)
                                                                          ← main HEAD (Windows #33 builds this)
```

(docs commits interleaved throughout: lane snapshots, ledger refreshes, cron purge,
patch-114 authoring — see git log)

## 7. Feature roadmap (user requests P1–P6)

| ID | Feature | Status | Where |
|---|---|---|---|
| P3 | Tab marks de-Chrome (Android + desktop) | **DONE** | earlier wave (logo asset swap, Android tab-card mark; desktop had no Chrome literal) |
| P4 | Aegis ID username | **DONE** (backend + desktop client) | worker `/v1/auth/register` `username` (3–24 `a-z0-9_-`, unique, 409 `username_taken`, live in D1) + patch 108 v2 (`1d7265d`). **Left: Android-side username field** (AegisIdManager.java / AegisEcosystemSettings.java, patch 087 surface) |
| P5 | Aegis ID avatar (upload→scale→use) | **DONE desktop client**, worker needs nothing | patch 108 v2: file picker → SkCodec decode → skia resize fit 256 → PNG → AesGcmSeal(vault key) → push item_type=setting item_key=profile/avatar; pull path decrypts+caches in `aegis_id_state.json`. **Left: Android-side avatar** |
| P6 | Passwords sync via aegis-sync | **AUTHORED 2026-11-11** (source + plan + patch generated + apply-tested; NOT in chain yet) | patch 114: `docs/PATCH_114_PLAN.md` (full plan + verified 156 anchors); source staged `download/aegis-p114-src/`; generated patch `download/aegis-p114-out/114-aegis-passwords-sync.patch` (1220 lines, include-lint + git-apply green). Joins the next desktop wave with 112/113 (bank invalidates once). Left: Android counterpart. |
| P1 | Total de-Google identity (paths, version page, installer, strings) | **NOT STARTED** — patch 112 | anchors in `download/aegis-design/DEGOOGLE-IDENTITY-CAPSULES-PLAN.md` §P1 (chromium_strings.grd:288-303/509-522/1185-1191, chrome_constants.cc:34-53, installer linux files, version_ui, components_chromium_strings.grd; profile dir → `~/.config/aegis-browser`; **UA `Chrome/` token DO NOT CHANGE**) |
| P2 | Capsules = native tab groups (Edge-style) | **NOT STARTED** — patch 113, ship after 112 | anchors in same file §P2 (TabStripModel.h:547/684/693/756/713, TabStripController.h:104/108/116/124/162-184, TabGroupModel.h:42/47/57, tab_group_color.h:39-51 (9 colors), tab_group_editor_bubble_view.h:44/489, saved_tab_groups, tab_context_menu_command.h:24-30; NO TabGroupController at 156; tab_strip_action_container is Glic-only → group UI is ours) |

## 8. Fix-forward log (2026-11-11 wave — most recent first)

| Run | Error | Fix commit | Lesson |
|---|---|---|---|
| Linux #59 (da82b21) | — **GREEN ARTIFACT** (draft r59): bank resume 1,386 dirty edges, ninja rc=0 after 52 min, .deb 116 MB | — | Bank + surgical invalidation paid off completely: the whole link-stage fix cost was ONE 52-min run instead of a full rebuild. gfx::PNGCodec (//ui/gfx) encode + kN32 contract validated end-to-end on desktop. First 156-chain Linux deliverable |
| Linux #58 (e0824f0) | LINK STAGE (first ever): `[1318/1329] LINK ./chrome` — `undefined symbol: SkPngEncoder::Encode(SkPixmap const&, Options const&)` from LoadAndNormalizeAvatar. ALL compile TUs green (e0824f0 UNSAFE_TODO fix held) — bank carried 182k+ edges to the finish line | `da82b21` | SKIA PNG ENCODE IS RUST AT 156: //skia compiles `skia_encode_rust_png_srcs`, NOT the libpng `SkPngEncoder.cpp` — the header declares the old static but nothing defines it (compile-clean, link-dead). Rule: for SkBitmap→PNG use `gfx::PNGCodec::EncodeBGRASkBitmap` (//ui/gfx, always linked; returns `std::optional<std::vector<uint8_t>>` at 156 → also DELETES the UNSAFE_TODO escape). Force kN32 in DecodeAndFitAvatar for the EncodeBGRASkBitmap contract. A compile-error-free run can still die at link — bank+invalidate made the retry cheap (dialog TU + relink only) |
| APK #65 (e0824f0) | ONE TU / 2 errors: password_store_factory_util.cc:48/49 `kLoginDataForAccountFileName`/`kLoginDataForProfileFileName` undeclared on Android — e0824f0 un-guarded CreateLoginDatabase (built-in backend needs it) but upstream declares+defines these constants in password_manager_constants.{h,cc} ONLY under `#if !BUILDFLAG(IS_ANDROID)`. Bank carried 182,018 edges; only this TU (+dependents) recompiles in #66 | `35a8e90` | UN-GUARDING A FUNCTION EXPOSES ITS WHOLE BODY — inventory every symbol the body uses against the platform you're adding BEFORE dispatch (this one was a one-line grep in the cached 156 password_manager_constants.h). Fix = anon-namespace internal-linkage Android-only copies mirroring upstream values (no edits to stock constants files, no ODR/link impact either platform); section regenerated via scripts/fix110_factory_util_consts.py with a transform self-check (current-patch + block == new content) |
| APK #64 (c3870e0) | 10 errors, ALL stock-code-on-Android: backend.cc 101/102, 139/140, 169/170 `kNeedsSettingsConfirmation`/`kUnrecoverableError` absent (`#if !BUILDFLAG(IS_ANDROID)` in sync_service.h) + 3× `kDeviceManagementError` unhandled on Android + 265/616 `prefs::kClearingUndecryptablePasswords` absent. TU compiled on Android for the FIRST time (patch 110 BUILD.gn force-include; prior runs never reached it) | `e0824f0` | STOCK DESKTOP CODE IS NOT ANDROID-SAFE BY DEFAULT — stock never compiles this file on Android, so its guards were never exercised there. Force-including a desktop file on Android = audit EVERY #if guard inside it (enum members, prefs, helpers) against the cached 156 tag. New harness scripts/apply_test_110.py (mini-tree git apply) now guards patch 110 like apply_test_108 guards 108 |
| Linux #57 (72cb837) | dialog.cc:170:35 `function introduces unsafe buffer manipulation` — the c3870e0 `base::span(static_cast<const uint8_t*>(png->data()), size)` ctor itself flagged: uncounted-pointer provenance (void* cast) | `e0824f0` | span-from-uncounted-pointer is NOT the blessed form in converted dirs — only span-from-.data()-of-known-containers is. SkData has no span accessor → UNSAFE_TODO(std::vector(...)) is the sanctioned escape (//docs/unsafe_buffers.md); include base/compiler_specific.h. depot_tools HTTP 502 from #56 did NOT recur (one-off confirmed) |
| Linux #56 (c3870e0) | NO build error — INFRA: step 9 `install-build-deps.py` died at 42 min on `depot_tools update failed … error: RPC failed; HTTP 502` (GitHub git-server 5xx). Patches never applied (steps 11+ skipped) | — (redispatch #57) | depot_tools self-update inside install-build-deps is the ONLY un-retried git call in the workflow. First infra death in the Linux lane since the API-fix wave; does NOT count against the fix chain. If it repeats: wrap install-build-deps in retry or export DEPOT_TOOLS_UPDATE=0 (workflow change → user OK first) |
| Linux #55 (fd981ff) | dialog.cc:169 `static_cast<const uint8_t*>(png->data()) + png->size()` → `-Werror,-Wunsafe-buffer-usage`. ONE error only; manager.o compiled clean at edge 697 — every fd981ff fix held | `c3870e0` | The `(ptr, ptr+size)` iterator-pair ctor IS flagged — #52's "iterator-pair empirically clean" note was about a different ctor shape, do not trust it. Fix: named `base::span(ptr, size)` (blessed ctor) + vector-from-span-iterators + `base/containers/span.h`. Regenerated via build_patch108.py; diff audit = only the dialog change (no lost historical fixes). Bank resume + surgical invalidation worked: ninja ran 31 min before dying at edge 136,610 |
| Linux #54 (2aff576) | REAL compile errors — gn gen PASSED (2aff576 skia fix proven). crypto.cc:28 `base::make_span` GONE in 156; manager.cc x3 `base::Value::List` gone in 156 (values rewrite: standalone `ListValue`, FindList → `const ListValue*`); SetAvatarImage reply never bound png (PostTaskAndReplyWithResult reply = task return ONLY) + OnAvatarPushBodyReady/OnAvatarDecrypted undeclared; dialog.cc allocPixels(info) void in 156 Skia + GetText() returns u16string_view | `fd981ff` | All verified against cached 156 sources (`scripts/chromium_file.py`). Devices path already used ListValue (compiled #52) = in-file ground truth. crypto.cc now STAGED (extracted 1:1 from patch, roundtrip-checked) and build_patch108.py made idempotent (windowed NEW_DEPS strip, net-delta count). Lesson: the v2 avatar path was written against a fictional API — ALWAYS verify new-API usage against the cached pin before regenerating |
| Linux #53 (e943d44) | gn gen died in seconds: `ERROR at //third_party/skia/gn/skia.gni:51:19: Duplicate build argument declaration` — `skia_use_dawn` already declared at `//skia/features.gni:12`. Chain: profiles/BUILD.gn:492 dep `//third_party/skia` → standalone Skia BUILD.gn:10 → `import gn/skia.gni` → declare_args | `2aff576` | NEW DEP CLASS: NEVER dep on `//third_party/skia` (standalone Skia BUILD.gn is never in the stock chrome graph). `//skia` alone provides all `third_party/skia/include/*` headers. Poison line came from `build_patch108.py` NEW_DEPS during 1d7265d; #52 passed gn gen only because it predated the hunk. Fixed surgically (`scripts/fix108_skia_dep.py`, asserts: unique line + BUILD.gn section + in-hunk range, hunk count 34→33) AND in the generator. Diff audit: exactly 2 lines changed. Watchdog cron 523593 fired at 08:45Z but failed silently (log redirect 401) — dispatched nothing |
| Linux #52 (e8d86ce) | `aegis_id_crypto.cc:25` InfoSpan `reinterpret_cast` → hard `-Werror,-Wunsafe-buffer-usage` (converted dir) at [692/1331], 27 min in | `e943d44` | REPEAT of #47: 942db2c fixed this exact line, the fix was LOST when patch 108 was regenerated from staging (1d7265d). **Lesson: when regenerating a patch from staging, re-check every historical one-line fix against the staged content.** dialog.cc iterator-pair pattern empirically plugin-clean (compiled #49/#50) — left alone |
| Linux #51 (8ff19f0) | `aegis_id_crypto.cc` CHECK_GT: `base/check_op.h` not included | `e8d86ce` | CHECK macros need check_op.h, not just logging.h |
| Linux #49/#50 | aegis_id manager/dialog 156 API drift; DialogDelegateView passkey | `22cbbd4`, `61f083c` | verify every 156 API against pinned tag sources before patching |
| Patch 110 | missing closing quote on `built_in_backend` include | `8ff19f0` | include-quote lint = `patch_lint_includes.py` (now green across all 41 patches) |
| APK #58 | — GREEN (draft r58) | — | first APK with patch 110 compiled |
| Windows #32 | — cap-bank GREEN, no artifact | — | Windows next run must be artifact run (cron 523593 gates it on Linux #53) |

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
5. **gn/BUILD.gn deps:** NEVER add `"//third_party/skia"` (or any dep that parses a
   standalone component BUILD.gn not in the stock chrome graph) — duplicate
   `declare_args` vs Chromium-side .gni → gn gen dies in seconds. Skia headers
   (incl. `include/codec/*`, `include/encode/*`) come from `"//skia"`.
6. **Backend contract (aegis-sync):** register body may carry optional `username`
   (3–24 `a-z0-9_-`, 409 `username_taken`); login reply includes `user.username`.
   Push shape `{items:[{item_type,item_key,ciphertext_b64,nonce_b64,base_version,deleted}]}`
   → `{results:[{status:ok|conflict,version}]}`; pull returns `{items:[...],next_cursor}`.
7. **Aegis ID crypto chain** (byte-exact ports in patch 087/108): PBKDF2-SHA256 600k →
   HKDF auth key → PBKDF2 verifier / KEK → AES-256-GCM vault. Token/vault keys stay
   memory-only; state file `aegis_id_state.json` carries email/username/avatar cache only.
8. **Timing:** APK runs ~1.5–5 h to verdict; Linux full ~4–6 h with cap-bank windows;
   Windows ~5–6 h. Don't extrapolate ETAs from one window (runner variance up to 2.3×).
9. **GH job logs = 302 to signed URL; fetch in TWO steps:** GET `/actions/jobs/<jid>/logs`
   with a no-redirect handler → grab `Location` → GET it WITHOUT the auth header
   (re-sending auth on the signed URL = 401). This exact trap killed watchdog crons
   523593/523412 (fired on schedule, died silently, dispatched/rescheduled nothing).
   Cron payloads must embed this recipe + explicit reschedule rules.

## 10. Next actions queue (in order)

1. **Windows #33 verdict — MANUAL CHECK (no cron).** `python3 scripts/run_verdict.py
   43164506963`. RED → fix-forward (see §8; first desktop exposure of the full wave —
   expect possible new API-drift surface). GREEN cap-bank → `dispatch_flavor.py
   windows`. GREEN artifact → verify draft `windows-156.0.8078.79-r33` (.exe + .zip),
   report delivery.
2. **APK #66 verdict — MANUAL CHECK.** `python3 scripts/run_verdict.py 43143077453`.
   GREEN artifact → verify draft r66, report delivery (icons v2 + home/dock + patch 110
   local passwords + crypto). GREEN cap-bank → `dispatch_flavor.py apk`. RED →
   fix-forward APK.
3. **PATCH 114 AUTHORED 2026-11-11 (P6 client)** — complete plan at
   `docs/PATCH_114_PLAN.md`; source staged `download/aegis-p114-src/`
   (aegis_password_sync.h 232 / .cc 916 lines); generated + validated patch at
   `download/aegis-p114-out/114-aegis-passwords-sync.patch` (1220 lines) via
   `scripts/gen_114.py` (include lint + git apply --check vs post-108
   mini-tree, both green). **Deliberately NOT committed into aegis/patches/**:
   joining the chain invalidates the banks, so it rides the next desktop wave
   with 112/113. All password API signatures verified against the
   156.0.8078.79 pin (plan §3); flagged compile-time anchors in plan §8.4.
4. Author **patch 112 (P1 de-Google identity)** from ready anchors, then
   **patch 113 (P2 Capsules)** — dispatch as one desktop wave after Linux #53
   settles (per user's one-wave-per-lane preference); the wave carries
   112 + 113 + 114 in one bank invalidation.
5. **Android parity pass** (small patch 115): username field + avatar in
   Android Aegis ID (AegisIdManager.java / AegisEcosystemSettings.java), riding
   the next APK wave; Android password-sync counterpart after that.
6. When user asks: publish chosen drafts (user decides which; never unprompted).

---

*Maintenance protocol: after every pass, update §4 snapshot, §5 ledger if releases changed,
§7 roadmap statuses, §8 fix log, §10 queue — then `git add docs/PROJECT-STATE.md docs/DEPLOYMENT-PLAN.md && git commit && git push`.*
