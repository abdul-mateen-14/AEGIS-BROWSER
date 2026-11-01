# Aegis Browser - Free Android Build Kit (v1.2)

Everything you need to build **Aegis Browser for Android (arm64) for $0/month**
using GitHub's free hosted runners for public repositories.

- No local Chromium compilation (your 8 GB laptop only ever edits text files)
- No cloud VMs, no spot instances, no build services
- One command to start a build: open GitHub -> Actions -> "Build Aegis Browser APK" -> Run
- Result: a signed APK attached to a draft GitHub Release, 4-7 hours later

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

This folder IS the repository - see `docs/PUSH_TO_GITHUB.md` for three ways
to get it onto GitHub (including a fully automatic one).

```
.github/workflows/build-aegis-apk.yml   <- the free CI pipeline (the core of this kit)
aegis/ci/arm64.gn                       <- low-RAM build flags (documented inline)
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
docs/PATCH_FOUNDRY.md                    <- the $0 Codespace patch-authoring guide
docs/PUSH_TO_GITHUB.md                   <- how to push this folder to GitHub (3 ways)
docs/Aegis_Free_Android_Build_Plan.docx  <- the full plan document (+ .pdf)
docs/aegis-architecture.png              <- architecture diagram
aegis-sync/                              <- sync backend (Cloudflare Worker + D1, deploy free)
.github/workflows/deploy-sync.yml        <- optional one-click backend deploy from CI
README.md                               <- this file
```

## Quickstart (first APK in ~1 day of wall time)

1. **Create a public GitHub repository** (e.g. `aegis-browser-android`).
   Public is required for free unlimited Actions minutes; private repos only
   get 2,000 min/month, which is less than ONE Chromium build.

2. **Copy the files** from this kit into the repo (keep the folder layout,
   including the hidden `.github/` directory).

3. **Create your release keystore locally** (one-time, needs any JDK):
   ```
   KEYSTORE_PASSWORD='YourStrongPassword1!' KEY_PASSWORD='YourStrongPassword1!' \
       bash scripts/create-release-keystore.sh
   ```
   Back the file up in your password manager. If you lose it you must
   uninstall/reinstall the app instead of updating it.

4. **Add four repository Secrets** (Settings -> Secrets and variables -> Actions):
   - `ANDROID_KEYSTORE_BASE64`  (printed by the script)
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`        (`aegis`)
   - `ANDROID_KEY_PASSWORD`

5. **Run the build**: Actions tab -> "Build Aegis Browser APK" -> Run workflow.
   Leave `chromium_version` empty to build the latest Chrome stable, and
   `build_target` as `chrome_public_apk`.

6. **Wait 4-7 hours.** The runner is free but slow (4 cores, 16 GB RAM, no
   build cache between runs - see the plan document for why).

7. **Install**: open the draft Release, download `AegisBrowser_..._arm64.apk`,
   copy it to your phone (or just download it in the phone's browser), enable
   "Install unknown apps" for that browser, and install.

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
(Sync settings screen, device list UI, menu hooks) is authored against your
pinned CI tag with the Patch Foundry (see `docs/PATCH_FOUNDRY.md`) and lands
in `aegis/patches/` per the plan document, section 6.2. The Java client in
`android-sync/java/` is the code those patches will wire into the settings UI.

## What the workflow does, in order

1. Frees ~25 GB of disk on the runner; adds 16 GB swap (link spikes).
2. Installs JDK 17.
3. Resolves the Chromium version (your input, or latest Chrome stable via the
   public `versionhistory.googleapis.com` API).
4. Clones `depot_tools`; `fetch --no-history --nohooks chromium` (saves ~20 GB
   of git history); adds `target_os = ['linux','android']`.
5. Checks out your chosen Chromium tag; `gclient sync` downloads dependencies
   AND the Android SDK/NDK (via CIPD); `gclient runhooks`.
6. Applies `aegis/patches/*.patch` in order; runs `aegis/branding/apply.sh`.
7. `gn gen out/arm64` with the low-RAM args from `aegis/ci/arm64.gn`
   (unofficial build: no ThinLTO, no PGO, zero debug symbols).
8. `autoninja -C out/arm64 chrome_public_apk` -> `out/arm64/apks/ChromePublic.apk`.
9. zipalign + `apksigner` with YOUR keystore -> `AegisBrowser_<ver>_arm64_r<N>.apk`.
10. Uploads the artifact (1-day retention) and attaches it to a DRAFT release.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Build fails at 5h55m with timeout | 6 h GitHub job cap | Just re-run (free). If it keeps happening, keep `build_target=chrome_public_apk` (already default) and make sure you did not re-enable LTO/PGO in `arm64.gn`. |
| "No space left on device" | Disk full | Keep `symbol_level=0`; do not add arm32/x86 targets; keep patches lean. The workflow already frees ~25 GB and works on `/mnt`. |
| `git fetch tag` fails | Tag not published yet / typo | Check the version on `chromiumdash.appspot.com`; pin the exact tag like `156.0.8078.79`. |
| Patch fails to apply | Made against a different Chromium version | Regenerate the patch from a checkout of the same tag (`git diff > patch`). |
| App "not installed" on phone | APK vs device ABI or old Android | Kit builds arm64 for Android 8+. Emulators (x86_64) cannot run it. |
| Wrong signature / update rejected | Keystore changed | You must uninstall and reinstall. Never regenerate the keystore. |
| Launcher still says "Chromium" | Branding hook found no match | Check the build log's "Branding:" lines; if empty, the resource layout moved - move the change into a proper patch in `aegis/patches/`. |

## Notes on "free"

- GitHub Actions is free with **unlimited minutes for public repositories**
  (standard runners). Your patches and branding will be publicly visible -
  that is inherent to the free tier. If you ever need privacy, the paid path
  (a $1-3 spot VM per build, see `aegis-build/` GCP kit) still exists.
- GitHub Release assets: 2 GB per file (an APK is ~150-250 MB) - effectively
  unlimited free storage for your builds.
- The sync backend (`aegis-sync`) runs on Cloudflare Workers + D1 free tiers
  and is unchanged by this pivot.
- Android-only means Phase 4 (Windows) of the blueprint is deferred, not
  cancelled: the patch series you accumulate in `aegis/patches/` is portable
  to desktop builds later.

## Status of each file

| File | Status |
| --- | --- |
| `.github/workflows/build-aegis-apk.yml` | Ready to run; every step uses documented Chromium/Android tooling. First real run is the true test - expect to debug 1-2 minor failures (see plan document, Section 9). |
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
