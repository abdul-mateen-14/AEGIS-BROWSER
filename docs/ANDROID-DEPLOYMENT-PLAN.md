# ANDROID DEPLOYMENT PLAN — AEGIS-BROWSER (2026-11-11)

> Android-lane companion to `docs/DEPLOYMENT-PLAN.md` (program-wide) and
> `docs/PROJECT-STATE.md` (live ledger). This file states exactly how the APK gets
> built, what ships in each wave, and what is left. Update after every Android pass.

## 1. Objective & distribution channel

- Deliver a **GMS-free, de-Googled Chromium-156-based Android browser** (arm64 APK,
  sideload distribution).
- **Channel = GitHub draft releases** (`build-156.0.8078.79-rNN`), linked from the
  landing page. No Play Store: the whole point of the Aegis patch chain (metrics off,
  no Google defaults, no GMS password/identity backends) is incompatible with Play
  policies and GMS dependence. Publishing a draft is always the user's decision.

## 2. Build pipeline (how an APK is produced)

| Step | Fact |
|---|---|
| Workflow | `.github/workflows/build-aegis-apk.yml` |
| Target | `chrome_public_apk` (input `build_target`), `target_cpu=arm64` only |
| gn args | `aegis/ci/arm64.gn` — low-RAM args, **no LTO/PGO** (unofficial-but-stable profile) |
| Chromium pin | `156.0.8078.79` — every dispatch carries `chromium_version`; dispatch `ref=main` only |
| Bank/restore | `banking-android` draft holds zstd volumes (runs 42797034779, 42848316691); cap-bank windows resume builds across runs |
| Signing | repo secrets `ANDROID_KEYSTORE_BASE64/PASSWORD/ALIAS` → `apksigner` release signature → `AegisBrowser_156.0.8078.79_arm64_rNN.apk` |
| Delivery | attaches to draft release `Aegis Browser - Chromium 156.0.8078.79 (arm64)` with sideload notes |
| Typical duration | ~1.5–5 h to verdict (bank state dependent); APK lane has its own concurrency group |

## 3. Android patch surface (what the APK carries today)

UI/UX: 080 dock, 081 search sheet, 082 dock parity, 099 dock motion, 103 Aegis home,
109 home/dock fixes, launcher icons v2 (38ec03f), 092 android res sources.
Identity/backend: 087 Aegis ID sign-in (`AegisIdManager.java`, `AegisCrypto.java`,
`AegisSyncClient.java`, `AegisEcosystemSettings.java`), 110 local on-device password
manager (password_store backend swap: `password_manager_android_util.cc`,
`password_store_backend_factory.cc`, `password_store_factory_util.*` — no GMS).
Privacy/de-Google: 010/020/030/040, 073 defaults, 088 FRE/settings sweep, 089 brand
sweep, 096 settings strings, 098 default search, 100/104 settings hardening/dedupe,
105 Gemini off, 094 You-and-Aegis, 095 credits, 093 bookmark import.
Tab marks (P3): Android logo/tab-card asset swap — done.

## 4. In flight now (auto-managed)

- **APK #62** (id 42974545503, head `e8d86ce`, started 06:44:27Z 2026-11-11) = the real
  next delivery: launcher icons v2 + Aegis home z-order/NTP detection/dock recall fixes +
  patch 110 local password manager + all aegis_id crypto fixes.
- Watchdog cron **523412** sweeps its verdict (18:20 CST, self-reschedules +45 min):
  GREEN → verify draft r62 asset (`AegisBrowser_156.0.8078.79_arm64_r62.apk`) and report;
  RED → fix-forward in `aegis/patches/`, validate, push, dispatch.
- Fallback artifact: draft **r58** (318.9 MB, head `c70a066`) already on the release
  (no icons v2 — superseded once r62 lands).

## 5. Feature parity matrix (desktop ↔ Android)

| Feature | Desktop | Android | Android vehicle |
|---|---|---|---|
| Aegis ID sign-in (email+password, E2E vault) | ✅ 108 | ✅ 087 | existing `AegisIdManager`/`AegisSyncClient` |
| P4 username (register field + display) | ✅ 108 v2 | ❌ **left** | patch 115: `AegisIdManager.java` register body + state + `AegisEcosystemSettings.java` display (worker already accepts/returns `username`; zero backend work) |
| P5 avatar (pick→resize→E2E sync→display) | ✅ 108 v2 | ❌ **left** | patch 115: Android picker → decode → resize 256 → PNG → `AesGcmSeal(vault_key)` → push setting item `profile/avatar` (same item_key as desktop → avatars sync across desktop+Android); pull path caches |
| P6 passwords cross-device sync | ❌ patch 114 pending | ❌ after 114 | Android counterpart rides `AegisSyncClient` + patch-110 store; same `item_type=password` items → desktop↔Android share one E2E vault |
| P3 tab marks de-Chrome | ✅ | ✅ | done |
| P1 de-Google identity sweep | patch 112 pending | mostly done earlier | Android already swept (088/089/096/103); 112 is desktop-focused (installer/paths/version page) |
| P2 Capsules (tab groups) | patch 113 pending | non-goal for now | desktop presentation layer; Android native groups UI unchanged |

## 6. Android waves (execution order)

| Wave | Content | Dispatch trigger | Notes |
|---|---|---|---|
| W-now | r62 (already running) | cron 523412 | verdict + report only |
| W1 | **patch 115**: Android username + avatar (P4/P5 parity) | after r62 verdict AND patch authored+validated | one dispatch; first javac of new surface may throw 1–3 deterministic errors → fix-forward |
| W2 | **patch 114 Android counterpart**: passwords E2E sync (push/pull + settings toggle "Sync passwords via Aegis ID") | after desktop 114 lands & W1 GREEN | shares `item_type=password` with desktop → single cross-device vault |
| W3 | user-directed publishes + landing link refresh | user decision | publish chosen draft; landing gets the APK link |

Rules per wave: validate patches (`patch_lint_includes.py` + `validate_patches.py`),
commit, push `main`, `scripts/dispatch_flavor.py apk` (30-min lane rule enforced),
then update `docs/PROJECT-STATE.md` §4/§7/§10 + this file §7 and commit.

## 7. Android release ledger

| Draft/Run | Head | Size | Contains | Status |
|---|---|---|---|---|
| r58 / run 42955612843 | `c70a066` | 318.9 MB | first APK with patch 110 compiled; icons v1 | draft (fallback) |
| r57 (earlier) | pre-icons-v2 | 318.9 MB | home/dock fixes wave | draft |
| r43 / r29 | older | 318.9/511.3 MB | older waves | draft, superseded |
| r28 | older | 511.3 MB | old fat build | **published** (only published APK) |
| **r62 / run 42974545503** | `e8d86ce` | pending | icons v2 + home/dock + patch 110 + crypto fixes | **in flight** |
| W1 (r63+) | — | — | patch 115 username+avatar | planned |
| W2 (r64+) | — | — | patch 114 password sync | planned |

## 8. Verification checklist (before calling an APK "done")

1. `adb install -r AegisBrowser_156.0.8078.79_arm64_rNN.apk` upgrades cleanly over the
   previous release (same release key, same package id).
2. Launcher icon = rounded tile v2, label "Aegis".
3. Aegis home: z-order correct, NTP detection works, dock recall works.
4. Settings → Aegis Ecosystem → Aegis ID: register/login (email+password), device list.
5. Aegis Passwords: save a credential locally, restart browser, credential persists (patch 110, no GMS prompt).
6. Tab switcher card mark = Aegis (P3), no Google doodle/logo on NTP.
7. After W1: username shown on account surface; avatar pick → displayed; same avatar appears on desktop build (E2E item `profile/avatar`).
8. After W2: toggle "Sync passwords" → push; second device pulls and decrypts (E2E verified).

## 9. Risks & traps (Android-specific)

- **javac frontier:** first compile of any new Android surface historically yields 1–3
  deterministic errors (dup fields, removed 156 APIs like `Profile.getLastUsedRegularProfile`,
  `Tab.isLoaded`, `base.supplier.Supplier`). Fix-forward per run; always verify symbols
  against the pinned 156.0.8078.79 source before patching.
- **Bank churn:** APK banks sit near the 1 GB danger zone historically; volume split
  stays at 700 MB; never upload single >1 GB bodies.
- **Keystore secrets:** if signing secrets go missing the workflow fails loudly at the
  sign step (guard exists) — do not bypass; ask user to check repo secrets.
- **arm64-only:** no arm32/x86_64 builds planned; devices must be arm64 (note in release
  notes already).
- **No run cancels by agents**; APK lane dispatches obey the 30-min rule via
  `scripts/dispatch_flavor.py apk`.

## 10. Milestones

| ID | Definition | Status |
|---|---|---|
| A1 | APK with local password manager + icons v2 delivered (r62) | in flight |
| A2 | APK with username + avatar (patch 115) | planned — W1 |
| A3 | APK with cross-device password sync (114 Android side) | planned — W2 |
| A4 | User publishes chosen APK draft + landing link | on user decision |
