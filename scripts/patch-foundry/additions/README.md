# additions/ - whole files copied into the Chromium tree by the Patch Foundry

Files here are NEW source files that `gen-aegis-patches.sh` copies into the
checkout at an `ADDn_DEST` path declared in an `edits/*.conf` spec, and from
there they end up inside the generated `.patch` file. New files need no
anchor against a moving Chromium tree, so this is the preferred delivery
mechanism for Aegis's own code (the rules in `aegis/patches/README.md`
still apply: keep each addition reviewable and one concern per patch).

## Current contents

- `aegis/AegisEcosystemSettings.java` — the Aegis Ecosystem settings
  page (patch 054); opens the AM Products / CoreX / AEGIS OS / AEGIS SOC
  sites. Modeled on `AboutChromeSettings`.
- `aegis/res/xml/aegis_ecosystem_preferences.xml` — its preference XML
  (patch 054).
- `aegis/shield/aegis_shield.h` / `.cc` — the Aegis Shield engine
  (patch 070).
- `aegis/shield/aegis_shield_blocklist.h` — shield data header
  (patch 070).
- `aegis/shield/aegis_shield_blocklist.cc` — **generated** by
  `scripts/aegis-shield/gen_blocklist.py` (EasyList + EasyPrivacy); do
  not edit by hand (patch 070).

## Planned (batch 3, sync UI)

The tested sync client already lives in `android-sync/java/`:

- `org.aegis.sync.AegisCrypto` - crypto core, vector-tested (16/16)
- `org.aegis.sync.AegisSyncClient` - protocol client, live-tested (13/13)

The first UI integration patch series will add, from here:

- `org.aegis.sync.AegisSession` - token/vault-key storage on top of the
  Android Keystore (never persists the vault key in plaintext)
- `org.aegis.sync.SyncSettingsFragment` - the Settings > Aegis Sync screen
  (login, sync-now, device list with revocation)

...plus small anchored edits that register the fragment in Chromium's
settings screen and a menu hook. Those anchors can only be written
reliably AFTER the first CI run against a pinned tag, which is why they
ship as specs in `edits/` rather than as a blind patch today.
