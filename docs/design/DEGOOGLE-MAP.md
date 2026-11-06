# De-Google map — default services off, help links self-hosted

User directives (2026-11-06):
- No Google services as default (no Gemini/GenAI surfaces, no browser-embedded Google
  sign-in, no Google Password Manager branding). Google SERVICES remain available as
  websites only (e.g. gemini.google.com opens normally).
- Google search engine STAYS the default search engine (patch 020 already keeps
  suggestions off).
- All help/support links must open the project's own docs on the landing page.
- Ship together with the Liquid Glass UI series as ONE update wave.

Landing docs URL: https://aegis-browser.pages.dev (help section: /help/).
Aegis ID backend (LIVE): https://aegis-sync.abdulmateen14.workers.dev
  (E2E-encrypted sync, Cloudflare Workers + D1, client example: aegis-sync/client-example/)

## Patch series

| Patch | Contents | Status |
|---|---|---|
| `085-degoogle-help-links.patch` | chrome/common/url_constants.h: 145 Google URLs (140 distinct: support/account/activity/wallet/gemini/...) -> https://aegis-browser.pages.dev/help/ (topic params preserved). Regenerated count-asserted; broken a/a//b/b/ path header fixed | DONE (gen: scripts/gen_patch_085.py) |
| `073-aegis-degoogle-defaults.patch` | Gemini/Glic UI entries, Compose, sign-in prompt/preview features -> DISABLED_BY_DEFAULT | DONE |
| `074-aegis-passwords-rebrand.patch` | "Google Password Manager" -> "Aegis Passwords" (130 strings, 4 grd/grdp) | DONE |
| `072-aegis-help-links.patch` | Android help/feedback/legal URL seams (Help & feedback launcher, NTP, site settings, Safe Browsing, Safety Hub, personal_context) -> landing help/legal | DONE |
| `087-aegis-id-signin.patch` | Aegis ID sign-in entry + device status bound to aegis-sync API, hosted in the existing Aegis ecosystem page (054 seam: chrome/android/java/src/org/chromium/chrome/browser/aegis/AegisEcosystemSettings.java) | TODO - next patch session |
| `080-083 mobile dock UI, 090-093 desktop glassbar UI` | Liquid Glass series per docs/design/IMPLEMENTATION-MAP*.md | TODO - ships in the same wave |
| `landing/help/index.html` | Static help site routing ?p= topic params -> real docs (Shield, cookies, telemetry, sync, passwords, AI, troubleshooting) | AUTHORED (306 lines, JS syntax-checked) |

## Pinned seams (verified at tag 156.0.8078.79 via googlesource ?format=TEXT)

- `chrome/common/url_constants.h` — 107 inline constexpr help URLs (94x chrome?p=, plus
  accounts/drive/googlepay/wallet/installer/chromecast). PRIMARY seam, patch 085.
- `chrome/browser/ui/android/strings/android_chrome_strings.grd` — the Android strings
  file moved here from chrome/android/java/strings/ (404 at the old path). Contains
  sign-in promo strings, personalization strings (lines ~333-346), FRE footer (line 3586).
  NOTE: 0 direct support.google.com URLs in it — Android help URLs come from code
  constants / resources to pin.
- Android help launcher class path NOT yet pinned (HelpAndFeedback* 404'd at two guessed
  paths) — pin via tree walk next session.
- Desktop M1 anchors: see IMPLEMENTATION-MAP-DESKTOP.md (verified paths there).

## Rules (unchanged from patches/README.md)

- Generate every patch against the exact tag via scripts/chromium_file.py — never from
  memory. CI tries git apply --3way then plain; failure stops the build loudly.
- One concern per patch. Pure string/resource changes are cheap; new C++ files preferred
  over edits to heavily-included headers.
