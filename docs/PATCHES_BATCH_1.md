# Batch 1 privacy patches - ground-truthed against Chromium 156.0.8078.79

Generated with the Patch Foundry on 2026-11-01. Every anchor was verified to
match **exactly once** against the real file content of Chromium tag
`156.0.8078.79` (what the CI resolves today from the versionhistory API),
and every emitted patch was re-verified with `git apply --check` on a
pristine tree of that tag's files.

| Patch | Touches | Change | Why |
| --- | --- | --- | --- |
| `010-block-third-party-cookies.patch` | `components/content_settings/core/browser/cookie_settings.cc` | `kCookieControlsMode` default `kIncognitoOnly` → `kBlockThirdParty` | Third-party cookies blocked by default, not just in incognito. Users can relax per-site. |
| `020-search-suggest-off.patch` | `chrome/browser/profiles/profile.cc` | `kSearchSuggestEnabled` default `true` → `false` | Address-bar suggestions send every keystroke to the search provider. Off by default; re-enableable in settings. |
| `030-do-not-track-on.patch` | `chrome/browser/ui/browser_ui_prefs.cc` | `kEnableDoNotTrack` default `false` → `true` | DNT header on by default. Upstream removed the settings toggle in this era but the pref (and the header emitted from it) still exists and works. Zero breakage risk. |
| `040-metrics-off-by-default.patch` | `chrome/browser/browser_process_impl.cc` | `kMetricsReportingEnabled` default from `GetCollectStatsConsent()` → literal `false` | Deterministic opt-in-only metrics. (Unofficial builds already can't upload without API keys - this makes the local state honest too.) Users can still enable it. |

## Deliberately NOT in batch 1

- **DNT settings toggle UI**: gone upstream; nothing to re-add without
  building new UI. The default flip is enough.
- **URL-keyed anonymized data collection** (`RegisterUrlKeyedAnonymizedDataCollectionPref`):
  the registration site could not be located at this tag (moved/renamed by
  upstream refactorings). On an unofficial build without Google API keys the
  UKM upload path is inert anyway. Revisit in a later batch from a full tree.
- **GN hardening args** (`gn-args-hardening.conf`): must be filtered through
  `verify-gn-args.sh` inside a full checkout first (Codespace loop,
  `docs/PATCH_FOUNDRY.md`).

## Regenerating on a newer tag

Patches are literal-default flips; they usually survive minor-version bumps.
If a CI run reports a patch failing to apply:

1. Open a Codespace (or WSL2 with ~35 GB free).
2. `scripts/patch-foundry/fetch-chromium-src.sh <new-tag>`
3. Re-run `gen-aegis-patches.sh` - a moved anchor aborts loudly and tells
   you the file and pattern; re-point it (usually a 5-minute fix).
4. Commit the regenerated `.patch` files.

## Engine fix shipped with this batch

`gen-aegis-patches.sh` had a bug found while authoring this batch: the
line-based `grep -F` pre-check split multi-line `EDITn_OLD` literals into
separate patterns and miscounted them (a 3-line literal reported "matched 4
times"). The exact Python `str.count` check is now the single source of
truth, so multi-line literals are fully supported in both dry-run and emit
modes.
