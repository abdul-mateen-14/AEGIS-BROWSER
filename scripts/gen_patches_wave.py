#!/usr/bin/env python3
"""gen_patches_wave.py - generate the release-wave patches:

  080-aegis-dock.patch        Liquid Glass dock (M1+M2: pill, sliver, collapse)
  081-aegis-search-sheet.patch glass search sheet (M3 slice)
  082-aegis-dock-parity.patch  reduced-motion / low-RAM fallbacks (M4 slice)
  089-degoogle-brand-sweep.patch NTP logo kill + full brand-string sweep
  090-aegis-glass-tokens.patch desktop Liquid Glass color tokens

Same rules as gen_patch_088.py: count-asserted literal edits against the
pristine tag-156 files cached under chromium-cache/, patches emitted as
plain unified diffs (git apply). Staging honors the alphabetical patch
order (054 < 080 < 081 < 082 < 087? NO - 080/081/082 run BEFORE 087; the
gni staging chains are applied accordingly: 080 stages post-054, 081
stages post-080, 082 stages post-081, 089 stages post-088 for the strings
file that 088 already rebrands).
"""
import difflib
import importlib.util
import pathlib
import re
import subprocess
import tempfile

REPO = pathlib.Path("/home/z/my-project/download/aegis-repo")
CACHE = pathlib.Path("/home/z/my-project/chromium-cache/156.0.8078.79")
ADDS = REPO / "scripts/patch-foundry/additions"
PATCHES = REPO / "aegis/patches"


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


check087 = load_module("check087", "/home/z/my-project/scripts/check087.py")
gp088 = load_module("gen_patch_088", "/home/z/my-project/scripts/gen_patch_088.py")

DOCK_REL = ("chrome/android/java/src/org/chromium/chrome/browser/aegis/dock/"
            "AegisDockView.java")
SHEET_REL = ("chrome/android/java/src/org/chromium/chrome/browser/aegis/dock/"
             "AegisSearchSheet.java")
GNI_REL = "chrome/android/chrome_java_sources.gni"
ECO_LINE_OLD = ('  "java/src/org/chromium/chrome/browser/about_settings/'
                'AboutChromeSettings.java",\n'
                '  "java/src/org/chromium/chrome/browser/aegis/'
                'AegisEcosystemSettings.java",')
DOCK_LINE = ('  "java/src/org/chromium/chrome/browser/aegis/dock/'
             'AegisDockView.java",')
SHEET_LINE = ('  "java/src/org/chromium/chrome/browser/aegis/dock/'
              'AegisSearchSheet.java",')
ECO_LINE_OLD_UNUSED = ('  "java/src/org/chromium/chrome/browser/about_settings/'
                       'AboutChromeSettings.java",\n'
                       '  "java/src/org/chromium/chrome/browser/aegis/'
                       'AegisEcosystemSettings.java",')


def read(rel, base=CACHE):
    return (base / rel).read_text(encoding="utf-8")


def add_text(addition_rel):
    return (ADDS / addition_rel).read_text(encoding="utf-8")


def emit(number, slug, desc, files):
    """files: [(rel, pristine_or_None, final_text)] - None => new file."""
    diffs = []
    for rel, pristine, final in files:
        if pristine is None:
            lines = final.splitlines(keepends=True)
            out = [f"diff --git a/{rel} b/{rel}\n",
                   "new file mode 100644\n",
                   "--- /dev/null\n",
                   f"+++ b/{rel}\n",
                   f"@@ -0,0 +1,{len(lines)} @@\n"]
            out += ["+" + l for l in lines]
            diffs.append("".join(out))
        else:
            assert pristine != final, f"no change for {rel}"
            diffs.append("".join(difflib.unified_diff(
                pristine.splitlines(keepends=True), final.splitlines(keepends=True),
                fromfile=f"a/{rel}", tofile=f"b/{rel}", n=3)))
    header = f"# {number} - {slug}\n" + "".join(f"# {l}\n" for l in desc) + "\n"
    out = PATCHES / f"{number:03d}-{slug}.patch"
    out.write_text(header + "\n".join(diffs), encoding="utf-8")
    print("wrote", out.name, out.stat().st_size, "bytes")


def literal(text, old, new, rel):
    n = text.count(old)
    assert n == 1, f"{rel}: anchor found {n} times (need 1): {old[:70]!r}"
    return text.replace(old, new, 1)


def stage_with_patches(rel, patch_names):
    """Stage a pristine file through the real earlier patches (git apply),
    exactly like the CI series does, then return the staged text. The staging
    sandbox carries every cached tag file so multi-file patches apply."""
    work = pathlib.Path(tempfile.mkdtemp(prefix="stage"))
    for f in CACHE.rglob("*"):
        if f.is_file():
            dst = work / f.relative_to(CACHE)
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(f.read_bytes())
    for pn in patch_names:
        r = subprocess.run(
            ["git", "apply", str(PATCHES / pn)],
            cwd=work, capture_output=True, text=True)
        if r.returncode != 0:
            raise SystemExit(f"staging {rel}: {pn} failed: {r.stderr[:400]}")
    staged = (work / rel)
    if not staged.exists():
        raise SystemExit(f"staging {rel}: patch chain did not produce the file")
    return staged.read_text(encoding="utf-8")


# ----------------------------------------------------------------- staging
# The gni chain: 054 adds the ecosystem entry; 080 puts the dock entry right
# BEFORE the about-settings line so 054's adjacent about+ecosystem block
# (087's anchor) stays byte-identical; 081 puts the sheet entry right after
# the dock entry.
post054_gni = stage_with_patches(
    GNI_REL, ["054-aegis-ecosystem-page.patch"])
ABOUT_LINE = '  "java/src/org/chromium/chrome/browser/about_settings/' + \
             'AboutChromeSettings.java",'
post080_gni = literal(post054_gni, ABOUT_LINE, DOCK_LINE + "\n" + ABOUT_LINE, "gni080")
post081_gni = literal(post080_gni, DOCK_LINE, DOCK_LINE + "\n" + SHEET_LINE, "gni081")

dock_v080 = add_text("aegis/dock/AegisDockView.java")
dock_stub_old = """    /**
     * Opens the glass search sheet. Patch 080 ships this as a hook stub so
     * the dock compiles standalone; patch 081 (AegisSearchSheet) rewrites
     * the body to show the sheet.
     */
    public void openSearchOverlay() {
        // Patch 081 replaces this body with the glass search sheet.
    }"""
dock_stub_new = """    /** Opens the glass search sheet (patch 081). */
    public void openSearchOverlay() {
        AegisSearchSheet.show(this);
    }"""
dock_v081 = literal(dock_v080, dock_stub_old, dock_stub_new, "dock081")

# ------------------------------------------------------------------- 080
chrome_activity = read(
    "chrome/android/java/src/org/chromium/chrome/browser/app/ChromeActivity.java")
chrome_activity = literal(
    chrome_activity,
    """            ControlContainer controlContainer = findViewById(R.id.control_container);

            // Inflate the correct toolbar layout for the device.""",
    """            ControlContainer controlContainer = findViewById(R.id.control_container);

            // Aegis Liquid Glass: the native top toolbar stays hidden for the
            // whole session; the Aegis dock replaces it at the bottom edge.
            // Find-in-page UI lives in this container and returns with the
            // glass sheets.
            if (controlContainer != null) {
                ((View) controlContainer).setVisibility(View.GONE);
            }

            // Inflate the correct toolbar layout for the device.""",
    "ChromeActivity.java")

cta = read("chrome/android/java/src/org/chromium/chrome/browser/ChromeTabbedActivity.java")
cta = literal(
    cta,
    "import org.chromium.chrome.browser.app.ChromeActivity;",
    "import org.chromium.chrome.browser.aegis.dock.AegisDockView;\n"
    "import org.chromium.chrome.browser.app.ChromeActivity;",
    "CTA import")
cta = literal(
    cta,
    "            TabbedStartupWindowPolicyDelegate.getInstance().initializeWithNative(originalProfile);",
    "            TabbedStartupWindowPolicyDelegate.getInstance().initializeWithNative(originalProfile);\n"
    "\n"
    "            // Aegis Liquid Glass: the bottom dock replaces the hidden top\n"
    "            // toolbar. The menu supplier is lazy - RootUiCoordinator creates\n"
    "            // the handler during native initialization.\n"
    "            AegisDockView.install(this, () -> mRootUiCoordinator.getAppMenuHandler());",
    "CTA install")

files_080 = [
    (DOCK_REL, None, dock_v080),
    (GNI_REL, post054_gni, post080_gni),
    ("chrome/android/java/res/values/aegis_tokens.xml", None,
     add_text("aegis/dock/res/values/aegis_tokens.xml")),
]
for d in ("aegis_dock_pill", "aegis_dock_button", "aegis_dock_sliver",
          "aegis_ic_menu", "aegis_ic_plus", "aegis_ic_search", "aegis_ic_tabs"):
    files_080.append(
        (f"chrome/android/java/res/drawable/{d}.xml", None,
         add_text(f"aegis/dock/res/drawable/{d}.xml")))
files_080 += [
    ("chrome/android/java/src/org/chromium/chrome/browser/app/ChromeActivity.java",
     read("chrome/android/java/src/org/chromium/chrome/browser/app/ChromeActivity.java"),
     chrome_activity),
    ("chrome/android/java/src/org/chromium/chrome/browser/ChromeTabbedActivity.java",
     read("chrome/android/java/src/org/chromium/chrome/browser/ChromeTabbedActivity.java"),
     cta),
]
emit(80, "aegis-dock", [
    "Liquid Glass dock (mobile M1+M2): the bottom-edge navigation pill that",
    "replaces the hidden native top toolbar. Menu button opens the regular",
    "app menu anchored to the dock (bottom-bar variant); URL pill binds the",
    "current tab title at 1 Hz; tabs button opens the tab-search surface;",
    "new-tab button uses the browser's own menu action. Collapses to a",
    "24x140dp edge sliver after 4s; touch recalls it. Design tokens live in",
    "res/values/aegis_tokens.xml (light base; dark set overrides via the",
    "same names). The ChromeActivity EDI hides the native control container;",
    "the ChromeTabbedActivity EDI installs the dock after native init.",
], files_080)

# ------------------------------------------------------------------- 081
files_081 = [
    (SHEET_REL, None, add_text("aegis/dock/AegisSearchSheet.java")),
    (GNI_REL, post080_gni, post081_gni),
    (DOCK_REL, dock_v080, dock_v081),
]
emit(81, "aegis-search-sheet", [
    "Glass search sheet (mobile M3 slice): the dock URL-pill tap opens a",
    "translucent bottom sheet with a single query field and the default",
    "engine chip. Typed domains load in the current tab (https:// added for",
    "bare hosts); typed words search with the default engine's template URL",
    "(Google stays the default engine by design). Suggestions and per-engine",
    "chips land in a later sheet iteration.",
], files_081)

# ------------------------------------------------------------------- 082
dock_field_old = """    private boolean mExpanded;
    private @Nullable String mLastBound;"""
dock_field_new = """    private boolean mExpanded;
    private @Nullable String mLastBound;
    /** False when the OS asks for reduced motion or the device is low-RAM:
     *  state changes are applied instantly without animation. */
    private final boolean mAnimate =
            android.provider.Settings.Global.getFloat(
                    getContext().getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                    > 0f
            && !((android.app.ActivityManager) getContext().getSystemService(
                    android.content.Context.ACTIVITY_SERVICE)).isLowRamDevice();"""
collapse_old = """        mExpanded = false;
        mPill.animate().alpha(0f).setDuration(220).withEndAction(() -> {"""
collapse_new = """        mExpanded = false;
        if (!mAnimate) {
            mPill.setVisibility(GONE);
            mSliver.setAlpha(1f);
            mSliver.setVisibility(VISIBLE);
            return;
        }
        mPill.animate().alpha(0f).setDuration(220).withEndAction(() -> {"""
expand_old = """        mExpanded = true;
        mSliver.animate().alpha(0f).setDuration(120).withEndAction(() -> mSliver.setVisibility(GONE))
                .start();
        mPill.setAlpha(0f);
        mPill.setVisibility(VISIBLE);
        mPill.animate().alpha(1f).setDuration(180).start();
        refreshUrl();
        scheduleCollapse();"""
expand_new = """        mExpanded = true;
        if (!mAnimate) {
            mSliver.setVisibility(GONE);
            mPill.setAlpha(1f);
            mPill.setVisibility(VISIBLE);
            refreshUrl();
            scheduleCollapse();
            return;
        }
        mSliver.animate().alpha(0f).setDuration(120).withEndAction(() -> mSliver.setVisibility(GONE))
                .start();
        mPill.setAlpha(0f);
        mPill.setVisibility(VISIBLE);
        mPill.animate().alpha(1f).setDuration(180).start();
        refreshUrl();
        scheduleCollapse();"""
dock_v082 = literal(dock_v081, dock_field_old, dock_field_new, "dock082 field")
dock_v082 = literal(dock_v082, collapse_old, collapse_new, "dock082 collapse")
dock_v082 = literal(dock_v082, expand_old, expand_new, "dock082 expand")
emit(82, "aegis-dock-parity", [
    "Dock parity (mobile M4 slice): respects the system reduced-motion",
    "setting (animator duration scale 0) and low-RAM devices by applying",
    "collapse/expand state changes instantly instead of animating them.",
    "Light/dark theme tokens ship with 080 via values/ + values-night/.",
], [(DOCK_REL, dock_v081, dock_v082)])

# ------------------------------------------------------------------- 089
def sweep_grd(text):
    """Rebrand user-visible message text: Google Chrome -> Aegis, then bare
    Chrome/Chromium -> Aegis. Word-boundary safe (Chromebook survives). Only
    the text between <message ...> and </message> changes - ids, descs and
    attributes stay untouched."""
    def fix(m):
        inner = m.group(2)
        inner = inner.replace("Google Chrome", "Aegis")
        inner = re.sub(r"\bChrome\b", "Aegis", inner)
        inner = re.sub(r"\bChromium\b", "Aegis", inner)
        return m.group(1) + inner + m.group(3)
    return re.sub(r"(<message\b[^>]*>)(.*?)(</message>)", fix, text, flags=re.S)


STRINGS_REL = "chrome/browser/ui/android/strings/android_chrome_strings.grd"
CHROMIUM_STRINGS_REL = "chrome/app/chromium_strings.grd"
SETTINGS_STRINGS_REL = "chrome/app/settings_strings.grdp"

strings_pristine = read(STRINGS_REL)
strings_post088 = stage_with_patches(
    STRINGS_REL, ["072-aegis-help-links.patch", "074-aegis-passwords-rebrand.patch",
                  "088-degoogle-ui-fre-settings.patch"])
strings_swept = sweep_grd(strings_post088)

chromium_pristine = read(CHROMIUM_STRINGS_REL)
chromium_post074 = stage_with_patches(
    CHROMIUM_STRINGS_REL, ["074-aegis-passwords-rebrand.patch"])
chromium_swept = sweep_grd(chromium_post074)

settings_pristine = read(SETTINGS_STRINGS_REL)
settings_post074 = stage_with_patches(
    SETTINGS_STRINGS_REL, ["074-aegis-passwords-rebrand.patch"])
settings_swept = sweep_grd(settings_post074)

logo_rel = ("components/search_engines/android/java/src/org/chromium/components/"
            "search_engines/TemplateUrlService.java")
logo_pristine = read(logo_rel)
logo_new = literal(
    logo_pristine,
    """    public boolean doesDefaultSearchEngineHaveLogo() {
        return TemplateUrlServiceJni.get()
                .doesDefaultSearchEngineHaveLogo(mNativeTemplateUrlServiceAndroid);
    }""",
    """    public boolean doesDefaultSearchEngineHaveLogo() {
        // Aegis (de-Googled): no search-provider logo on the NTP or start
        // surface. This is the single choke point for both consumers (the
        // layout metrics and the LogoMediator visibility), so the Google
        // wordmark never loads and no doodle/impression network requests are
        // made, while the default search engine itself stays functional.
        return false;
    }""",
    "TemplateUrlService.java")

emit(89, "degoogle-brand-sweep", [
    "Complete Chrome/Google branding removal, part 2 (the string sweep):",
    "  - the Google wordmark on the NTP is gone (doesDefaultSearchEngine-",
    "    HaveLogo() returns false - the single choke point for the layout",
    "    metrics and the logo view; also stops doodle fetches),",
    "  - every user-visible 'Chrome'/'Chromium'/'Google Chrome' in the three",
    "    big string catalogs is rebranded to 'Aegis' (word-boundary safe:",
    "    Chromebook survives; Google-only strings like the engine name stay -",
    "    the Google search engine itself remains the functional default).",
    "  - the sweep runs on top of patch 088's rebranded strings (staged).",
], [
    (logo_rel, logo_pristine, logo_new),
    (STRINGS_REL, strings_post088, strings_swept),
    (CHROMIUM_STRINGS_REL, chromium_post074, chromium_swept),
    (SETTINGS_STRINGS_REL, settings_post074, settings_swept),
])

# ------------------------------------------------------------------- 090
mixer_h_rel = "chrome/browser/ui/color/aegis_glass_color_mixer.h"
mixer_cc_rel = "chrome/browser/ui/color/aegis_glass_color_mixer.cc"
mixer_h = """// Copyright 2026 The Aegis Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_UI_COLOR_AEGIS_GLASS_COLOR_MIXER_H_
#define CHROME_BROWSER_UI_COLOR_AEGIS_GLASS_COLOR_MIXER_H_

#include "ui/color/color_provider_key.h"

namespace ui {
class ColorProvider;
}

// Adds the Aegis Liquid Glass token colors. Registered last in
// chrome_color_mixers.cc so the tokens override every earlier mixer and the
// native (GTK/DWM) recipes, but still respect user themes (the custom_theme
// and app_controller mixers run after this function returns) and
// forced-colors high-contrast mode (gated in the implementation).
void AddAegisGlassColorMixer(ui::ColorProvider* provider,
                             const ui::ColorProviderKey& key);

#endif  // CHROME_BROWSER_UI_COLOR_AEGIS_GLASS_COLOR_MIXER_H_
"""
mixer_cc = """// Copyright 2026 The Aegis Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/ui/color/aegis_glass_color_mixer.h"

#include "chrome/browser/ui/color/chrome_color_id.h"
#include "third_party/skia/include/core/SkColor.h"
#include "ui/color/color_provider.h"

void AddAegisGlassColorMixer(ui::ColorProvider* provider,
                             const ui::ColorProviderKey& key) {
  // Forced-colors / high-contrast users keep the native accessible recipes.
  if (key.contrast_mode != ui::ColorProviderKey::ContrastMode::kNormal) {
    return;
  }
  const bool dark_mode =
      key.color_mode == ui::ColorProviderKey::ColorMode::kDark;
  ui::ColorMixer& mixer = provider->AddMixer();

  // Solid tier-2 glass tokens (spec Table 5). Live backdrop blur is a later
  // slice; these tints are the deterministic fallback everywhere.
  if (dark_mode) {
    mixer[kColorToolbar] = {SkColorSetRGB(0x14, 0x15, 0x18)};
    mixer[kColorLocationBarBackground] = {SkColorSetRGB(0x24, 0x25, 0x29)};
    mixer[kColorFrameCaptionActive] = {SkColorSetRGB(0x14, 0x15, 0x18)};
    mixer[kColorFrameCaptionInactive] = {SkColorSetRGB(0x1B, 0x1C, 0x20)};
  } else {
    mixer[kColorToolbar] = {SkColorSetRGB(0xF2, 0xF2, 0xF2)};
    mixer[kColorLocationBarBackground] = {SkColorSetRGB(0xE8, 0xE8, 0xE8)};
    mixer[kColorFrameCaptionActive] = {SkColorSetRGB(0xF2, 0xF2, 0xF2)};
    mixer[kColorFrameCaptionInactive] = {SkColorSetRGB(0xE8, 0xE8, 0xE8)};
  }
}
"""
build_gn = read("chrome/browser/ui/color/BUILD.gn")
build_gn = literal(
    build_gn,
    '    "chrome_color_mixers.cc",',
    '    "aegis_glass_color_mixer.cc",\n'
    '    "aegis_glass_color_mixer.h",\n'
    '    "chrome_color_mixers.cc",',
    "color BUILD.gn")
mixers_cc = read("chrome/browser/ui/color/chrome_color_mixers.cc")
mixers_cc = literal(
    mixers_cc,
    '#include "chrome/browser/ui/color/native_chrome_color_mixer.h"',
    '#include "chrome/browser/ui/color/aegis_glass_color_mixer.h"\n'
    '#include "chrome/browser/ui/color/native_chrome_color_mixer.h"',
    "mixers include")
mixers_cc = literal(
    mixers_cc,
    """  // Must be the last one in order to override other mixer colors.
  AddNativeChromeColorMixer(provider, key);""",
    """  // Must be the last one in order to override other mixer colors.
  AddNativeChromeColorMixer(provider, key);

  // Aegis Liquid Glass tokens: after native (GTK/DWM) so the tokens always
  // win, but before the user-theme mixers below so custom themes still
  // override them.
  AddAegisGlassColorMixer(provider, key);""",
    "mixers call")
emit(90, "aegis-glass-tokens", [
    "Desktop Liquid Glass tokens: a last-position color mixer sets the",
    "glass toolbar / location-bar / caption tints for dark and light, after",
    "the native GTK/DWM mixer so the tokens always win. Forced-colors",
    "high-contrast users keep the native accessible recipes. Pure theme",
    "change - no new views, no gn-arg changes; the full Glassbar (frameless",
    "window, tab-strip surgery) is deliberately deferred.",
], [
    (mixer_h_rel, None, mixer_h),
    (mixer_cc_rel, None, mixer_cc),
    ("chrome/browser/ui/color/BUILD.gn",
     read("chrome/browser/ui/color/BUILD.gn"), build_gn),
    ("chrome/browser/ui/color/chrome_color_mixers.cc",
     read("chrome/browser/ui/color/chrome_color_mixers.cc"), mixers_cc),
])

print("wave generation complete")
