# Aegis Browser — Patch Batch 2 Plan: Sync UI integration

Status: **ground-truthed @ tag `156.0.8078.79` (2026-11-01), not yet emitted.**
Batch 1 (privacy defaults) is in CI; this batch wires the tested `android-sync`
client into the Android Settings UI.

## Ground truth (verified against the real tag, not memory)

Chromium 156 moved things around — the old paths from earlier docs are stale:

| Thing | Real path @ 156 |
| --- | --- |
| Main settings screen | `chrome/android/java/res/xml/main_preferences.xml` (note: `java/res`, **not** `java/src/res`; file is `main_preferences.xml`, **not** `main_settings.xml`) |
| Settings fragment host | `chrome/android/java/src/org/chromium/chrome/browser/settings/MainSettings.java` |
| Fragment base class | `ChromeBaseSettingsFragment` (`org.chromium.chrome.browser.settings`) — see `AboutChromeSettings.java` as the smallest working example |
| Required fragment overrides | `onCreatePreferences` (`SettingsUtils.addPreferencesFromResource(this, R.xml.<page>)`), `getPageTitle()` returning `MonotonicObservableSupplier<String>`, `getMainMenuKey()` |
| Settings page XMLs live in | `chrome/android/java/res/xml/` (21 files, incl. `about_chrome_preferences.xml`, `privacy_preferences.xml`) |

## Patch plan (2 patches + 1 hook change)

### Patch 050 — settings entry (`main_preferences.xml`, edit)

Insert an "Aegis Sync" item as the **last entry of the Basics section**
(per-group orders in Basics are 6,7,8,9,10; 11 is free).

- Anchor (verified `str.count(...) == 1` on the tag's file):

```
    <org.chromium.components.browser_ui.settings.ChromeBasePreference
        android:key="default_browser"
        android:order="10"
        android:title="@string/default_browser_title" />
```

- Inserted **before** the anchor (i.e. after `default_browser`), `order="11"`:

```
    <org.chromium.components.browser_ui.settings.ChromeBasePreference
        android:fragment="org.chromium.chrome.browser.aegis.AegisSyncSettings"
        android:key="aegis_sync"
        android:order="11"
        android:title="Aegis Sync" />
```

`android:fragment` is a plain string resolved only on click, so this patch
compiles even if shipped alone. Follow-up (nice-to-have): replace the literal
title with `@string/aegis_sync_title` added to
`chrome/android/java/res/values/android_ui_strings.xml` (separate edit, anchor
on any stable neighboring string block).

### Patch 060 — fragment page (new files, hook-copied)

Patch Foundry edits existing files only, so new files ship via the **branding
hook** (same pattern as `aegis/branding/apply.sh`): extend it to copy
`aegis/extra-src/**` into `$WORK/src/` before `gn gen`. New files:

1. `chrome/android/java/src/org/chromium/chrome/browser/aegis/AegisSyncSettings.java`
   — skeleton (modeled on `AboutChromeSettings`):

   ```java
   package org.chromium.chrome.browser.aegis;

   public class AegisSyncSettings extends ChromeBaseSettingsFragment
           implements EmbeddableSettingsPage {
       private final SettableMonotonicObservableSupplier<String> mPageTitle =
               new SettableMonotonicObservableSupplier<>();

       @Override
       public void onCreatePreferences(Bundle bundle, String s) {
           mPageTitle.set("Aegis Sync");
           SettingsUtils.addPreferencesFromResource(this, R.xml.aegis_sync_preferences);
       }
       // getPageTitle(), getMainMenuKey() -> "aegis_sync", getAnimationType()
   }
   ```

2. `chrome/android/java/res/xml/aegis_sync_preferences.xml` — preference page:
   server URL, account email, status line, buttons bound to the
   `android-sync/` client (register / login / push / pull / delete account).
   `android-sync` already passes 16/16 crypto vectors + 13/13 live E2E against
   the production worker, so the UI layer is a thin wrapper around it.

## Verification recipe (same as batch 1)

1. Fetch the two touched tag files via Gitiles `?format=TEXT` into
   `/tmp/foundry/<tag>/`, confirm every `EDITn_OLD` literal has
   `str.count == 1`.
2. Emit with `scripts/patch-foundry/gen-aegis-patches.sh`, then
   `git apply --check` each patch against the tag's mock tree.
3. Document in `docs/PATCHES_BATCH_2.md`, bump README changelog, push, and
   let CI rebuild.

## Risks / notes

- If Chrome stable has moved past `156.0.8078.79` when this batch is emitted,
  re-run the ground-truth step first (file paths may move again; they did in
  the last ~20 releases).
- `R.xml.aegis_sync_preferences` requires the XML to be under
  `chrome/android/java/res/xml/` (Chrome's own res package) — hook-copied
  files are included because the target is already a `java` resource package.
- Do **not** reuse Google's `ManageSyncSettings`; Aegis sync is fully
  independent of Chrome's sign-in flow (by design, E2E-encrypted, no Google
  account involved).
