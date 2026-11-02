# Aegis Browser (v1.3)

A privacy-hardened Chromium browser for Android (arm64). It is built and
shipped entirely on GitHub's free infrastructure — no ads, no accounts, no
telemetry, and every piece of the toolchain that produces it is public.

- **Aegis Shield** — ad & tracker blocking at the network layer, 92,957
  hosts, no extensions involved
- **Private by default** — third-party cookies off, search suggestions off,
  Do Not Track on, metrics opt-in only
- **End-to-end encrypted sync** — the server only ever sees ciphertext
- **Real releases** — every build ends in a signed APK on the Releases page
- Landing page: **https://aegis-browser.pages.dev** (Cloudflare Pages, free tier)

## Get the browser

Open [Releases](https://github.com/abdul-mateen-14/AEGIS-BROWSER/releases),
download the latest `AegisBrowser_..._arm64.apk` (Android 8 and up), allow
"install unknown apps" when Android asks, install. The shield is on from the
first launch.

## How it is built

No local Chromium compilation, no rented cloud machines, no build services.
All the heavy work runs on GitHub's free hosted runners — the repository
itself holds only text files (patches, scripts, workflow definitions), and
one click on the Actions tab turns them into a signed APK 4-7 hours later.
The full story is in `docs/Aegis_Free_Android_Build_Plan.docx` (+ .pdf).

## What's new in v1.3

- **Aegis Shield v1 - built-in, Brave-style ad & tracker blocking.** A
  ~60-line C++ engine in the network service (`services/network/
  aegis_shield.cc`) drops third-party requests to **92,957** known
  ad/tracker hosts with `ERR_BLOCKED_BY_CLIENT` - the same error adblock
  extensions produce. Browser-initiated and same-party requests always
  pass, the Aegis ecosystem/sync domains are hard-allowlisted, and the
  data is generated from EasyList + EasyPrivacy by
  `scripts/aegis-shield/gen_blocklist.py` (semantics-tested in
  `scripts/test-shield-semantics.py`). See `docs/SHIELD.md`.
- **Aegis Ecosystem page.** Settings now ends with an "Aegis" section
  opening the **Aegis Ecosystem** screen with the whole product family:
  AM Products (`am.infy.uk/products/`), CoreX, AEGIS OS and AEGIS SOC.
  Shipped as patches `052` (menu entry) + `054` (fragment + XML),
  ground-truthed @ `156.0.8078.79`.
- **Sync UI moved to batch 3** (was batch 2): the Ecosystem entry takes
  the free settings slot first; the sync screen patch series is planned
  in the updated `docs/PATCHES_BATCH_2_PLAN.md`.
- Patch Foundry: a spec may now edit the same file more than once
  (include + call-site in one patch).

## What's new in v1.2
- **The sync client now ships as a Java twin - and both layers are re-proven
  in it.** Chromium's chrome_java targets are Java-first, so the tested
  Kotlin client was ported to `android-sync/java/org/aegis/sync/`
  (`AegisCrypto.java`, `AegisSyncClient.java`) to integrate with a one-line
  BUILD.gn addition instead of new Kotlin tooling. The Java crypto core
  passes the SAME generated vectors - **16/16 checks** (the generator now
  emits identical `.kt` and `.java` vector twins). The Java protocol client
  passes the SAME acceptance sequence live - **13/13 E2E steps** against a
  locally-run worker (register, two-device push/pull, the conflict path,
  password rotation with vault-key survival, account deletion).
- **Patch Foundry: the safe way to author the patch series for $0.**
  `scripts/patch-foundry/` adds an asserted-edit engine (each spec must find
  its anchor EXACTLY once in the exact tag, or the run aborts loudly and
  restores the tree - no silent no-ops, no broken 5-hour CI runs), a GN-args
  verifier for the hardening batch, a cheap `fetch-chromium-src.sh`, and the
  $0 GitHub-Codespace authoring guide in `docs/PATCH_FOUNDRY.md`. The engine
  is end-to-end tested on a mock tree: clean patch emitted, `git apply
  --3way` applies cleanly to a pristine checkout, and the abort path leaves
  the tree untouched.

## What's new in v1.1

- **The Kotlin sync client's crypto layer is conformance-tested: 16/16 checks pass.**
  The crypto was split into `android-sync/AegisCrypto.kt` (pure JVM, zero
  dependencies) and validated against generated vectors that were themselves
  cross-checked against WebCrypto AND Node's native PBKDF2. The suite includes a
  non-ASCII-password regression case and the full 600,000-iteration production KDF.
- **A real crypto bug was found and fixed.** The draft used javax's
  `PBKDF2WithHmacSHA256`, which converts its `char[]` input to bytes via
  UTF-8. WebCrypto feeds PBKDF2 raw bytes, so for any input containing bytes
  >= 0x80 - which the random authKey behind the login verifier always has -
  Android would have derived a DIFFERENT verifier than the JS reference and
  cross-device login would have failed. `AegisCrypto.pbkdf2` now implements
  RFC 2898 over raw bytes directly (see the file header for the full rationale).
- **The protocol layer is live-tested: 13/13 E2E steps pass.**
  `android-sync/test/E2EDriver.kt` runs the full Phase 3 acceptance sequence
  against a real aegis-sync worker (local via `wrangler dev`, or your deployed
  URL): register, two-device login, push/pull in both directions, the
  optimistic-concurrency conflict path, the device list, password rotation with
  vault-key survival, old-password rejection, and account deletion. The run also
  caught one API fix: `register()` now returns the server response.
- Branding hook hardened: `app_name` is replaced wherever it is defined under
  `chrome/android/java` instead of in one path that moves between releases.

---

## Repository layout

Everything here is the repository itself; `docs/PUSH_TO_GITHUB.md` documents
three ways to push it to GitHub, one of them fully automatic.

```
.github/workflows/build-aegis-apk.yml   <- the free CI pipeline (the core of this kit)
aegis/ci/arm64.gn                       <- low-RAM build flags (documented inline)
aegis/ci/linux_x64.gn                   <- same low-RAM family for Linux x64 (Phase D1)
aegis/branding/apply_desktop.sh         <- desktop branding hook (English UI strings)
aegis/patches/                          <- your Chromium patches (.patch), applied in order
aegis/patches/README.md                 <- how to write patches that survive free CI
aegis/branding/apply.sh                 <- branding hook (sets the launcher label)
scripts/create-release-keystore.sh      <- one-time keystore creation (run locally)
scripts/gen-crypto-vectors.mjs          <- regenerates the crypto test vectors (Node 18+)
android-sync/AegisCrypto.kt             <- crypto primitives, pure JVM, vector-tested
android-sync/AegisSyncClient.kt         <- Phase 3 protocol client (live-tested against the worker)
android-sync/test/AegisCryptoVectors.kt <- generated test vectors (do not edit by hand)
android-sync/test/AegisCryptoVectorTest.kt <- zero-dependency JVM conformance test
android-sync/test/E2EDriver.kt          <- live end-to-end test (needs a running worker + org.json)
android-sync/java/org/aegis/sync/AegisCrypto.java <- JAVA twin of the crypto core (16/16 vectors)
android-sync/java/org/aegis/sync/AegisSyncClient.java <- JAVA twin of the protocol client (13/13 E2E)
android-sync/java/org/aegis/sync/AegisTestVectors.java <- generated, identical to the .kt twin
android-sync/java/org/aegis/sync/AegisCryptoVectorTest.java <- zero-dependency test for the Java port
scripts/patch-foundry/gen-aegis-patches.sh <- Patch Foundry engine: asserted specs -> .patch files
scripts/patch-foundry/verify-gn-args.sh <- confirms hardening GN args exist in your tag
scripts/patch-foundry/fetch-chromium-src.sh <- cheap source fetch (authoring machine, no build)
scripts/patch-foundry/gn-args-hardening.conf <- batch-1 hardening GN args (self-verifying)
scripts/patch-foundry/edits/000-TEMPLATE.conf <- spec format + conventions
scripts/patch-foundry/additions/         <- whole NEW files to inject (no anchors needed)
scripts/aegis-shield/gen_blocklist.py    <- Aegis Shield data generator (EasyList+EasyPrivacy)
docs/SHIELD.md                           <- Aegis Shield design, policy and scope
docs/PATCH_FOUNDRY.md                    <- the $0 Codespace patch-authoring guide
docs/PUSH_TO_GITHUB.md                   <- how to push this folder to GitHub (3 ways)
docs/Aegis_Free_Android_Build_Plan.docx  <- the full plan document (+ .pdf)
docs/aegis-architecture.png              <- architecture diagram
aegis-sync/                              <- sync backend (Cloudflare Worker + D1, deploy free)
.github/workflows/deploy-sync.yml        <- optional one-click backend deploy from CI
.github/workflows/build-aegis-linux.yml  <- Linux x64 desktop build (Phase D1, docs/DESKTOP_PLAN.md)
landing/                                 <- public landing page (aegis-browser.pages.dev)
.github/workflows/deploy-landing.yml     <- auto-deploys landing/ on every push that touches it
README.md                               <- this file
```

## Build it yourself (first APK in ~1 day of wall time)

The pipeline is self-service — the same recipe that produces the public
releases:

1. **Create a public GitHub repository** (for example `aegis-browser-android`).
   Public is what makes Actions minutes unlimited; a private repo only gets
   2,000 min/month, less than one Chromium build needs.

2. **Copy the files** from this repository into it (keep the folder layout,
   including the hidden `.github/` directory).

3. **Create the release keystore once** (any machine with a JDK):
   ```
   KEYSTORE_PASSWORD='YourStrongPassword1!' KEY_PASSWORD='YourStrongPassword1!' \
       bash scripts/create-release-keystore.sh
   ```
   Back the file up — a lost keystore means uninstall/reinstall instead of
   in-place updates.

4. **Add four repository Secrets** (Settings -> Secrets and variables -> Actions):
   - `ANDROID_KEYSTORE_BASE64`  (printed by the script)
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`        (`aegis`)
   - `ANDROID_KEY_PASSWORD`

5. **Start a build**: Actions tab -> "Build Aegis Browser APK" -> Run workflow.
   Leaving `chromium_version` empty builds the latest Chrome stable;
   `build_target` stays `chrome_public_apk`.

6. **Give it 4-7 hours.** The free runner is honest but slow: 4 cores,
   16 GB RAM, no build cache between runs (the plan document explains why).

7. **Install**: open the draft Release, download `AegisBrowser_..._arm64.apk`,
   copy it to the phone (or download it directly in the phone's browser),
   allow "Install unknown apps" for that browser, and install.

## Verify the sync client (optional, ~10 minutes)

Both layers of the Kotlin client can be re-verified on any machine with a JDK
and the Kotlin compiler (`brew install kotlin` / `sdk install kotlin` /
`snap install kotlin --classic`).

**Layer 1 - crypto conformance (no server needed):**

```
kotlinc android-sync/AegisCrypto.kt \
        android-sync/test/AegisCryptoVectors.kt \
        android-sync/test/AegisCryptoVectorTest.kt \
        -include-runtime -d aegis-crypto-test.jar
java -jar aegis-crypto-test.jar
```

Expected last line: `ALL 16 CHECKS PASSED`. The vectors are generated by
`scripts/gen-crypto-vectors.mjs`, which cross-checks every value against
WebCrypto and Node's native PBKDF2 before writing the file - re-run it any
time you change the crypto.

**Layer 2 - live protocol E2E (needs the org.json jar + a running worker):**

```
# start a local worker from the aegis-sync backend (any machine with Node 18+):
#   cd aegis-sync && npm ci && npx wrangler d1 execute DB --local --file=schema.sql && npx wrangler dev
# or point AEGIS_BASE at your deployed workers.dev URL

kotlinc android-sync/AegisCrypto.kt android-sync/AegisSyncClient.kt \
        android-sync/test/E2EDriver.kt \
        -cp json-20240303.jar -include-runtime -d aegis-e2e.jar
AEGIS_BASE=http://127.0.0.1:8787 java -cp aegis-e2e.jar:json-20240303.jar org.aegis.sync.E2EDriverKt
```

Expected last line: `ALL 13 E2E STEPS PASSED`. (`json-20240303.jar` is the
one-file org.json release from Maven Central; on Android org.json is built in.)

**The Java twin - same guarantees, no Kotlin toolchain** (any JDK 17+):

```
javac -encoding UTF-8 -d out android-sync/java/org/aegis/sync/*.java
java -cp out org.aegis.sync.AegisCryptoVectorTest           # -> ALL 16 CHECKS PASSED
java -cp out:json-20240303.jar org.aegis.sync.E2EDriver     # -> ALL 13 E2E STEPS PASSED
```

(org.json is only needed for the E2E run; the crypto test is fully
self-contained. The vector twins are regenerated together with
`node scripts/gen-crypto-vectors.mjs`.)

That closes everything Phase 3 needs before UI integration - the remaining work
(Sync settings screen, device list UI, menu hooks) is authored against the
pinned CI tag with the Patch Foundry (see `docs/PATCH_FOUNDRY.md`) and lands
in `aegis/patches/` per the plan document, section 6.2. The Java client in
`android-sync/java/` is the code those patches will wire into the settings UI.

## What the workflow does, in order

1. Frees ~25 GB of disk on the runner; adds 16 GB swap (link spikes).
2. Installs JDK 17.
3. Resolves the Chromium version (the workflow input, or latest Chrome stable
   via the public `versionhistory.googleapis.com` API).
4. Clones `depot_tools`; `fetch --no-history --nohooks chromium` (saves ~20 GB
   of git history); adds `target_os = ['linux','android']`.
5. Checks out the chosen Chromium tag; `gclient sync` downloads dependencies
   AND the Android SDK/NDK (via CIPD); `gclient runhooks`.
6. Applies `aegis/patches/*.patch` in order; runs `aegis/branding/apply.sh`.
7. `gn gen out/arm64` with the low-RAM args from `aegis/ci/arm64.gn`
   (unofficial build: no ThinLTO, no PGO, zero debug symbols).
8. `autoninja -C out/arm64 chrome_public_apk` -> `out/arm64/apks/ChromePublic.apk`.
9. zipalign + `apksigner` with the release keystore -> `AegisBrowser_<ver>_arm64_r<N>.apk`.
10. Uploads the artifact (1-day retention) and attaches it to a DRAFT release.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Build fails at 5h55m with timeout | 6 h GitHub job cap | Re-run (free). If it repeats, keep `build_target=chrome_public_apk` (the default) and LTO/PGO off in `arm64.gn`. |
| "No space left on device" | Disk full | Keep `symbol_level=0`; no arm32/x86 targets; keep patches lean. The workflow already frees ~25 GB and works on `/mnt`. |
| `git fetch tag` fails | Tag not published yet / typo | Check the version on `chromiumdash.appspot.com`; pin the exact tag like `156.0.8078.79`. |
| Patch fails to apply | Made against a different Chromium version | Regenerate the patch from a checkout of the same tag (`git diff > patch`). |
| App "not installed" on phone | APK vs device ABI or old Android | Builds are arm64, Android 8+. x86_64 emulators cannot run them. |
| Wrong signature / update rejected | Keystore changed | Uninstall and reinstall; the keystore is never regenerated. |
| Launcher still says "Chromium" | Branding hook found no match | Check the build log's "Branding:" lines; if empty, the resource layout moved - move the change into a proper patch in `aegis/patches/`. |

## Notes on "free"

- GitHub Actions is free with **unlimited minutes for public repositories**
  (standard runners). Patches and branding are publicly visible - that is
  inherent to the free tier. A private paid path (a $1-3 spot VM per build,
  see the `aegis-build/` GCP kit) still exists if it is ever needed.
- GitHub Release assets: 2 GB per file (an APK is ~150-250 MB) - effectively
  unlimited free storage for builds.
- The sync backend (`aegis-sync`) runs on Cloudflare Workers + D1 free tiers
  and is unchanged by this pivot.
- Android-only means Phase 4 (Windows) of the blueprint is deferred, not
  cancelled: the patch series accumulating in `aegis/patches/` is portable
  to desktop builds later.

## Status of each file

| File | Status |
| --- | --- |
| `.github/workflows/build-aegis-apk.yml` | Ready to run; every step uses documented Chromium/Android tooling. |
| `aegis/ci/arm64.gn` | Known-good low-RAM arg set (same family of flags public forks use). |
| `aegis/branding/apply.sh` | v1.1: replaces `app_name` wherever it is defined under `chrome/android/java`; idempotent. |
| `aegis/patches/README.md` | Ready. |
| `android-sync/AegisCrypto.kt` | **Vector-tested: 16/16 checks pass** (WebCrypto + Node cross-checked vectors; pure JVM, zero dependencies). |
| `android-sync/AegisSyncClient.kt` | **Live-tested: 13/13 E2E steps pass** against a locally-run worker (register, two-device push/pull, conflict, rotation with vault-key survival, deletion). Needs `org.json` (bundled with Android). |
| `android-sync/java/org/aegis/sync/AegisCrypto.java` | **Vector-tested: 16/16 checks pass** against the same generated vectors (generator emits .kt + .java twins). |
| `android-sync/java/org/aegis/sync/AegisSyncClient.java` | **Live-tested: 13/13 E2E steps pass** (same acceptance sequence as the Kotlin original). Drop-in for chrome_java. |
| `android-sync/java/org/aegis/sync/AegisCryptoVectorTest.java` | Zero-dependency JVM runner for the Java port; `ALL 16 CHECKS PASSED` verified on OpenJDK 21. |
| `android-sync/test/AegisCryptoVectorTest.kt` | Zero-dependency JVM runner for the generated vectors. |
| `android-sync/test/AegisCryptoVectors.kt` | Generated by `scripts/gen-crypto-vectors.mjs`; do not edit by hand. |
| `android-sync/test/E2EDriver.kt` | Live E2E driver; needs a running worker + org.json jar on desktop JVM. |
| `scripts/create-release-keystore.sh` | Ready; requires any locally installed JDK. |
| `scripts/gen-crypto-vectors.mjs` | Ready; requires Node 18+. Self-validating (WebCrypto + Node PBKDF2 cross-checks). Emits the .kt and .java vector twins. |
| `scripts/patch-foundry/gen-aegis-patches.sh` | **Tested end-to-end on a mock tree**: dry-run, clean patch emission (edit + addition), clean `git apply --3way` on a pristine checkout, loud abort with tree restore on a bad anchor. |
| `scripts/patch-foundry/verify-gn-args.sh` | Tested on a mock tree (FOUND with location / MISSING reporting, correct exit code). |
| `scripts/patch-foundry/fetch-chromium-src.sh` | Ready; standard shallow depot_tools flow, no hooks (authoring only, no build). |
| `scripts/patch-foundry/gn-args-hardening.conf` | Batch-1 hardening args; MUST be filtered through verify-gn-args.sh per tag (some args get renamed/dropped upstream). |
| `docs/PATCH_FOUNDRY.md` | Ready; $0 Codespace authoring loop + spec-writing rules. |

## Changelog

- **v1.3.2** - Phase D1 launched (docs/DESKTOP_PLAN.md): a Linux x64 desktop
  pipeline. New `aegis/ci/linux_x64.gn` (the same low-RAM arg family as the
  Android build), `.github/workflows/build-aegis-linux.yml` (builds `chrome`
  + the proper `chrome/installer/linux:stable_deb` package, then repacks the
  deb into a portable zip; draft release `linux-<ver>-r<N>`), and
  `aegis/branding/apply_desktop.sh` (format-safe English UI rename to
  "Aegis"; non-English locales keep upstream strings for now). The SAME patch
  series applies - desktop Chromium shares the sources, including the Shield.
  Ground-truthed @ 156.0.8078.79 (`linux_package("stable")` targets verified
  via Gitiles).

- **v1.3.1** - The public landing page is live: **https://aegis-browser.pages.dev**
  (Cloudflare Pages, free tier, $0). Built strictly on the brand kit in
  `docs/BRAND.md` - the traced shield mark as inline SVG, Montserrat, the
  monochrome ink ramp, dark-first, hard angles. Sections: hero, Aegis Shield
  (92,957-host network blocking stats), feature grid with honest per-feature
  status tags, the Aegis ecosystem cards (AM Products / CoreX / OS / AEGIS
  SOC), download steps, and a 1280x640 social preview (`og-image.png`) that
  doubles as the GitHub repo social image. Source lives in `landing/` and
  auto-deploys from CI (`.github/workflows/deploy-landing.yml`) on every push
  that touches it.
- **v1.3.0** - Aegis Shield v1 (network-level ad/tracker blocking,
  92,957 hosts, first-party safe) + the Aegis Ecosystem settings page
  (AM Products / CoreX / AEGIS OS / AEGIS SOC). Patches 052/054/070
  emitted via the Patch Foundry against Chromium 156.0.8078.79 and
  re-verified with `git apply --check` on a tag-faithful mock tree;
  blocklist semantics covered by a 9-case test. Sync UI re-planned to
  batch 3.
- **v1.2.2** - Real branding: the Aegis shield-"A" mark is now the launcher
  icon. Master logo committed (`aegis/branding/logo/`), the mark traced into
  an Android VectorDrawable (IoU 0.989 vs the master) and the full icon set
  generated (`scripts/branding/make_icons.py`): adaptive background/foreground
  + legacy + Android 13 monochrome, all densities. Brand kit (palette,
  Montserrat typography, design language) documented in `docs/BRAND.md`.
  `aegis/branding/apply.sh` now installs the icons into
  `chrome/android/java/res_chromium_base/` (layout verified @ 156.0.8078.79).
  Takes effect from the next CI build.
- **v1.2.1** - Shipped the first real patch series: `aegis/patches/010-040`
  (third-party cookies blocked by default, search suggestions off, DNT on,
  metrics opt-in only). All anchors ground-truthed against Chromium
  `156.0.8078.79`; every emitted patch re-verified with `git apply --check`.
  See `docs/PATCHES_BATCH_1.md`. Also fixed a Patch Foundry engine bug:
  multi-line `EDITn_OLD` literals were miscounted by the line-based grep
  pre-check; the exact Python check is now authoritative.
- **v1.2** - Java twin of the sync client for direct chrome_java
  integration: `AegisCrypto.java` passes the same 16 generated vectors,
  `AegisSyncClient.java` passes the same 13 live E2E steps (re-verified on
  OpenJDK 21 against a locally-run worker). Added the Patch Foundry
  (asserted-edit engine, GN-args verifier, cheap source fetch, Codespace
  guide) and tested the engine end-to-end on a mock tree - clean patch
  emission, clean `git apply --3way`, loud abort with tree restore.
- **v1.1** - Split crypto into `AegisCrypto.kt` and fixed the PBKDF2
  char[]-encoding bug (raw-byte RFC 2898 implementation); added the
  self-validating vector generator and the 16-check JVM conformance suite
  (all passing); added the live E2E driver and fixed what it caught
  (`register()` now returns the server response) - 13/13 steps pass against a
  locally-run worker; hardened the branding hook.
- **v1.0** - Initial kit: CI workflow, low-RAM gn args, keystore script,
  branding hook, draft Kotlin sync client.
