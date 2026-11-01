#!/usr/bin/env bash
# Aegis Browser - Patch Foundry engine.
# =====================================================================
# Turns small, asserted, literal text edits into reviewable .patch files
# against the EXACT Chromium tag you build - the safe way to author the
# Phase 2 (hardening) and Phase 3 (sync UI) patch series without ever
# building Chromium locally.
#
#   usage (inside a GitHub Codespace, or WSL with a fetched tree):
#     scripts/patch-foundry/gen-aegis-patches.sh /path/to/chromium/src [--out DIR] [--dry-run]
#
# How it works
#   - It reads every edits/*.conf file (sorted). A spec sets
#       PATCH_ENABLED=1, PATCH_NUMBER=010, PATCH_SLUG="short-name",
#       PATCH_DESC="...", and a list of edits EDIT1_*, EDIT2_*, ...
#   - For each edit it REQUIRES:
#       EDITn_FILE  file path relative to the Chromium src root
#       EDITn_GREP  an extended regex that must match EXACTLY ONCE
#       EDITn_OLD   the exact literal text to replace (must occur once)
#       EDITn_NEW   the replacement literal (real newlines are allowed)
#   - ADDn_SRC / ADDn_DEST copy a whole NEW file from additions/ into the
#     tree (new files are version-robust; only anchor edits can drift).
#   - If ANY assertion fails, the spec aborts, the tree is restored, and
#     nothing is written: Chromium moved -> update the anchor, no silent
#     no-ops ever. On success it emits <out>/NNN-slug.patch via git diff
#     and restores the tree.
#
# Design rule (matches aegis/patches/README.md): patches stay SMALL, one
# concern per patch, applied in alphabetical order by the free CI.
set -euo pipefail

usage() {
  sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 1
}

SRC=""
OUT=""
DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) usage ;;
    *) if [ -z "$SRC" ]; then SRC="$1"; shift; else echo "unexpected argument: $1" >&2; usage; fi ;;
  esac
done
[ -n "$SRC" ] || usage
SRC="$(cd "$SRC" && pwd)"
[ -d "$SRC/.git" ] || { echo "ERROR: $SRC is not a git checkout (run fetch-chromium-src.sh first)" >&2; exit 1; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required" >&2; exit 1; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EDITS_DIR="$HERE/edits"
ADDITIONS_DIR="$HERE/additions"
OUT="${OUT:-$HERE/../../aegis/patches}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
mkdir -p "$ADDITIONS_DIR"

restore=()   # modified files -> git checkout
added=()     # intent-to-add paths (relative) -> git reset + rm

restore_tree() {
  if [ ${#restore[@]} -gt 0 ]; then
    git -C "$SRC" checkout -- "${restore[@]}" 2>/dev/null || true
  fi
  if [ ${#added[@]} -gt 0 ]; then
    git -C "$SRC" reset -q -- "${added[@]}" 2>/dev/null || true
    for f in "${added[@]}"; do rm -f "$SRC/$f"; done
  fi
  restore=(); added=()
}
trap restore_tree EXIT

die() { echo "ERROR: $*" >&2; exit 1; }

# apply_edit <file-rel> <grep-regex> <old-literal> <new-literal>
apply_edit() {
  local file="$1" pat="$2" old="$3" new="$4"
  local abs="$SRC/$file"
  [ -f "$abs" ] || die "anchor target not found in this tag: $file"

  # A spec may edit the same file several times (e.g. include + hook call).
  # Uncommitted changes are only acceptable if THIS spec made them.
  if [ -n "$(git -C "$SRC" status --porcelain -- "$file")" ]; then
    local known=0
    for r in ${restore[@]+"${restore[@]}"}; do
      [ "$r" = "$file" ] && known=1
    done
    [ "$known" = "1" ] \
      || die "$file already has uncommitted changes - refusing to mix edits"
  fi

  local count
  count="$(grep -E -c -- "$pat" "$abs" || true)"
  [ "$count" = "1" ] || die "GREP anchor matched $count times (need exactly 1) in $file:
    pattern: $pat
    -> Chromium moved; locate the new location and update this anchor."

  # Exact OLD-literal check is done in Python below (multi-line safe; a
  # line-based grep -F would split multi-line literals into separate
  # patterns and miscount them).
  OLD="$old" NEW="$new" DRY="$DRY" python3 - "$abs" <<'PYEOF'
import os, sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
old, new = os.environ["OLD"], os.environ["NEW"]
n = s.count(old)
if n != 1:
    print(f"OLD literal found {n} times in {p}", file=sys.stderr)
    sys.exit(3)
if os.environ.get("DRY") != "1":
    p.write_text(s.replace(old, new, 1), encoding="utf-8")
PYEOF
  if [ "$DRY" = "1" ]; then
    echo "  [dry-run] would edit $file (OLD literal verified exactly once)"
    return 0
  fi

  restore+=("$file")
  echo "  edited $file"
}

# add_file <src-rel-to-additions> <dest-rel-to-src>
add_file() {
  local src="$1" dest="$2"
  [ -n "$src" ] && [ -f "$ADDITIONS_DIR/$src" ] || die "addition not found: additions/$src"
  [ -z "$(git -C "$SRC" status --porcelain -- "$dest" 2>/dev/null)" ] \
    || die "$dest already has uncommitted changes - refusing to overwrite"
  if [ "$DRY" = "0" ]; then
    mkdir -p "$SRC/$(dirname "$dest")"
    cp "$ADDITIONS_DIR/$src" "$SRC/$dest"
    added+=("$dest")
    echo "  added  $dest"
  else
    echo "  [dry-run] would add $dest"
  fi
}

emit_patch() {
  local number="$1" slug="$2"
  local out_file="$OUT/${number}-${slug}.patch"
  if [ "$DRY" = "1" ]; then return 0; fi
  local paths=()
  local f
  for f in "${restore[@]}"; do paths+=("$f"); done
  for f in "${added[@]}"; do paths+=("$f"); done
  [ ${#paths[@]} -gt 0 ] || die "spec produced no changes"
  git -C "$SRC" add -N -- "${added[@]}" 2>/dev/null || true
  git -C "$SRC" diff -- "${paths[@]}" > "$out_file"
  [ -s "$out_file" ] || die "generated patch is empty: $out_file"
  echo "  wrote  ${out_file} ($(wc -l < "$out_file") lines)"
}

shopt -s nullglob
specs=("$EDITS_DIR"/*.conf)
if [ ${#specs[@]} -eq 0 ]; then
  echo "No spec files found in $EDITS_DIR (see edits/000-TEMPLATE.conf)."
  exit 0
fi

for spec in "${specs[@]}"; do
  # reset spec variables (a later spec may define fewer of them)
  unset PATCH_ENABLED PATCH_NUMBER PATCH_SLUG PATCH_DESC EDIT_COUNT ADD_COUNT 2>/dev/null || true
  for i in $(seq 1 32); do unset "EDIT${i}_FILE" "EDIT${i}_GREP" "EDIT${i}_OLD" "EDIT${i}_NEW" "ADD${i}_SRC" "ADD${i}_DEST" 2>/dev/null || true; done

  # shellcheck disable=SC1090
  source "$spec"
  name="$(basename "$spec")"
  if [ "${PATCH_ENABLED:-0}" != "1" ]; then
    echo "skip   $name (PATCH_ENABLED=0)"
    continue
  fi
  echo "spec   $name -> patch ${PATCH_NUMBER}-${PATCH_SLUG}.patch"
  [ -n "${PATCH_NUMBER:-}" ] && [ -n "${PATCH_SLUG:-}" ] || die "$name: PATCH_NUMBER and PATCH_SLUG are required"
  [ -n "${EDIT_COUNT:-}" ] || [ -n "${ADD_COUNT:-}" ] || die "$name: EDIT_COUNT or ADD_COUNT required"

  for i in $(seq 1 "${ADD_COUNT:-0}"); do
    v_src="ADD${i}_SRC"; v_dest="ADD${i}_DEST"
    add_file "${!v_src:-}" "${!v_dest:-}"
  done
  for i in $(seq 1 "${EDIT_COUNT:-0}"); do
    v_f="EDIT${i}_FILE"; v_g="EDIT${i}_GREP"; v_o="EDIT${i}_OLD"; v_n="EDIT${i}_NEW"
    apply_edit "${!v_f:-}" "${!v_g:-}" "${!v_o:-}" "${!v_n:-}"
  done
  emit_patch "$PATCH_NUMBER" "$PATCH_SLUG"
  restore_tree
  echo
done

trap - EXIT
if [ "$DRY" = "1" ]; then
  echo "Dry run OK - all anchors exist in this checkout. Re-run without --dry-run to emit patches."
else
  echo "Done. Patches in $OUT - commit them into the repo (aegis/patches/) and run the CI."
fi
