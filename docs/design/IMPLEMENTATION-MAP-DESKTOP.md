# Aegis "Liquid Glass Glassbar" — Desktop Implementation Map (Linux + Windows)

Source spec: `Aegis-Browser-UI-Design-Proposal-Desktop.pdf` v1.0 + `Aegis-UI-Mockups-Desktop.html`
(archived in this folder, received 2026-11-03 ~13:00Z). Companion to `IMPLEMENTATION-MAP.md` (Android).
Written against tag **156.0.8078.79**. Every future desktop patch session reads this first.

## 0. Verdict

**Buildable as designed.** The spec's own §06 is right: the whole concept lives in the Views
application layer (one view hierarchy above the WebContents), no compositor surgery. One extra
honesty point the spec implies but doesn't say: because desktop Chromium UI is cross-platform Views
code, **one patch series covers Linux and Windows alike** — but our free runners only build the
Linux flavor today, so the **.deb gets the Glassbar first**. A Windows .exe needs its own runner
flavor (separate bank `banking-windows`, windows-latest runner, slower cores) — possible later, same
patches.

## 1. Component → Chromium 156 target map (paths probed at the tag on googlesource)

| Design component | Chromium target | Status |
|---|---|---|
| Frameless window + caption cluster (min/max/close in glass) | `browser_frame_view_win.cc` (VERIFIED) + `browser_frame_view_linux.cc` (VERIFIED) + `ui/views/window/non_client_view.cc`, `frame_view.cc` (VERIFIED) — NonClientHitTest routes the top band as drag/caption; Linux already ships CSD here | paths VERIFIED |
| Glassbar container (capsule / strip / rail, z-stack Table 12) | New views under `chrome/browser/ui/views/glassbar/` (new dir) hosted in BrowserView's ClientView above WebContents — `browser_view.cc` (VERIFIED) is the attachment seam | path VERIFIED |
| Trust segment (shield + domain + title) | Presentation-only pill re-bound to existing omnibox/location-bar controllers — `location_bar_view.cc` (VERIFIED); profile/security state reused | path VERIFIED |
| Mini favicon tabs + hover strip | Re-host `tab_strip.cc` (VERIFIED) + `tab_strip_action_container.cc` (VERIFIED) inside the capsule; active-tab-lives-in-trust-segment is a re-layout, not a new model | paths VERIFIED, risk D2 |
| Tab grid sheet | Native tab-search equivalent moved in 156 (404 at historical paths) — pin exact file at M3; fallback: own sheet on TabModel | to pin |
| Menu sheet (2-col grid, shield count header) | `app_menu_model.cc` (VERIFIED) — reuse menu item ids, replace presentation with glass sheet | path VERIFIED |
| Command omnibox | Omnibox controllers reused; the views-side files moved in 156 (`omnibox_view*.cc` 404) — pin at M1 (Ctrl+L expansion is core M1) | to pin |
| Edge rail + recall gestures | Part of glassbar view (collapsed geometry 6×160, hit 32×192) | to write |
| Backdrop blur (Table 9/10) | Win11: DWM acrylic via frame view; KDE: blur protocol; GNOME/X11: **Aegis readback** — WebContents frame sampled quarter-res, Skia blur, cached, 200 ms scroll-idle throttle | to write, risk D3 |
| Wayland CSD | `ui/ozone/platform/wayland/host/wayland_window.cc` (VERIFIED) + xdg-decoration | path VERIFIED |
| Tokens (Tables 5–8, shared with mobile) | One `aegis_tokens` color/dimens source under `ui/color`/resources; Montserrat + Inter + JetBrains Mono are all OFL → bundle in resources | to write |
| Keyboard map (Table 3) + a11y (Table 11) | Views focus manager (F6 cycle, focus trap in sheets); standard accelerators | standard |

**Honesty note:** `browser_frame.{h,cc}`, `custom_frame_view.cc`, `tab_strip_region_view.cc`,
`tab_search_bubble.*`, `omnibox_view*.cc` all 404 at their historical paths in 156 — desktop
frame/omnibox internals were also restructured. Verified anchors above are enough to start; exact
file pinning happens per-patch via googlesource `?format=TEXT` at the tag, never from memory.

## 2. Ranked engineering risks

- **D1 — Frame + caption replacement (M1).** Frameless window with in-glass caption buttons touches
  NonClientHitTest, snap layouts and window controls on two windowing systems. Mitigated: Chromium
  already draws its own frames on both platforms (the verified frame_view files ARE that code) — we
  restyle, not reinvent.
- **D2 — Tab strip re-hosted in the capsule (M2).** TabStrip assumes a fixed top region; hover
  expansion (240 ms width transition), tab drag and tooltips inside a floating pill is the deepest
  re-layout. Slice: capsule shows favicon mini-tabs first; full labeled strip only after the
  geometry is proven.
- **D3 — Readback blur perf (M1→M4).** WebContents renders on a separate compositor (viz); live
  per-frame readback is expensive. The spec's fallback ladder is correct — ship tiers 2→4 (cached
  quarter-res snapshot, solid tint, reduce-transparency) first; OS acrylic (Win11) is tier-1
  enhancement; GNOME readback blur is parity work in M4, not a blocker.
- **D4 — KDE/Wayland blur protocol (M4).** Needs an `org_kde_kwin_blur` binding in ozone/wayland —
  small but touches the ozone host. GNOME gets the readback path regardless.
- **D5 — Focus/a11y (M2–M3).** F6 cycling, sheet focus traps, screen-reader labels (Table 11) —
  standard Views APIs, must not be skipped.

## 3. Ship sequence → patch series (mirrors mobile 080–083)

| Milestone (spec §6.1) | Patch | Contents |
|---|---|---|
| M1 | `090-glassbar-capsule.patch` | Frameless window + caption cluster + glass capsule with bound trust line, + and ⋮; native toolbar hidden; tokens |
| M2 | `091-glassbar-states.patch` | Hover strip expansion, auto-hide triggers, edge rail + recall |
| M3 | `092-glassbar-sheets.patch` | Shared sheet component: tab grid, menu, command omnibox |
| M4 | `093-glassbar-parity.patch` | Light theme tokens, GNOME readback blur, Win10 / reduce-transparency tiers |
| branding | hook or patch (bank-safety rules) | Montserrat/Inter/JetBrains Mono, icon set, home wallpaper |

**Iteration cost model (honest):** Views C++ patches are heavier than Android Java patches. With the
bank intact: restore ~2 min + recompile touched objects + final low-RAM link (~30–60 min) + deb pack
≈ **2–4 h per iteration**; keep new headers narrow (new files, pimpl) so `.cc`-only edits stay
surgical. Header changes to `browser_view.h`-class files can widen invalidation — avoid in early
milestones. Windows artifact additionally requires standing up a Windows bank flavor first.

## 4. Open decisions for the user (do not block M1)

1. Home quick tiles: which 8 shortcuts ship in v1 (spec shows placeholders)?
2. Engine chips default order (same open question as mobile: Aegis/DuckDuckGo/Startpage/Wikipedia)?
3. Windows .exe flavor: stand up `banking-windows` now or after the first .deb lands? (Recommend: after.)
4. GNOME caption cluster: spec fixes close-only CSD — confirm no minimize/maximize even for GNOME users who ask.
