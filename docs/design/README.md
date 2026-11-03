# Aegis Design — "Liquid Glass"

This folder is the design foundation of Aegis Browser: two platform concepts
(Android and Linux/Windows) that share one visual language, plus the
implementation maps that translate them into the Chromium 156 codebase.

**Hardened by default. Quiet by design.**

## The language

| Element | Spec |
|---|---|
| Material | **Liquid glass** — one recipe at three scales: a 5–6 px collapsed sliver/rail, a 44 px capsule (dock / glassbar), a full-width strip state |
| Palette | Strict achromatic monochrome — true blacks `#0A0A0B`, paper whites `#F6F6F7`; hierarchy carried by lightness and blur, never hue |
| Type | Montserrat (identity / numerals), Inter (dense UI), JetBrains Mono (kbd hints) — all OFL |
| Trust line | The only place the browser speaks: shield + domain, explicit "not encrypted" for HTTP; blocked-tracker counts shown on demand in the menu header, never as ambient anxiety |
| Motion | `cubic-bezier(.3, 0, .1, 1)` — fast departure, gentle landing; every transition < 0.5 s |
| Themes | Dark first, light in lockstep — same geometry, token swap only |
| Blur | Four-tier fallback ladder (live/system blur → cached snapshot → solid tint → reduce-transparency); every tier keeps the same silhouette |

## The two concepts

- **Android — Liquid Glass Dock** (`Aegis-Browser-UI-Design-Proposal.pdf` + interactive
  `Aegis-UI-Mockups.html`): every page edge-to-edge under one 260×52 dp dock that collapses
  to a 5×120 dp sliver; glass sheets for tabs, menu and search.
- **Linux/Windows — Liquid Glass Glassbar** (`Aegis-Browser-UI-Design-Proposal-Desktop.pdf`
  + `Aegis-UI-Mockups-Desktop.html`): a frameless window with a 640×44 glass capsule carrying
  tabs, omnibox and even the caption buttons; hover expands it to a 1,040 px strip, reading
  collapses it to a 6×160 px edge rail. ~2.2% of the viewport vs ≈9.8% for a conventional
  tab strip + toolbar.

## Implementation maps

- `IMPLEMENTATION-MAP.md` — Android concept → Chromium 156 (`chrome/android/java/...`),
  patch series **080–083** (dock → collapse → glass sheets → parity), verified anchors,
  ranked risks, iteration cost model.
- `IMPLEMENTATION-MAP-DESKTOP.md` — Desktop concept → Chromium 156 Views C++
  (`chrome/browser/ui/views/...`), patch series **090–093** (capsule → states → sheets →
  parity), platform blur matrix, window-management risks.

Both maps were written against tag `156.0.8078.79` with every file path probed on
googlesource at that tag; exact file pinning is re-verified per patch.
