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
echo "Desktop branding done."
