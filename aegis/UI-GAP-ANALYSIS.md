# Aegis UI gap analysis — r57 vs proposal (Liquid Glass Dock v1.0)

Date: 2026-11-10 ~21:30Z. Source: user screenshots (r57 APK) + dex/arsc forensics
of AegisBrowser_156.0.8078.79_arm64_r57.apk.

## Forensic facts (verified)

1. r57 classes.dex CONTAINS the aegis UI code: string literals unique to the
   aegis java survive ('aegis:private', 'EEE, MMMM d' home date format). Class
   names are R8-renamed (is_official_build=true -> R8 shrink+obfuscation; no
   keep rules in aegis patches, not needed — code is reachable).
2. AegisDockView.install() is wired in ChromeTabbedActivity.startNativeInitia-
   lization (reachable, kept). control_container GONE hunk in
   ChromeActivity.doLayoutInflation() lands between findViewById and
   initWithToolbar — hiding the LEGACY container works while browsing
   (user's search-results screenshot shows no top toolbar).
3. resources.arsc keeps only 4 aegis identifiers: aegis_dock, aegis_home,
   aegis_search_input, aegis_search_sheet. Dock drawables/tokens
   (aegis_dock_pill, aegis_dock_sliver, aegis_ic_menu, aegis_tokens ...) were
   STRIPPED by chrome_public_apk__optimize_resources (resource shrinker).
   => dock renders without its spec'd glass/drawables — visually broken pill.
4. #57 is the ONLY APK artifact ever shipped (r53-56 GREEN-at-cap, no artifact)
   -> the user tested r57.

## What the user reports (mapped)

| Complaint | Cause |
|---|---|
| "dock shows in starting then there's no dock, just dots/bar" | dock installs, auto-hides to left sliver (5dp, invisible-ish), stripped drawables make it worse; recall unreliable |
| "chrome address bar and buttons" | StartSurface/home top toolbar (NOT control_container) still renders on the home; legacy hunk doesn't cover it |
| "home page is default chrome one" | StartSurface/XSurface home (search bar, shortcuts, Chrome tips, customize promo, appearance sheet) — not AegisHomeView |
| "Aegis Passwords stopped working" | renamed Google Password Manager; UPM backend requires GMS; degoogle build -> error state |
| "Sign in to Aegis ... Add account to device" | start-surface sign-in promo -> GAIA account adder (Aegis ID patches 087/091/093 exist but promo path is GAIA) |

## Fix waves

W1 (patch 109) — proposal home + dock visibility:
- Make AegisHomeView full-screen opaque overlay whenever tab is NTP (covers
  whatever renders the NTP: StartSurface/XSurface/legacy). Tiles per proposal
  Plate 01 (clock, hardened pill, quick shields).
- Hide the start-surface/home top toolbar (find the home toolbar view +
  GONE it, or route home through AegisHomeView only).
- Sliver recall: keep 24x140dp touch target, widen idle 5->8dp + brighter
  border (visible), verify peek-after-navigation fires.
- NOTE: resource shrinker strips aegis drawables referenced from aegis java —
  add a resource keep: either res/raw keep-refs or -keep resource rules via
  aaptOptions/noObfuscation... Chromium: use `proguard_flags_files` + the
  resource shrinker traces from dex; since aegis code survives, the drawables
  should be traced-kept — investigate why they weren't (likely R8 inlined R
  constants before the shrinker trace). Chromium solution:
  `android_resources` target `shared_resources`/`no_resource_removal` gn arg
  or keep R classes via proguard flag
  `-if ... -keep class **.R$**` — pick minimal working recipe in W1.

W2 (patch 110) — Aegis Passwords (real):
- Disable UPM/GMS-dependent password features so the local LoginDatabase UI
  works under the Aegis Passwords name (save/show/autofill on-device).
- Candidate features (grep at 156): password_manager::features kUPM*/Unified*,
  kBiometricTouchToFill, settings password entry gating.
W3 (patch 111) — Aegis vault sync: wire password vault to Aegis Sync worker
  (client exists from 093), cross-device via Aegis ID.

## Treadmill state at write time
- APK #57 DELIVERED (138.3MB artifact).
- LINUX #48 (42895404119, 942db2c) building; only crypto.o + relink + deb.
- WIN #32 (42855429095) in flight, cap ~22:31Z.
- Sweeps: aegis-sweep-linux48 @21:10Z (522086), aegis-sweep-win32 @22:45Z
  (522089). Cron service parses one_time datetime strings in UTC+8 (verified).
