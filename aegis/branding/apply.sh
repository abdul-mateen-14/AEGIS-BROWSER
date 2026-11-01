#!/usr/bin/env bash
# Aegis Browser - branding hook (runs in CI before gn gen).
# Usage: apply.sh <path-to-chromium-src>
#
# v1.1: replaces <string name="app_name"> in EVERY resource file under
# chrome/android/java that defines it. (The resource file layout has moved
# between Chromium releases - res_chromium/, res_default/, generated
# manifests - so searching is robust to that; the old single-path sed
# silently did nothing when the path changed.)
#
# This gets your first builds displaying "Aegis Browser" in the launcher.
# For a deeper rebrand (package id, icons, about pages) prefer real .patch
# files in aegis/patches/ so changes stay reviewable and revertible.
#
# Keep this script idempotent: CI may re-run it.
set -euo pipefail
SRC="${1:?usage: apply.sh <chromium-src>}"
LABEL="Aegis Browser"

count=0
while IFS= read -r -d '' f; do
  if grep -q '<string name="app_name">' "$f"; then
    sed -i 's|<string name="app_name">[^<]*</string>|<string name="app_name">'"$LABEL"'</string>|' "$f"
    count=$((count + 1))
    echo "Branding: app_name -> '$LABEL' in ${f#"$SRC"/}"
  fi
done < <(find "$SRC/chrome/android/java" -type f -name '*.xml' -path '*res*' -print0 2>/dev/null)

if [ "$count" -eq 0 ]; then
  echo "Branding: no app_name string found under chrome/android/java - check the Chromium layout for this version"
fi

# --- Launcher icons (Aegis mark over Chromium's) -------------------------
# Overwrites Chromium's launcher assets with same-name files generated from
# aegis/branding/logo/aegis-logo.png by scripts/branding/make_icons.py.
# Layout verified @ chromium 156.0.8078.79 (chrome/android/java/res_chromium_base):
#   mipmap-*/app_icon.png                     legacy launcher
#   mipmap-*/layered_app_icon.png             composed adaptive foreground (used by ic_launcher)
#   mipmap-*/layered_app_icon_background.png  adaptive background layer
#   mipmap-nodpi/layered_app_icon_foreground.xml  adaptive foreground vector (ic_launcher_round)
#   drawable/themed_app_icon.xml              Android 13 monochrome layer
ICONS="$(cd "$(dirname "$0")" && pwd)/icons/res_chromium_base"
DEST="$SRC/chrome/android/java/res_chromium_base"
if [ -d "$ICONS" ] && [ -d "$DEST" ]; then
  (cd "$ICONS" && tar -cf - --exclude=preview.png .) | (cd "$DEST" && tar -xf -)
  echo "Branding: launcher icons replaced with the Aegis mark ($ICONS -> $DEST)"
else
  echo "Branding: icon override skipped (missing $ICONS or $DEST - layout changed upstream?)"
fi
