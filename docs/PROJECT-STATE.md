# PROJECT STATE — AEGIS-BROWSER (single source of truth)

> **READ THIS FIRST in every new session (human, agent, or cron watchdog).**
> Everything you need to resume work is in this file. If memory and this file disagree,
> trust the file, then verify against GitHub API. Update this file + commit + push after
> EVERY working pass so a context loss or sandbox recycle never loses state.
>
> Last updated: **2026-11-13 16:0x UTC (21:0x PKT)** — by main session (wave 121-126 dispatched).

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

## 4. Lane status — snapshot 2026-11-13 12:1xZ (manual checkpoint, no crons)

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
| Lane | Latest run | State | Head | Meaning |
|---|---|---|---|---|
| **APK** | #72 id=43419387001 (dispatched 2026-11-13T11:43Z) | **in_progress — bank-resume from #71's cap-bank** | `28c0e60` | #71 verdict 11:36Z: CAP BANK RUN, no APK (wave 113-118 compiled to 118,910 edges; bank gen 43376020530, 3 vols, 1,873,237,631 B size-verified). r70 = newest PUBLISHED apk. #72 restores that gen — wave pre-compiled, expect GREEN ARTIFACT. **When #72 concludes (lane idle + 30-min rule): dispatch #73** — carries wave **121-126** (identity/desktop unaffected; Android-side = 124 fullscreen + 125 NTP customization + 126 pw-updater guard). User crash dumps `aegis-crash-2026-11-13 08:26*.txt` = r69-era (timestamps predate r70's 03:49Z build; r69 lacked patch 112) — r70 on-device sessions in the user's screenshots show a stable browser; patch 126 hardens the same bridge against any future null accessor. NO watchdog — manual check. |

| **Linux** | #63 (dispatch after wave push) | **pending dispatch** | `3d4e523+` | #61 GREEN ARTIFACT (.deb 115,623,972 B, draft r61) + desktop artifact test: frame/toolbar/identity/AI-sweep/promos ✓, two gaps fixed in `b7f6cda` (116 AI-nav hard-off, 118 NTP body #141416). #62 verdict 13:26Z: GREEN .deb r62 = redundant rebuild of the r61 tree (dispatch-before-commit slip) — NOT a deliverable. **#63 = first run with wave 121-126** (patch 121 about/settings identity, 122 webstore-off, 123 aegis:// alias) + b7f6cda fixes. Desktop re-test after #63: AI-nav entry gone, NTP body #141416, version page = Aegis mark (no chromium logo/wordmark), settings About logo = Aegis mark, no Web Store tile/menu item, `aegis://version` resolves. NO watchdog. |

| **Windows** | #38 (dispatch after wave push) | **pending dispatch** | `3d4e523+` | #36 GREEN bank (gen 43351285749) → #37 restored it and CAPPED at 68,339 edges (14:11Z verdict): **cap-bank again, no exe**, gen 43392811502 streamed (2 vols, 1,447,532,054 B size-verified). **#38 restores gen 43392811502** — remaining ~31k edges should finally fit inside 280 min ⇒ expect the first Windows exe artifact. #38 also carries wave 121-126 + b7f6cda (its head 10d93fa predated those). Banking pattern intact (3 gens consumed cleanly). NO watchdog. |

**Note:** Linux #59 built `da82b21` (= `e0824f0` + gfx::PNGCodec avatar encode); APK #66
builds `35a8e90` = `e0824f0` + Android-only login-db constants — da82b21 does not touch
Android code. Windows #33 built `b8b1ab0` (docs-only on top of da82b21) and was the first
desktop run validating `1d7265d` + `2aff576` + `fd981ff` + `c3870e0` + `e0824f0` +
`da82b21` together. APK #67 builds `7c49140` = CI-only change (workflow + diagnostics
source, both outside the patch chain) — ninja inputs unchanged vs #66, so the #65 bank
applies identically and the run should mirror #66's profile (~148 min ninja).
Windows #34 builds `778d3c9` = docs + patch 111 — patch 111 touches ONLY chrome/android
class files (AegisCrashTrap.java + its 2 hook sites), outside the Windows ninja graph,
so the #33 bank applies identically and dirty stays patch-108-sized.

**Verdicts 2026-11-13 04:0xZ:** APK r69 = GREEN ARTIFACT and **the trap did its job** — user
 delivered two aegis-crash-*.txt captured on the Hot 60i at launch (thread
 "ThreadPoolSingl..."): java.lang.NullPointerException at
 PasswordSettingsUpdaterDispatcherBridge.getSettingValue (R8-remapped :25), native frames
 via JniAndroid.handleException only (handler unwind, not a native fault). Root cause
 chain + fix = patch 112 (see APK lane row); r70 id=43361540474 dispatched @ 00f965b.
 Windows #35 = GREEN BANK RUN (already recorded); #36 in_progress @ f0c7f29 (started
 02:19Z, cap ≈ 06:39Z if not finished earlier).

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

**Verdicts + triage 2026-11-12 09:2xZ:** APK #66 = **GREEN ARTIFACT** — resumed #65's
2.19 GB bank, AEGIS-DIRTY edges=11658, ninja rc=0 after 148 min, NINJA_EDGES=200,734,
sign step ran apksigner verify --print-certs (V3.0 signer cert DN C=US/OU=Release/O=Aegis/
CN=Aegis Browser, SHA-256 f36c11bfd0e1e9411168d8faee214e4093c333ec428580de9232a7d6e97371a6)
→ draft `build-156.0.8078.79-r66`, APK 338,083,578 B. Windows #33 still in_progress.

**APK r66 install failure — triage (APK VERIFIED GOOD, device-side suspected):** user
sideloaded r66 (338.08 MB = byte-exact asset) → installer: "App not installed as package
appears to be invalid." Local dissection (scripts/inspect_apk66.py on downloaded asset):
zip integrity clean (4,786 entries, unzip -t no errors), v1 (META-INF/AEGIS.RSA,
sha256WithRSA, cert valid 2026-11-01→2056) + v2 (0x7109871a) + v3 (0xf05368c0) signing
blocks all present, manifest parses (label "Aegis" readable), minSdk=29 / targetSdk=37 /
versionCode 807807904 / package org.chromium.chrome, extractNativeLibs=false with ALL 8
.so STORED + 4KB-aligned, resources.arsc STORED. **r58 vs r66 structural diff: identical
in every install-relevant field** (same minSdk/target/sign schemes/lib pattern, 0 bad
entries both). minSdk=29 is STOCK Chromium 156 (config.gni default_min_sdk_version=29;
24 is Cronet-only) — stock Chrome 156 also requires Android 10+. Suspects (in order):
(1) phone Android < 10 → minSdk refusal (MIUI shows the generic invalid string);
(2) stale older-signature Aegis install (pre-Nov-1 keystore era) → update-incompatible;
(3) MIUI/HyperOS pure mode blocking sideload; (4) 16KB-page device (4K-aligned .so
rejected) — long shot. Awaiting from user: device model + Android version, whether an
old Aegis build is installed. Candidate hardening (workflow change → user OK first):
zipalign `-P 16` (16KB-page-safe packaging, zero risk on 4K devices).

**RESOLVED 2026-11-12 10:2xZ — install failure was a TRUNCATED DOWNLOAD (Brave browser
interruption); re-download installed fine.** NEW ISSUE: r66 crashes on launch. Device =
Infinix Hot 60i (Helio G81 Ultimate, Android 15 / XOS 15.1, arm64, 4KB pages) — meets
minSdk 29; NOT a compat issue. First runtime test of ANY 156-chain APK (no device-OK
baseline exists). Static sweep of r66 (scripts/apk66_startup_statics.py): all 223
manifest class refs OK (the 42 raw "missing" are normal non-class alias names like
com.google.android.apps.chrome.Main + meta-data keys — not real classes; the only real
tags are 2 NativeOnlySandboxedProcessService{0,1} child-process services never bound at
launch), 81 aegis_* resources present in resources.arsc (dock pill/sliver, glass, tokens
all there; r57 stripper fix confirmed), 23 icon entries (mipmap + layered).

**Launch-crash triage 2026-11-12 10:4xZ — 16KB-PAGE HYPOTHESIS ELIMINATED (both layers):**
(ELF) readelf -lW on all 8 extracted .so: every primary PT_LOAD has p_align=0x4000
(libchrome 3rd seg 0x10000=64KB, superset) — Chromium 156 links arm64 16KB-ready by
default. (ZIP) ground-truth data-offset check (local header + name + local extra): ALL
8 .so entries sit at 0 mod 16384 — zipalign honors the ELF p_align even at `-p 4`
(cross-checked vs unzip -v). So the Hot 60i crash is RUNTIME (patch-wave prime suspect)
or device-specific loader behavior — it needs the actual stack.

**User chose offered path (3) — diagnostics artifacts shipped 2026-11-12 (commit
7c49140):**
- **Aegis Diagnostics app** — `aegis/diagnostics/`, 16,811 B, org.aegis.diagnostics
  v1.0, minSdk 29/targetSdk 35, v3-signed (diag-only keystore, NOT the release key),
  sha256 e7870f8b9aa5f797250bca0c29fab4c9d44be43325815e2ba8b3cc93d613b197, copy at
  download/AegisDiagnostics.apk. One screen reports: kernel page size (Os.sysconf),
  device/ABI, installed Aegis version + .so zip offsets (%4K/%16K) + ELF max PT_LOAD
  p_align, then System.load()s two small Chromium ELFs (linker 18 KB, crashpad
  trampoline 6 KB). One screenshot separates loader/alignment failure from runtime
  crash — reusable for every future Android test, no adb needed. Build via
  aegis/diagnostics/build.sh (build-tools 35 + android-35 jar).
- **Workflow 16 KB test lane** (7c49140): standard APK + `-test16k` variant from the
  same binaries + `check_align` gate (4096 AND 16384) computed from zip local headers —
  a silently-ignored zipalign flag can never ship a fake variant; every future release
  stays provably 16 KB-compliant (Nov-2025 Play requirement pre-staged).
- **Crash-trap rebuild (patch 111 authored+validated 2026-11-12, commit 174fc29; FIX
  345eb52 — r68 red: the new file was never registered in `chrome_java_sources.gni`
  and javac correctly failed with `cannot find symbol`; the patch now carries a 4th
  hunk adding the `aegis/AegisCrashTrap.java` entry; re-validated `git apply --3way` +
  reverse-check on pristine 156 files; dispatches as r69)**: AegisCrashTrap.java (chrome
  split) records any
  uncaught Java exception — device/process/version + full stack — to MediaStore
  Downloads (`aegis-crash-<millis>.txt`, no permission on API 29+, visible in
  Files/Downloads; app-private backup copy too), then chains to the previous handler so
  stock crash handling is unchanged. Two hooks, both files pristine at the pin (patch
  103 touches only the manifest template, NOT the class files):
  (1) SplitChromeApplication.attachBaseContext — reflective install as the FIRST
  statement: earliest app code, pre-ContentProvider; reflection keeps the base→split
  dependency direction intact; (2) ChromeApplicationImpl.onCreate — direct call, which
  is also what keeps the trap class alive under R8 (reflection-only classes get
  stripped in official builds). Generated by scripts/gen_patch_111.py (anchors asserted
  unique, git apply --check+apply on a fresh pristine 156 tree, trap compile-checked
  against android-35). NATIVE crashes stay silent — if the trap file never appears,
  fall back to offered path (1) LADB logcat or (2) r58 install bisection. r67 must
  finish first (one active run per lane).

**Verdict 2026-11-12 12:0xZ — Windows #33 = GREEN BANK RUN (wave 1 of the desktop
build, no artifact yet):** restored banking-windows gen 42855429095 (68,117 edges,
~1 min), AEGIS-DIRTY edges=58,568, ninja rc=137 after 273 min — the 280-min cap fired
its INT, killed cleanly (1× Killed), last progress [24,620/58,567] ≈ 42% of the dirty
wave. BUILD_REASON=cap, BUILD_DONE unset, NINJA_EDGES=62,327. Bank streamed:
**AEGIS-BANK flavor=windows gen=43164506963 vols=2 bytes=1,448,325,651 edges=62,327** →
new banking-windows draft. Run itself completed/success (bank path is a green exit by
design). Rate ≈ 93 edges/min ⇒ ~33.9k remaining edges ≈ 365 min > cap ⇒ next wave also
caps; Linux took 2 waves (bank→GREEN). **Windows #34 verdict 18:0xZ: completed/CANCELLED**
— cap hit (BUILD_REASON=cap, 47,396 edges logged), 7.5 GB bank streaming began 18:00:02Z,
run cancelled 18:02:28Z at 5h50m elapsed BEFORE any `[up]` line: zero volumes uploaded,
no new gen, #34's wave progress lost. Cause unknown (no newer windows run → no
concurrency kill; not the 6h wall at 18:12Z). **Windows #35 verdict 00:0xZ Nov 13: GREEN
BANK RUN** — restored gen 43164506963, dirty 58,567 (full wave redo), ninja rc=137
(cap INT) after 272 min at [27,214/58,567] ≈ 46.5%, rate ~100 edges/min, bank gen
**43276366118 vols=2 bytes=1,449,991,132 edges=67,993** fully uploaded (first clean
bank save since #33). **Windows #36 dispatched id=43351285749 @ f0c7f29** (02:19Z Nov
13, 30-min rule passed — #35 created 493 min earlier; restores gen 43276366118).
Verdict command: `python3 scripts/run_verdict.py 43351285749`.

**Local 16KB test variant of r66 built (future Android test artifact, user request):**
`download/aegis-156.0.8078.79-r66-16kb-test.apk` 337,583,797 B — pipeline scripts/
`mk16k.sh` (strip_sigblock.py → align16k.py [own zipalign-equivalent, extra-field
padding] → official build-tools 34 apksigner on JRE 21). v1-off / v2+v3 signed with
throwaway key CN="Aegis Browser (16KB test)", cert sha256 f6cbc6a282fb421a76e49d3a5e7c
bb8814c9637eeea487ad8caea487eefd057a (≠ release key ⇒ uninstall release build before
installing). check16k.py audit on the signed file: all 4,786 entry blobs byte-identical
to r66 (parity 4786/4786), 8/8 .so STORED at 0 mod 16384, stored entries 0 mod 4,
apksigner verify Verifies + minSdk 29 OK (verify output profile identical to the
release asset's). The audit independently re-confirmed r66 was already 16K-compliant,
so this variant is a test/utility piece, not a crash fix; r67's `-test16k` lane
supersedes it for release-signed variants. Build kit kept in scripts/apk16k/
(build-tools 34 zipalign + apksigner.jar both run on the sandbox JRE; note public
build-tools zips end at r34 — no `-P 16` zipalign CLI exists, hence the Python aligner).

**On-device diagnostics results 2026-11-12 (user screenshots; Aegis Diagnostics v1.0
running on the phone):** device INFINIX X6728B (Hot 60i), fingerprint
Infinix/X6728B-OP/Infinix-X6728B:15/AP3A.240905.015.A2/195007:user/release-keys,
Android 15 (SDK 35), arm64-v8a, **kernel page = 4096 bytes → 4K KERNEL** — the
16KB-page theory is now dead at the device level too (this device never needed 16K
alignment). Installed APK = r66 byte-exact (versionCode 807807904; zip data offsets
match the local dissection digit-for-digit, libchrome.so @47448064 …). On-device
audits mirror the offline analysis exactly: .so zip offsets 8/8 %4K=ok %16K=ok
("8 16K-aligned, 0 problems"); ELF PT_LOAD p_align max = 0x10000 (libchrome.so),
0x4000 for the rest → all 16K-ready.

Load tests (the new signal): `libcrashpad_handler_trampoline.so` (6,192 B)
**LOADED OK**; `libchromium_android_linker.so` failed with `UnsatisfiedLinkError:
JNI_ERR returned from JNI_OnLoad` — **verified BY-DESIGN for a foreign host, at the
pin's own source** (upstream tag 156.0.8078.79): JNI_OnLoad → LinkerJNIInit →
LibInfo_class.Init(env) does FindClass("org/chromium/base/library_loader/Linker$LibInfo")
(base/android/linker/linker_jni.h) — that class lives in libchrome.dex, which the
Diagnostics app does not carry → Init returns false → JNI_OnLoad returns -1. The .so
itself was fully loaded, linked and EXECUTED (it runs LOG_INFO("Entering") before the
class lookup), so the device's dlopen→relocate→run pipeline is healthy; in-browser
the class exists and JNI_OnLoad succeeds either way — NOT a device fault.

**Net verdict: statics clean + loader machinery proven working on-device ⇒ the r66
launch crash is INSIDE Chromium's own startup — Java runtime (patch-wave prime
suspect) or the APK-direct extractNativeLibs=false load path, which a foreign app
cannot replicate.** Discriminator = **r69, NOW BUILT AND SHIPPED** (patch 111 trap,
FIXED 345eb52, GREEN ARTIFACT 2026-11-12T20:11Z, draft `build-...-r69` .apk
338,097,998 B; r68 red on a compile error before any device test): **install r69 on
the Hot 60i → launch.** Java crash → `aegis-crash-<millis>.txt` in Files/Downloads
(user sends it); crash with no trap file → native crash → fall back to LADB logcat
or r58-install bisection.

**Linux r59 .deb feature test 2026-11-13 (sandbox Xvfb+CDP, full report +
13 screenshots at `download/aegis-r59-deb-review/`):** 10 features PASS —
packaging/branding (097), product strings (089/096/103/107), patch-095 license
line (renders on chrome://settings/help, string in pak), default search Aegis
(098), cookie_controls_mode=1=kBlockThirdParty fresh-profile + radio UI (010;
156 enum ground-truth: kOff=0/kBlockThirdParty=1/kIncognitoOnly=2), DNT on
(030), metrics off by source+Local-State (040), suggest off (020), Shield with
CDP `net::ERR_BLOCKED_BY_CLIENT` on GA/GTM/adservice + control LOADED (070),
help links → aegis-browser.pages.dev (072/085), UA Chrome/ token intact.
3 PARTIALs queued as candidate patch-116 material (desktop wave with 113/114/115):
(1) chrome://version wordmark still Chromium — 106 missed
chrome/app/theme/chromium/product_logo.png (107 covered only the .svg);
(2) settings autofill card label still "Password Manager" (074's patched id is
not the one that card renders); (3) Aegis ID dialog (108) unreachable — its
kSigninButton hook is compiled but 073 removed the promo button; needs an
explicit profile-menu item.

## 5. Deliverables ledger (verified via API 2026-11-13 12:0xZ)

Published releases (user decides these; never delete unprompted):
- `build-...-r70` .apk 338,097,998 B (GREEN #70 @ 00f965b, sha256 `df81d78b…`, patch-112 NPE fix) — **PUBLISHED 2026-11-13 06:43Z (user publish, not assistant)**; tag `build-…-r70` points at 47e86d6 (docs), build was 00f965b
- `linux-156.0.8078.79-r45` (.deb 110.5 MB + .zip 160.0 MB, tag @ `a548d4c`) — **the only published release**

Draft releases (NEVER publish/delete without user):
- `linux-...-r61` .deb 115,623,972 B + .zip 167,326,390 B (GREEN #61 @ 10d93fa, ninja rc=0 after 23 min; desktop-tested 2026-11-13 — see §8) — **latest Linux artifact draft**; NOT desktop-tested-clean: two gaps fixed by `b7f6cda` ride #63
- `linux-...-r59` .deb 116 MB + .zip 167 MB (GREEN #59 @ da82b21) — first of the 156 chain
- `build-...-r69` .apk 338,097,998 B (GREEN #69 @ 345eb52; 63 min ninja after 5,042-edge
  surgical invalidation; check_align 4096+16384 both passed; = r67 + 14,420 B of compiled
  crash-trap) — **latest APK artifact draft; the crash-trap discriminator awaiting the
  Hot 60i device test**
- `build-...-r67` .apk 338,083,578 B (GREEN #67 @ 7c49140; 154 min ninja, check_align
  4096+16384 both passed; byte-size-identical to r66 — CI-only change — **-test16k
  variant skipped**, CI's bundled build-tools has no `-P 16` zipalign) — superseded by
  r69; content-identical build of r66's code
- `build-...-r66` .apk 338,083,578 B (GREEN #66 @ 35a8e90; icons v2 + home/dock + patch 110 local passwords) — superseded by r67; still the build installed on the Hot 60i (launch crash under triage, see §4)
- `build-...-r58` APK 318.9 MB (GREEN #58 @ c70a066; NO icons v2) — superseded by r66
- `windows-...-r31` .exe 116.1 MB + .zip 199.9 MB — **latest/best Windows artifact draft** (r33/r34 banking toward the first artifact of the desktop wave)
- `banking-windows` — bank vols: runs 42764643911 (#30), 42855429095 (#32); gens 43164506963 (#33) and 43276366118 (#35) were DELETED by #36's save; **latest gen 43351285749 (#36, 1,451,616,214 B, 67,230 edges) — restores into #37**
- `banking-linux` — bank vols: runs 41870785941 (orphan txt), 43016173134 (#54), 43026595992 (#55, 1.71 GB), 43080734791 (#57, 1.71 GB), 43133968988 (#58, 1.61 GB — restored by GREEN #59), 43372651434 (#60 failed-run bank, fresh tree through 118 @ buggy-117, ~913 edges — restored by GREEN #61); **next save = #62's post-green tree**
- `banking-android` — bank vols: runs 42797034779, 42848316691, 42974545503 (#62, 1.89 GB), 43019369532 (#63, 1.96 GB — rotated away by #65), 43073661607 (#64, 2.18 GB), 43133995506 (#65, 2.19 GB, 182,018 edges — restored by #66/#67), 43250401495 (#68, 2.35 GB — restored by #71) + **43376020530 (#71 cap-bank, latest gen, 3 vols, 1,873,237,631 B, 118,909 edges — wave compiled; restores into #72)**
- **Aegis Diagnostics app (NOT a release):** `download/AegisDiagnostics.apk` 16,811 B,
  sha256 e7870f8b9aa5f797250bca0c29fab4c9d44be43325815e2ba8b3cc93d613b197; source
  `aegis/diagnostics/` + `build.sh`; org.aegis.diagnostics v1.0, v3-signed with the
  diagnostics-only keystore — on-device triage tool, see §4
- **16KB test variant of r66 (NOT a release; future Android test artifact):**
  `download/aegis-156.0.8078.79-r66-16kb-test.apk` 337,583,797 B, test-signed
  CN="Aegis Browser (16KB test)", sha256 f6cbc6a282fb421a76e49d3a5e7cbb8814c9637eeea487ad
  8caea487eefd057a — locally built from the r66 draft asset, see §4 verdict block
  (entry data byte-identical to r66, 8/8 .so 0 mod 16384)

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
2c07787  docs: APK #66 GREEN verdict + install-failure triage (APK good, device-side)
2521f08  docs: install failure resolved (truncated download); launch-crash triage queued
174fc29  patch 111: Aegis crash trap -> MediaStore Downloads stack dump
0b53c24  docs: 16KB hypothesis eliminated; r67 dispatched; Diagnostics delivered
7c49140  apk: 16 KB test lane + compliance gate + standalone Aegis Diagnostics app
c1286ef  docs: r67 GREEN ARTIFACT + diagnostics + r68 dispatched
345eb52  apk: patch 111 registers AegisCrashTrap in chrome_java_sources.gni (r68 fix)
f0c7f29  docs: r68 compile-failure verdict + patch-111 fix + r69; #34 cancelled + #35
dc1244c  docs: r69 GREEN ARTIFACT + #35 GREEN BANK RUN (46.5%) + #36 dispatched
00f965b  patch 112: settings service on pref-backed impl (fix startup NPE crash loop)
                                                                          ← main HEAD (#70 builds 00f965b; #36 builds f0c7f29)
```

(docs commits interleaved throughout: lane snapshots, ledger refreshes, cron purge,
patch-114 authoring — see git log)

## 7. Feature roadmap (user requests P1–P6)

| ID | Feature | Status | Where |
|---|---|---|---|
| P3 | Tab marks de-Chrome (Android + desktop) | **DONE** | earlier wave (logo asset swap, Android tab-card mark; desktop had no Chrome literal) |
| P4 | Aegis ID username | **DONE** (backend + desktop client) | worker `/v1/auth/register` `username` (3–24 `a-z0-9_-`, unique, 409 `username_taken`, live in D1) + patch 108 v2 (`1d7265d`). **Left: Android-side username field** (AegisIdManager.java / AegisEcosystemSettings.java, patch 087 surface) |
| P5 | Aegis ID avatar (upload→scale→use) | **DONE desktop client**, worker needs nothing | patch 108 v2: file picker → SkCodec decode → skia resize fit 256 → PNG → AesGcmSeal(vault key) → push item_type=setting item_key=profile/avatar; pull path decrypts+caches in `aegis_id_state.json`. **Left: Android-side avatar** |
| P6 | Passwords sync via aegis-sync | **AUTHORED 2026-11-11** (source + plan + patch generated + apply-tested; NOT in chain yet) | patch 114: `docs/PATCH_114_PLAN.md` (full plan + verified 156 anchors); source staged `download/aegis-p114-src/`; generated patch `download/aegis-p114-out/114-aegis-passwords-sync.patch` (1220 lines, include-lint + git-apply green). Joins the next desktop wave with 113/115 (bank invalidates once). Left: Android counterpart. |
| P1 | Total de-Google identity (paths, version page, installer, strings) | **PARTIAL — wave 121-123 shipped 2026-11-13**: version page + settings About = inline Aegis mark (121), Web Store app + menu entry removed (122), aegis:// alias scheme (123). Strings/paths/installer identity already swept by 089/096/106/097. DEFERRED: browser binary rename chrome→aegis (installer stages `<PROGNAME>.stripped` — needs gn target rename, high blast radius, invisible to the user; Linux deb already installs `/usr/bin/aegis-browser` + "Aegis Browser" menu) | anchors in `download/aegis-design/DEGOOGLE-IDENTITY-CAPSULES-PLAN.md` §P1; UA `Chrome/` token DO NOT CHANGE |
| P2 | Capsules = native tab groups (Edge-style) | **NOT STARTED** — patch 113, ship with 115 | anchors in same file §P2 (TabStripModel.h:547/684/693/756/713, TabStripController.h:104/108/116/124/162-184, TabGroupModel.h:42/47/57, tab_group_color.h:39-51 (9 colors), tab_group_editor_bubble_view.h:44/489, saved_tab_groups, tab_context_menu_command.h:24-30; NO TabGroupController at 156; tab_strip_action_container is Glic-only → group UI is ours) |

## 8. Fix-forward log (2026-11-11 wave — most recent first)

| Run | Error | Fix commit | Lesson |
|---|---|---|---|
| r61 .deb desktop test (forced-dark Xvfb) | TWO runtime gaps vs user feedback 2026-11-13: (a) settings nav keeps a dangling **"AI innovations"** entry — upstream `showAiPage` = OR(show_glic, ai_features, ai_mode_search, geic) still evaluates true in non-Google-branded builds though the AI page is already gone (chrome://settings/ai + /aiInnovations both redirect to chrome://settings/, verified via CDP); (b) **NTP body #3C3C3C** — material NTP mixer (last writer) resolves kColorSysBase through the NativeTheme forced-dark palette = Chromium greys, never the Aegis ramp. Frame (avg #0F1010 ≈ #0A0A0B ✓) and toolbar (#141518 ≈ #141416 ✓) verified exact — they are direct-RGB constants in patch 118, palette-independent | `b7f6cda` | RUNTIME TESTING CATCHES WHAT PATCH REVIEW CANNOT — both gaps were invisible in code review (one feature-gated upstream, one palette-dependent). showAiPage=false MUST keep showAiPageAiFeatureSection's original value or -Wunused-but-set-variable kills -Werror. Patch 116 also neutralizes UpdateShowGlicState()'s runtime re-set. Patch 118 addendum pins kColorNewTabPageBackground in material_new_tab_page_color_mixer.cc (last-writer-wins). Both hunks generated by git diff vs pristine branch-heads/8078 + git apply --check green. SEQUENCING LESSON: #62 was dispatched BEFORE the fix commit — its checkout grabbed 28c0e60 ⇒ redundant r61-tree rebuild the iron rule won't let us cancel; COMMIT + PUSH FIRST, DISPATCH SECOND |
| Linux #60 (f09dd2f) | FIRST compile of p117's desktop hunk: `whats_new_util.cc:67:3` — `code will never be executed [-Werror,-Wunreachable-code]` (`-Wunreachable-code-aggressive`): the hunk only PREPENDED `return false;` to `ShouldShowForState()`, leaving the entire original `#if IS_CHROMEOS/#else` body unreachable; ninja rc=1 at [913/1347] after 40 min, fresh-tree bank saved (gen 43372651434, ~913 edges) | `10d93fa` | A HARD-OFF GATE MUST DELETE THE BODY, NOT PRECEDE IT — code after an unconditional `return` is dead under -Werror; Java's `if (true) { return; }` carve-out (JLS 14.21) has no C++ equivalent. Desktop webui TUs never compile on APK runs (r70/r71 logs show only `chrome/browser/ui/android/whats_new` java targets), so the APK lane was never exposed. Hunk regenerated by scripts/fix_p117_unreachable.py (difflib, full-body deletion; `git apply --check` + `--3way --check` green vs pristine 156.0.8078.79 sources); LogStartupType/IsEnabled stay header-declared → no -Wunused-function |
| APK r70 (00f965b) | fix-forward: ON-DEVICE LAUNCH CRASH LOOP — r69 trap files show NPE in PasswordSettingsUpdaterDispatcherBridge.getSettingValue (settings-updater background thread, 2× at launch, 3.8 s apart). Root: patch 110's forced IsPasswordManagerAvailable() gate ALSO instantiates the GMS-backed PasswordManagerSettingsServiceAndroidImpl; un-branded builds ship no downstream PasswordSettingsAccessorFactory (UpstreamImpl is empty) → null accessor → NPE. Fix: Android uses the pref-backed PasswordManagerSettingsServiceImpl (upstream's designed no-GMS fallback), patch 112 | `00f965b` | THE CRASH TRAP PAID OFF — one device launch + two 4 KB text files replaced weeks of blind bisection. A forced gate must be audited for EVERY consumer, not just the one you patched (the gate had two call sites: backend factory + settings-service factory; only the first was considered). Upstream `@Nullable`-with-`assert` patterns are release-deadly: assert never fires in release APKs, so the null flies through to the deref |
| APK r69 (345eb52) | — GREEN ARTIFACT (draft r69, 338,097,998 B = r67 + 14,420 B): bank resume dirty 5,042, ninja rc=0 after 63 min. On-device: launches → crash loop → trap wrote `aegis-crash-*.txt` = DIAGNOSTIC SUCCESS | — | Patch-111 4th-hunk gni registration was the complete fix. VERIFY the trap fires on a real crash before trusting it as instrumentation — it did (full Java stack + thread + device metadata captured, files land in Files/Downloads via MediaStore) |
| APK #66 (35a8e90) | — **GREEN ARTIFACT** (draft r66): bank resume 11,658 dirty edges, ninja rc=0 after 148 min, v1+v2+v3 signed+verified. On-device install then failed "package appears to be invalid" — APK locally verified GOOD (zip/sign/manifest/libs all clean; r58 structurally identical; minSdk 29 = stock 156). Device-side suspected | — | ALWAYS dissect the artifact before blaming the build: byte-exact download + unzip -t + signing-block walk + AXML parse + .so alignment check took ~15 min and cleared the whole CI pipeline. minSdk=29 comes from config.gni default_min_sdk_version (Cronet-only =24) — Chromium 156 requires Android 10+ like stock Chrome. Installer CAN show the label while refusing install — parseability ≠ installability |
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

1. **DONE 2026-11-13 16:0xZ:** all three lanes' verdicts verified (see §4).
   Linux #61 GREEN + desktop test (2 gaps → `b7f6cda`), #62 GREEN redundant; Windows #37
   cap-bank (68,339 edges, gen 43392811502 banked); APK #71 cap-bank (gen 43376020530),
   #72 in flight.
2. **WAVE 121-126 COMMITTED+PUSHED 2026-11-13** (user feedback 2026-11-13):
   - **121 desktop identity** — chrome://version + settings About now render the inline
     Aegis mark (replaces IDR_PRODUCT_LOGO / current-channel-logo binary PNGs patch 107
     never reached), "About Aegis" title.
   - **122 webstore-off** — Google Web Store component app removed from the allowlist
     (no chrome://apps tile, no desktop entry) + desktop menu "Visit Web Store" deleted.
     Real Aegis store = separate backend project (roadmap, uses aegis-sync worker).
   - **123 aegis:// alias** — aegis:// registered standard+secure; WebUIConfigMap resolves
     aegis://<host> to chrome://<host ⇒ aegis://version works, chrome:// still works.
   - **124 Android full-screen** — AegisTopToolbar hides the top control container and
     re-asserts GONE every second (080's one-shot hide was reverted by the controls
     machinery), zeroes ToolbarControlContainer-reported top height (no reserved strip),
     hides bottom_container. Dock = only chrome.
   - **125 NTP customization** — editable shortcuts (long-press remove, + tile add,
     JSON prefs), favicons via LargeIconBridge (letter fallback), wallpaper picker
     (6 presets + ACTION_OPEN_DOCUMENT image, persistable URI + scrim).
   - **126 pw-updater guard** — null-accessor guard in PasswordSettingsUpdaterDispatcher
     Bridge get/setSettingValue → failure callback instead of NPE (hardens the exact
     crash-trap signature; user dumps were r69-era, r70+112 holds).
   All hunks generated via git diff vs pristine/chain-replayed trees;
   apply-tested: 121-124+126 vs pristine, 124→125 sequential apply green
   (scripts/patchgen/apply_test_wave.py + /tmp-style manual verify).
3. **Dispatch order (30-min lane rule, commit+push FIRST):** Linux #63 + Windows #38
   immediately after push; **APK #73 only after #72 concludes + 30 min**.
4. On-device (user) after APK #73 GREEN: full-screen browsing (no top toolbar at all),
   editable shortcuts + favicons + wallpaper on NTP, dock URL pill, splash, icon,
   no promos; trap stays armed (fresh aegis-crash-*.txt = triage input).
5. Desktop re-test after Linux #63 GREEN: version page + settings About = Aegis mark;
   no Web Store anywhere; aegis://version resolves; AI-nav entry gone; NTP #141416.
6. Numbering after 126: password-sync staged patch 114 → renumber **127**; P2 Capsules →
   **120 is taken by nothing yet but reserved** (capsules = 128 if 119-126 consumed);
   Android Aegis ID parity pass after that.
7. When user asks: publish chosen drafts (user decides which; never unprompted).
   r70 published 2026-11-13 06:43Z — not by assistant actions.

---

*Maintenance protocol: after every pass, update §4 snapshot, §5 ledger if releases changed,
§7 roadmap statuses, §8 fix log, §10 queue — then `git add docs/PROJECT-STATE.md docs/DEPLOYMENT-PLAN.md && git commit && git push`.*
