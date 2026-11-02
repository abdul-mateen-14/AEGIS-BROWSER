# Aegis Browser — Patch Batch 2 Plan: Ecosystem + Shield (SHIPPED), Sync UI (batch 3)

Status update (2026-11-01): **batch 2 shipped as patches 052 + 054 + 070.**
The sync UI moved to **batch 3** — the Ecosystem entry took the settings
slot first. Everything below reflects what was actually built.

## Batch 2 (shipped) — three patches, all ground-truthed @ tag `156.0.8078.79`

| Patch | Does | Anchors (all verified `count == 1`) |
| --- | --- | --- |
| `052-aegis-ecosystem-menu.patch` | Appends an **Aegis** category (order 99) with the **Aegis Ecosystem** entry at the bottom of `main_preferences.xml` | the `about_chrome` entry + `</PreferenceScreen>` tail |
| `054-aegis-ecosystem-page.patch` | Adds `AegisEcosystemSettings.java` + `aegis_ecosystem_preferences.xml` (AM Products / CoreX / AEGIS OS / AEGIS SOC links) and wires them into the build | `chrome_java_sources.gni` line 60, `chrome_java_resources.gni` line 661 |
| `070-aegis-shield-engine.patch` | **Aegis Shield**: `services/network/aegis_shield{,_blocklist}.{h,cc}` + hook in `NetworkServiceNetworkDelegate::OnBeforeURLRequest` + `services/network/BUILD.gn` sources | `network_change_manager.h` source line, `cookie_manager.h` include, `DCHECK(request)` + `URLLoader::ForRequest` pair |

Notes discovered during ground-truthing (the plan below was corrected):

- The earlier claim "order 11 is free in Basics" was **wrong** —
  `autofill_and_passwords` uses order 11. That is why the Aegis section is
  its own category at the bottom (order 99) instead of squeezing into
  Basics.
- Java sources are an **explicit list** in
  `chrome/android/chrome_java_sources.gni` (no glob) — hook-copying a new
  `.java` file alone would NOT compile it. Same for res files in
  `chrome_java_resources.gni`. Patch 054 edits both lists.
- Shield hook verified live at this tag:
  `net/url_request/url_request.cc:633` calls
  `network_delegate()->NotifyBeforeURLRequest` and
  `net::URLRequest::initiator()` exists (`url_request.h:404`).

Shield design, policy, data sources and regeneration: see `docs/SHIELD.md`.
Emission + verification pipeline: the foundry specs live in
`scripts/patch-foundry/edits/` (052/054/070), the shield data generator in
`scripts/aegis-shield/`, the semantics test in
`scripts/test-shield-semantics.py`.

## Batch 3 (planned) — Sync UI integration

The tested `android-sync` client (16/16 crypto vectors, 13/13 live E2E)
still needs its settings screen. Plan, adjusted for batch 2's changes:

### Patch 050 — settings entry (`main_preferences.xml`, edit)

Insert an "Aegis Sync" item into the **Aegis category** that patch 052
added (NOT into Basics — order 11 there is taken by autofill), as the
second entry of that category (order 2):

- Anchor (applies after `052`, which is alphabetical-first):

```
    <org.chromium.components.browser_ui.settings.ChromeBasePreference
        android:fragment="org.chromium.chrome.browser.aegis.AegisEcosystemSettings"
        android:key="aegis_ecosystem"
        android:order="1"
        android:title="Aegis Ecosystem"
        android:summary="AM Products · CoreX · AEGIS OS · AEGIS SOC" />
```

- Inserted **after** the anchor, `order="2"`:

```
    <org.chromium.components.browser_ui.settings.ChromeBasePreference
        android:fragment="org.chromium.chrome.browser.aegis.AegisSyncSettings"
        android:key="aegis_sync"
        android:order="2"
        android:title="Aegis Sync" />
```

### Patch 056 — fragment page (new files, additions)

1. `chrome/android/java/src/org/chromium/chrome/browser/aegis/AegisSyncSettings.java`
   — modeled exactly on `AboutChromeSettings` (same @NullMarked,
   `ObservableSuppliers.createMonotonic()`, `getMainMenuKey()`,
   `getAnimationType()` pattern as the shipped
   `AegisEcosystemSettings.java`).
2. `chrome/android/java/res/xml/aegis_sync_preferences.xml` — server URL,
   account email, status line, buttons bound to `android-sync/`.
3. Aegis sync Java classes (`AegisCrypto.java`, `AegisSyncClient.java`
   twins) copied into the same `aegis/` package, registered in
   `chrome_java_sources.gni` (same anchor pattern as patch 054).

## Verification recipe (used for batch 2, reuse for batch 3)

1. Fetch touched tag files via Gitiles `?format=TEXT` into
   `/tmp/foundry/b3/`, confirm every `EDITn_OLD` literal has
   `str.count == 1`.
2. Build a mock git tree containing exactly those files, emit with
   `scripts/patch-foundry/gen-aegis-patches.sh`, then `git apply --check`
   each patch against a fresh mock copy.
3. Document, bump README changelog, push, let CI rebuild.

## Risks / notes

- If Chrome stable moves past `156.0.8078.79`, re-run the ground-truth
  step first (file paths move; `main_preferences.xml` already moved once).
- Do **not** reuse Google's `ManageSyncSettings`; Aegis sync is fully
  independent of Chrome's sign-in flow (by design, E2E-encrypted, no
  Google account involved).
- The shield is always-on in v1 (no cross-process pref plumbing yet);
  a per-site shield toggle is queued behind the sync UI.
