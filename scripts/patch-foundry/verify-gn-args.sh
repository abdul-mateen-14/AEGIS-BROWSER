#!/usr/bin/env bash
# Aegis Browser - verify that each hardening GN arg still exists in the
# Chromium checkout you are about to build.
#
#   usage:  scripts/patch-foundry/verify-gn-args.sh /path/to/chromium/src
#
# For every line in gn-args-hardening.conf it looks for the arg NAME in the
# tree's .gn / .gni files (git grep -w, so enable_vr does not match
# enable_vr_something). FOUND lines are safe to append to aegis/ci/arm64.gn;
# MISSING lines must be removed (the arg was renamed or dropped upstream -
# keeping them would fail "gn gen" only AFTER the 1-2 h source fetch).
set -euo pipefail

SRC="${1:?usage: verify-gn-args.sh <chromium-src>}"
SRC="$(cd "$SRC" && pwd)"
[ -d "$SRC/.git" ] || { echo "ERROR: $SRC is not a git checkout" >&2; exit 1; }
CONF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/gn-args-hardening.conf"

fail=0
found=0
while IFS= read -r line; do
  # strip comments and whitespace; skip empties
  line="$(printf '%s' "$line" | sed -e 's/#.*//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [ -z "$line" ] && continue
  name="${line%%[[:space:]]*}"

  if loc="$(git -C "$SRC" grep -l -w -e "$name" -- '*.gn' '*.gni' 2>/dev/null | head -3 | tr '\n' ' ')" &&
     [ -n "$loc" ]; then
    echo "FOUND   $name   (${loc})"
    found=$((found + 1))
  else
    echo "MISSING $name   -> remove this line from aegis/ci/arm64.gn (renamed/dropped upstream)"
    fail=1
  fi
done < "$CONF"

echo
if [ "$fail" = "0" ]; then
  echo "ALL $found GN args verified - safe to append to aegis/ci/arm64.gn."
else
  echo "One or more args are MISSING in this tag - clean up gn-args-hardening.conf / arm64.gn first."
fi
exit "$fail"
