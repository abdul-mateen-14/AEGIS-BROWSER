#!/usr/bin/env bash
# Aegis Browser - fetch a Chromium checkout for PATCH AUTHORING (not building).
#
#   usage:  fetch-chromium-src.sh <chromium-version-tag> [workdir]
#   e.g.:   fetch-chromium-src.sh 156.0.8078.79
#
# This is the cheap half of a Chromium setup: depot_tools + `fetch
# --no-history --nohooks` (~20-30 GB, 30-60 min on a Codespace/WSL). It
# deliberately SKIPS `gclient sync` and `runhooks` - those download the
# SDK/NDK and third-party binaries you only need to BUILD, and a build does
# not happen on the authoring machine (the free GitHub runner does it).
#
# Memory needs: trivial. Disk needs: ~35 GB free. That fits the free GitHub
# Codespace tier (32 GB) or any laptop disk.
set -euo pipefail

VER="${1:?usage: fetch-chromium-src.sh <chromium-version-tag> [workdir]}"
WORK="${2:-$HOME/aegis-chromium}"
mkdir -p "$WORK"
cd "$WORK"

if [ ! -d depot_tools ]; then
  git clone --depth=1 https://chromium.googlesource.com/chromium/tools/depot_tools.git
fi
export PATH="$PWD/depot_tools:$PATH"

if [ ! -d src ]; then
  # ~25 GB; --no-history is what keeps this inside a small disk
  fetch --no-history --nohooks chromium
fi

cd src
git fetch --depth=1 origin "refs/tags/${VER}:refs/tags/${VER}"
git checkout --detach "refs/tags/${VER}"

echo
echo "Chromium ${VER} checked out at $PWD"
echo "Next steps:"
echo "  1. scripts/patch-foundry/verify-gn-args.sh \"$PWD\""
echo "  2. scripts/patch-foundry/gen-aegis-patches.sh \"$PWD\" --dry-run"
echo "  3. scripts/patch-foundry/gen-aegis-patches.sh \"$PWD\""
echo "  4. commit the emitted patches from aegis/patches/ and push - the CI builds them"
