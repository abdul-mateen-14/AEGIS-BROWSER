# Aegis Desktop — Windows & Linux Expansion Plan

Status of the question "can we ship Windows and Linux from this same project?" —
**yes**. Android was Phase 1-3; this document is the working plan for the
desktop phases. Nothing about the Android pipeline changes.

## Why it works from one repo

Everything the Android build needs is already platform-portable:

| Shared asset | Reused as-is? |
| --- | --- |
| `aegis/patches/*.patch` | Yes — desktop Chromium shares the same source files the patches touch; anchors re-ground-truthed per tag by the Patch Foundry (same flow as Android) |
| `scripts/patch-foundry/` | Yes, unchanged |
| `aegis/branding/` (logo, palette, icons) | Yes — desktop uses PNG/SVG icon sets instead of Android mipmaps |
| `aegis-sync` backend | Yes — it is plain HTTPS on Cloudflare Workers, platform-agnostic. Desktop sync UI is its own later batch (C++/Views settings page, not the Android Java twin) |
| Landing page | Yes — gains per-platform download buttons |
| Releases | Yes — one repo, assets named per platform |

What each platform adds: one `aegis/ci/<platform>.gn` args file + one workflow +
a packaging step. That is the whole diff.

## Phase D1 — Linux x64 (the quick win) — LAUNCHED

> Status: workflow `.github/workflows/build-aegis-linux.yml` committed; first
> build dispatched with the same 4-core free runner used by the APK pipeline.

- Runner: the SAME `ubuntu-latest` 4-core/16 GB machine the Android build uses
  today. A desktop Linux build is *lighter* than Android (no Java targets, no
  APK packaging).
- New files: `aegis/ci/linux.gn` (is_official_build=false, symbol_level=0,
  blink_symbol_level=0, no PGO/ThinLTO — same low-RAM family),
  `.github/workflows/build-aegis-linux.yml`.
- Output: `AegisBrowser_<ver>_linux_x64.zip` (+ `.deb` target if wanted —
  `//chrome/installer/linux` provides deb/rpm).
- Expected wall time: 3-5 h on the free runner. Cost: $0.
- Patches: batch-1 privacy set applies; desktop-specific anchors (e.g. metrics
  consent) verified through the Foundry against the same `156.0.8078.79` tag.

## Phase D2 — Linux arm64

- Cross-compile from the same x64 runner (`target_cpu = "arm64"` with the
  Chromium-provided sysroot). Moderate extra effort, still $0.

## Phase D3 — Windows x64 (possible, with one honest caveat)

- Free `windows-latest` runners are 4-core/16 GB — Chromium CAN build there,
  but two constraints are real:
  1. **6 h job cap** (free runners): a no-cache Windows Chromium build is
     typically 6-10 h on 4 cores — borderline over the cap.
  2. Disk: Windows runners have less free space than the ~145 GB Linux volume.
- Strategy in order:
  1. **Try once for free** — low-RAM arg family, `symbol_level=0`, debug off.
     If it lands under 6 h, we are done for $0.
  2. **If the cap blocks it**, use the already-documented paid fallback from
     the build plan: a $1-3 spot VM per build (or a beefier VM at
     ~$0.10-0.30/hour used only when a Windows build is wanted). The repo
     already carries this path for exactly this reason.
- Output: `AegisBrowser_<ver>_win_x64.zip` ( portable zip first; an installer
  via `//chrome/installer/util` later if wanted).

## Phase D4 — optional macOS arm64

macos-14 runners (M1, 3-core) build Chromium arm64 but likely exceed 6 h —
treat like Windows (attempt free, fall back to paid VM only when needed).

## Recommended order

1. Finish Android v1.3.x (current build → queued v1.3.0 build → Sync UI batch 3)
2. **D1 Linux x64** — one focused session: gn file, workflow, first build
3. D2 Linux arm64
4. D3 Windows x64 (free attempt → spot-VM fallback)
5. Desktop sync UI (one design across Linux/Windows, separate batch)
6. D4 macOS, if wanted

## Release naming convention (all platforms)

```
AegisBrowser_<chromium_ver>_android_arm64.apk
AegisBrowser_<chromium_ver>_linux_x64.zip
AegisBrowser_<chromium_ver>_linux_arm64.zip
AegisBrowser_<chromium_ver>_win_x64.zip
```

Draft releases stay per-build; a published release carries all platforms
built so far, so the landing page download section can link once and serve
every OS.
