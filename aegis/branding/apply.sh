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
