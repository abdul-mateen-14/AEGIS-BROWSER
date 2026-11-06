#!/usr/bin/env bash
# Aegis Browser - desktop branding hook (Linux/Windows)
#
# Renames the product in the desktop UI strings. Deliberately conservative:
# only exact text nodes  ">Chromium<"  and the author string are replaced, so
# XML structure, message names and printf-style placeholders stay intact.
# Non-English UI languages keep the upstream translations for now (their
# pretranslated .xtb files still say "Chromium") - a later branding patch can
# cover those.
#
# Usage: apply_desktop.sh <chromium-src-dir>
set -e
SRC="${1:?usage: apply_desktop.sh <chromium-src-dir>}"
cd "$SRC"

F=chrome/app/chromium_strings.grd
if [ -f "$F" ]; then
  BEFORE=$(grep -c '>Chromium<' "$F" || true)
  sed -i 's/>Chromium</>Aegis</g' "$F"
  AFTER=$(grep -c '>Chromium<' "$F" || true)
  echo "Desktop branding: $F '>Chromium<' occurrences ${BEFORE} -> ${AFTER} remaining"
else
  echo "Desktop branding: $F not found (layout moved upstream?) - skipping"
fi

# About-box company line (exact string, format-safe replace)
sed -i 's/The Chromium Authors/Aegis Browser/g' "$F" 2>/dev/null || true

# --- Installer branding (BRANDING -> deb/desktop template substitutions) ---
F_B=chrome/app/theme/chromium/BRANDING
if [ -f "$F_B" ]; then
  sed -i \
    -e 's/^COMPANY_FULLNAME=.*/COMPANY_FULLNAME=Aegis Browser/' \
    -e 's/^COMPANY_SHORTNAME=.*/COMPANY_SHORTNAME=Aegis Browser/' \
    -e 's/^PRODUCT_FULLNAME=.*/PRODUCT_FULLNAME=Aegis/' \
    -e 's/^PRODUCT_SHORTNAME=.*/PRODUCT_SHORTNAME=Aegis/' \
    -e 's/^PRODUCT_INSTALLER_FULLNAME=.*/PRODUCT_INSTALLER_FULLNAME=Aegis Installer/' \
    -e 's/^PRODUCT_INSTALLER_SHORTNAME=.*/PRODUCT_INSTALLER_SHORTNAME=Aegis Installer/' \
    "$F_B"
  echo "Desktop branding: $F_B -> Aegis identity"
fi

# --- Desktop icons: Aegis rounded-corner tile over Chromium's -------------
# Generated from aegis/branding/logo/aegis-logo-rounded.png by
# scripts/branding/make_icons.py (desktop_tiles). Launchers on Windows/Linux
# do not mask icons, so the tile itself is rounded and the mark sits at
# 62-72% of the tile - its three tips stay clear of every boundary.
# Layout verified @ chromium 156.0.8078.79:
#   chrome/app/theme/chromium/win/{chromium.ico,chromium_doc.ico}
#   chrome/app/theme/chromium/product_logo_{16,24,48,64,128,256}.png
#   chrome/app/theme/chromium/product_logo_22_mono.png
#   chrome/app/theme/chromium/linux/product_logo_{24,48,64,128,256}.png
HERE="$(cd "$(dirname "$0")" && pwd)"
ICO_SRC="$HERE/icons/desktop/aegis.ico"
TOP="$SRC/chrome/app/theme/chromium"

if [ -f "$ICO_SRC" ] && [ -d "$TOP/win" ]; then
  cp "$ICO_SRC" "$TOP/win/chromium.ico"
  cp "$ICO_SRC" "$TOP/win/chromium_doc.ico"
  echo "Desktop branding: win/chromium.ico + chromium_doc.ico <- aegis.ico (16..256 frames)"
else
  echo "Desktop branding: .ico override skipped (missing $ICO_SRC or $TOP/win)"
fi

copy_set() { # copy_set <src-dir> <dest-dir> <names...>
  local from="$1" to="$2"; shift 2
  if [ ! -d "$from" ] || [ ! -d "$to" ]; then
    echo "Desktop branding: icon override skipped (missing $from or $to - layout changed upstream?)"
    return 0
  fi
  local n=0 f
  for f in "$@"; do
    if [ -f "$from/$f" ]; then
      cp "$from/$f" "$to/$f"
      n=$((n + 1))
    fi
  done
  echo "Desktop branding: $n icon files -> ${to#"$SRC"/}"
}

copy_set "$HERE/icons/desktop" "$TOP" \
  product_logo_16.png product_logo_24.png product_logo_32.png \
  product_logo_48.png product_logo_64.png product_logo_128.png \
  product_logo_256.png product_logo_22_mono.png
copy_set "$HERE/icons/desktop" "$TOP/linux" \
  product_logo_24.png product_logo_48.png product_logo_64.png \
  product_logo_128.png product_logo_256.png

echo "Desktop branding done."
