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
# Usage: restamp.sh <src_dir> [aegis_repo_dir]
#   (env: AEGIS_SRC_EPOCH, same as bank.sh; aegis_repo_dir defaults to the
#    patches/ dir next to this script - CI checkouts always satisfy that)
set -u
SRC="${1:?src_dir}"
AEGIS_PATCH_DIR="${2:-$(dirname "$0")/../patches}"
EPOCH="${AEGIS_SRC_EPOCH:-1577836800}"   # 2020-01-01T00:00:00Z
# python stamps a ~500k-file tree 5-10x faster than find|xargs touch (windows
# process spawn is expensive); identical semantics, find|xargs as fallback.
PY="python3"; command -v python3 >/dev/null 2>&1 || PY="python"
stamp_tree() {
  "$PY" - "$SRC" "$EPOCH" "$AEGIS_PATCH_DIR" <<'STAMPEOF'
import glob, os, sys
src, epoch, patch_dir = sys.argv[1], int(sys.argv[2]) * 1000000000, sys.argv[3]
ns = (epoch, epoch)
skip = {os.path.join(src, "out"), os.path.join(src, ".git")}
# AEGIS (2026-11-17, stale-res root fix; evidence: r97 shipped the 136
# toolbar-shortcut and 140 autofill-row REMOVALS stale - the guessed gen:
# paths never matched, per the #97 log): files a patch writes must KEEP
# their fresh mtimes. Stamping them to the bank epoch made banked res
# outputs (aapt2 resourcezip / R.txt -> R.java) look up-to-date forever,
# so patched value-resources never reached the APK. ninja -t deps cannot
# map res inputs (ACTION edges) and gen: output names are template-
# dependent, so the fix belongs here: patched files stay newer than the
# bank and ninja rebuilds their actions naturally (~30 patched files
# re-dirty per run - bounded, seconds).
patched = set()
patch_roots = []
if patch_dir and os.path.isdir(patch_dir):
    patch_roots.append(patch_dir)
    # accept a repo ROOT too (CI passes $GITHUB_WORKSPACE): patches live in
    # <root>/aegis/patches; globbing only the root itself would silently
    # match nothing and disable the fix.
    sub = os.path.join(patch_dir, "aegis", "patches")
    if os.path.isdir(sub):
        patch_roots.append(sub)
for d in patch_roots:
    for p in glob.glob(os.path.join(d, "*.patch")):
        with open(p, errors="replace") as fh:
            for ln in fh:
                if ln.startswith("+++ ") and not ln.startswith("+++ /dev/null"):
                    tok = ln[4:].split("\t")[0].strip()
                    if tok.startswith("b/"):
                        tok = tok[2:]
                    if tok:
                        patched.add(os.path.join(src, tok))
n = skipped = 0
for root, dirs, files in os.walk(src):
    dirs[:] = [d for d in dirs if os.path.join(root, d) not in skip]
    for f in files:
        fp = os.path.join(root, f)
        if fp in patched:
            skipped += 1
            continue
        try:
            os.utime(fp, ns=ns, follow_symlinks=False)
            n += 1
        except OSError:
            pass
print(f"[restamp] stamped {n} source files @{src} "
      f"(kept fresh mtimes for {skipped} patched files)")
STAMPEOF
}
if ! stamp_tree 2>/dev/null; then
  find "$SRC" \( -path "$SRC/out" -o -path "$SRC/.git" \) -prune -o \
      -type f -print0 2>/dev/null | xargs -0 -r touch -d "@$EPOCH" 2>/dev/null || true
fi
echo "[restamp] src tree re-stamped @$EPOCH after patches/branding (banked outputs stay newer)"
