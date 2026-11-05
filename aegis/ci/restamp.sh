#!/usr/bin/env bash
# restamp.sh - re-apply the bank mtime epoch AFTER patches + branding hooks.
#
# WHY (audit finding, 2026-11-05): bank.sh restore stamps freshly checked-out
# sources to AEGIS_SRC_EPOCH so banked outputs stay strictly newer than all
# sources (the v6 ninja mtime contract). But `git apply` and the branding
# scripts rewrite files with CURRENT mtimes afterwards - so on EVERY run,
# every banked output of every patched file looked stale even when the patch
# set was byte-identical to the one the bank was built with (the common case;
# restore_tree.py only maps patch sets that actually CHANGED).
#
# Evidence: every run started ninja at the full dirty set - Linux runs #19 and
# #20 both began at [1/27089] despite their banks differing by ~21k built
# edges; APK #23 began at [1/34255] right after #22 banked 12,644 more built
# edges. With C++20 modules one recompiled header cascades via dyndep through
# every module consumer, so the re-dirty covered essentially the whole graph
# and made the bank useless: each run redid ~27k (Linux) / ~34k (APK) edges
# from scratch, hit the ~5 h cap, banked, and the next run started over - a
# full build can never fit one runner window that way, so no APK ever shipped.
#
# SAFE because the .aegis_head sha gate + restore_tree.py guarantee compiled-
# content identity of the bank: if patch/branding files CHANGED, restore_tree.py
# already deleted the outputs that depend on them (they rebuild regardless of
# mtimes, and their fresh outputs then correctly outrank the 2020 sources); if
# unchanged, contents are byte-identical to what produced the banked outputs,
# so making them look old cannot create stale objects.
#
# Usage: restamp.sh <src_dir>      (env: AEGIS_SRC_EPOCH, same as bank.sh)
set -u
SRC="${1:?src_dir}"
EPOCH="${AEGIS_SRC_EPOCH:-1577836800}"   # 2020-01-01T00:00:00Z
find "$SRC" \( -path "$SRC/out" -o -path "$SRC/.git" \) -prune -o \
    -type f -print0 2>/dev/null | xargs -0 -r touch -d "@$EPOCH" 2>/dev/null || true
echo "[restamp] src tree re-stamped @$EPOCH after patches/branding (banked outputs stay newer)"
