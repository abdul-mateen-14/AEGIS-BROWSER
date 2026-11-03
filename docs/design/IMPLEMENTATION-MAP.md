# Aegis "Liquid Glass Dock" — Chromium 156 Implementation Map

Source spec: `Aegis-Browser-UI-Design-Proposal.pdf` v1.0 + `Aegis-UI-Mockups.html` (archived in this folder).
Purpose: translation contract between the design spec and the patch series we will build. Every future
patch-writing session reads this first. Written 2026-11-03, against tag 156.0.8078.79.

## 0. Verdict

The spec is **buildable as designed** — its own Section 06 is architecturally correct: the whole concept
lives in the Android application layer (one View hierarchy overlay above WebContents), no compositor or
rendering-pipeline changes. The three glass sheets reuse omnibox/tab-model/menu controllers. The PDF's
fallback ladder (Table 9) already solves the hard problem (backdrop blur) correctly.

## 1. Component → Chromium target map (paths probed against tag 156.0.8078.79 on googlesource)

| Design component | Chromium target | Status |
|---|---|---|
| Dock container (260×52dp pill, layer 2, edge-to-edge) | New `AegisDockView` under `chrome/android/java/src/org/chromium/chrome/browser/` + custom layout; attaches to the Activity's root above WebContents; z-order per spec Table 11 | to write |
| Toolbar replacement / hiding of native chrome | `ToolbarManager.java` (VERIFIED at `chrome/android/java/src/org/chromium/chrome/browser/toolbar/`) + `ControlContainer.java` (VERIFIED at `chrome/browser/ui/android/toolbar/java/src/org/chromium/chrome/browser/toolbar/`) — ControlContainer is the seam where the native toolbar view attaches; dock bypasses it while reusing its controllers | paths VERIFIED |
| Browser-controls hiding (page under status bar, no 56dp bars) | browser-controls state machine (controls height/offset suppliers live under `chrome/browser/ui/android/toolbar/...` + `components/browser_controls` — exact files to pin when writing M1 patch) | to pin |
| URL / trust pill (binds security state + shield count) | Bind to the existing omnibox controllers (location bar controller + security icon state). The pill is presentation-only; omnibox round-trip reused per spec §06 | to pin |
| New-tab button | TabModelSelector / TabCreator — one call, no new state | to pin |
| Menu sheet (4×2 grid, shield count in header) | Replaces `appmenu` presentation only: reuse existing menu item ids per spec §06 (AppMenu files — exact path moved in recent Chromium; pin at M3) | to pin |
| Tab grid sheet (2-col cards, private inverted) | TabModelFilter (regular/private) binding; replaces native tab switcher presentation — see risk R2 | to pin |
| Search overlay (engine chips, suggestions) | Existing omnibox suggestion stack (autocomplete controller) re-skinned in glass sheet | to pin |
| Edge sliver (5×120dp, 24×140dp touch) | Part of AegisDockView (collapsed state) | to write |
| New-tab home (clock, status pill, quick tiles) | NewTabPage variants — `NewTabPage.java` VERIFIED; home redesign is a presentation patch + resources | path VERIFIED |
| Montserrat + tokens | res/font + `res/values/aegis_tokens.xml` (colors/dimens from spec Table 5-8); Android 13 monochrome theming already supported | to write |
| Edge-to-edge / cutout | WindowInsetsCompat + display-cutout passthrough per spec §06 | standard |

**Honesty note:** ToolbarPhone.java / LocationBar.java / AppMenu.java 404 at their historical paths in
156 — the toolbar/omnibox internals were restructured (Java→Kotlin migration era). Verified anchors:
`ToolbarManager.java`, `ControlContainer.java`, `NewTabPage.java`. Exact file pinning happens per-patch
by fetching `?format=TEXT` from googlesource at the tag — never from memory.

## 2. Ranked engineering risks

- **R1 — Live backdrop blur over web content ("liquid glass").** Web content renders on a separate
  compositor surface; a View-layer `RenderEffect` cannot blur it directly. Real mechanisms:
  `WindowManager.LayoutParams.blurBehindRadius` (API 31+, gated by cross-window-blur availability) with
  the dock/sheets as windows, or spec Table 9 Tier-2 (blurred page snapshot refreshed on scroll idle,
  200ms throttle). The spec's fallback ladder is exactly right: implement tiers 2→4 first (works
  everywhere), add tier-1 live blur as an enhancement behind a device capability check.
- **R2 — Tab grid replacing the native tab switcher.** The native TabSwitcher carries toolbar/hud state
  transitions. Shipping the glass tab sheet as an overlay that just *pauses* the tab switcher (not
  replaces it) is the safe M3 slice; full replacement can come later.
- **R3 — Controls/gesture interplay.** Hiding native browser controls + dock auto-hide + keyboard layer
  (spec Table 11: keyboard always wins) touches the gesture area edge cases. Mitigation: dock hidden
  while IME open (spec already mandates), auto-hide triggers live in scroll listeners only.

## 3. Ship sequence mapped to patch iterations (each ≈ 1 free-runner window, 2–3h wall)

| Milestone (spec §6.1) | Patch | Contents |
|---|---|---|
| M1 | `080-aegis-dock.patch` | AegisDockView + expanded dock + bound URL pill + new tab + menu entry point; native toolbar hidden; tokens xml |
| M2 | `081-aegis-dock-collapse.patch` | Auto-hide triggers, collapse motion (360ms emphasized), edge sliver + recall gestures |
| M3 | `082-aegis-glass-sheets.patch` | Glass sheet base + tab grid + menu sheet + search overlay |
| M4 | `083-aegis-glass-parity.patch` | Light theme tokens, reduced-transparency + low-end fallback tiers |
| branding assets | branding hook or patches (decide per bank-safety rules) | Montserrat font, icon set, home wallpaper gradient |

Iteration cadence after the bank is complete: **~2–3h per milestone patch**, several per day possible.
Design tokens live in one XML file per spec Table 5–8, so visual fine-tuning iterations are cheap.

## 4. Open decisions for the user (do not block M1)

1. Engine chips list (Aegis/DuckDuckGo/Startpage/Wikipedia) — hardcode order or make settings-driven?
2. Home quick tiles: which 6 shortcuts ship in v1?
3. Montserrat licensing: OFL — bundle as APK asset (confirmed fine).
4. Shield count source: patch 070's engine count API (reuse, no new C++).
