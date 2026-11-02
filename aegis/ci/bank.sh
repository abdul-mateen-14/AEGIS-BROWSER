#!/usr/bin/env bash
# bank.sh - Aegis CI build-tree persistence across GitHub Actions runs.
#
# WHY: Chromium 156 compiles all C++ with C++20 modules (clang PCM), and
# ccache cannot cache module compilation units (it silently passes them
# through - proven: the old design's "Save compiler cache" step stored
# 37 MB while ~40 GB of objects were compiled). So we bank the BUILD TREE
# itself: out/<dir>/obj + gen + .ninja_log + .ninja_deps are streamed
# (zstd level 1 - object files are incompressible, so no gzip waste),
# split into <2 GiB volumes and uploaded as assets of a DRAFT release.
# Draft release assets have no total storage quota and are invisible to
# end users; only the 2 GiB per-file limit applies. Nothing goes through
# actions/cache, so the 10 GB repo cache limit and its eviction policy
# never touch us.
#
# Usage:
#   bank.sh restore <flavor> <src_dir> <out_name>
#       Download the newest bank generation, verify sha256 against the
#       generation manifest, extract into <src_dir>/out/<out_name>,
#       sanity-gate it, then NORMALIZE MTIMEs (gen wave first, then obj
#       wave) so ninja trusts the restored outputs. The workflow applies
#       patches AFTER this step, so patched/branded sources get a NEWER
#       mtime than the banked objects and ninja recompiles exactly those
#       targets - invalidation by mtime ordering, no dependency-graph
#       surgery. NEVER exits nonzero: no bank == seed run, not a failure.
#
#   bank.sh save <flavor> <src_dir> <out_name>
#       Stream obj/gen/.ninja_log/.ninja_deps/.aegis_head/args.gn into
#       volumes, upload to draft release "banking-<flavor>", verify sizes
#       via the API, then delete other generations. Prints one
#       machine-readable line:
#         AEGIS-BANK flavor=<f> gen=<run_id> vols=<n> bytes=<n> edges=<n>
#
# Env required: GH_TOKEN, GITHUB_REPOSITORY, GITHUB_RUN_ID.
# Env optional: WANT_CHROMIUM_TAG (tag gate), AEGIS_HEAD_SHA.
set -u

CMD="${1:?restore|save}"; FLAVOR="${2:?flavor}"; SRC_DIR="${3:?src_dir}"; OUT_NAME="${4:?out_name}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY}"
GEN="${GITHUB_RUN_ID:?GITHUB_RUN_ID}"
: "${GH_TOKEN:?GH_TOKEN}"
OUT_DIR="$SRC_DIR/out/$OUT_NAME"
API="https://api.github.com/repos/$REPO"
UPLOADS="https://uploads.github.com/repos/$REPO"
RELEASE_TAG="banking-$FLAVOR"
TMP="${TMPDIR:-/tmp}/aegis-bank-$FLAVOR"
PART_MB=1900            # < 2 GiB per-asset limit, with margin
log() { echo "[bank] $*"; }
die()  { echo "[bank] ERROR: $*" >&2; }

if command -v zstd >/dev/null 2>&1; then EXT="tar.zst"; else EXT="tar.gz"; fi

find_release_id() {
  gh api "$API/releases?per_page=100" --jq \
    ".[] | select(.draft and .tag_name==\"$RELEASE_TAG\") | .id" 2>/dev/null | head -1
}

list_assets() { # prints "name<TAB>size" lines
  gh api "$API/releases/$1/assets?per_page=100" \
    --jq '.[] | "\(.name)\t\(.size)"' 2>/dev/null
}

# ================================================================= restore
if [ "$CMD" = restore ]; then
  RID="$(find_release_id || true)"
  if [ -z "${RID:-}" ]; then log "no draft release '$RELEASE_TAG' - seed run"; exit 0; fi
  GENLIST="$(list_assets "$RID" | cut -f1 | grep -E '^[0-9]+-vol-[0-9]+\.' | cut -d- -f1 | sort -un || true)"
  if [ -z "$GENLIST" ]; then log "no bank assets found - seed run"; exit 0; fi
  log "available generations: $(echo "$GENLIST" | tr '\n' ' ')"

  rm -rf "$TMP"; mkdir -p "$TMP"
  cat > "$TMP/dl_one.sh" <<'DLEOF'
#!/usr/bin/env bash
# internal helper: download one release asset by name (env: API GH_TOKEN RID TMP)
set -u
n="$1"
id=$(gh api "$API/releases/$RID/assets?per_page=100" --jq ".[] | select(.name==\"$n\") | .id")
[ -n "$id" ] || { echo "[dl] no asset id for $n" >&2; exit 1; }
for t in 1 2 3; do
  curl -sSL --retry 3 -H "Authorization: Bearer $GH_TOKEN" \
    -H "Accept: application/octet-stream" \
    -o "$TMP/parts/$n" "$API/releases/assets/$id" && break
  sleep 5
done
[ -s "$TMP/parts/$n" ] || { echo "[dl] empty download: $n" >&2; exit 1; }
DLEOF

  if command -v zstd >/dev/null 2>&1; then DECOMP="zstd -dc"; else DECOMP="gzip -dc"; fi
  RESTORED=""; RESTORED_GEN=""

  for G in $(echo "$GENLIST" | tac); do
    log "trying generation $G ..."
    rm -rf "$TMP/parts"; mkdir -p "$TMP/parts"
    list_assets "$RID" | grep -E "^$G-(vol-[0-9]+\.${EXT}|sha256\.txt)$" | cut -f1 > "$TMP/names"
    [ -s "$TMP/names" ] || { log "gen $G has no parts, skipping"; continue; }
    export API GH_TOKEN TMP RID
    if ! xargs -P 4 -I{} bash "$TMP/dl_one.sh" {} < "$TMP/names"; then
      log "some downloads failed for gen $G"; continue
    fi
    if [ ! -f "$TMP/parts/$G-sha256.txt" ] || \
       ! (cd "$TMP/parts" && sha256sum -c --quiet "$G-sha256.txt" >/dev/null 2>&1); then
      log "sha256 verification FAILED for gen $G - trying older generation"
      continue
    fi
    log "sha256 OK for gen $G; extracting ..."
    mkdir -p "$SRC_DIR/out"
    if ! cat $(ls "$TMP/parts/$G"-vol-*."$EXT" | sort) | $DECOMP | tar -xf - -C "$SRC_DIR/out"; then
      log "extraction failed for gen $G - trying older generation"
      rm -rf "$OUT_DIR"
      continue
    fi
    rm -rf "$TMP"
    RESTORED=1; RESTORED_GEN="$G"
    break
  done

  if [ -z "$RESTORED" ] || [ ! -d "$OUT_DIR/obj" ]; then
    log "no usable bank restored - seed run"
    exit 0
  fi

  # sanity gate: banked for a different Chromium tag -> drop it (the toolchain
  # and every command hash change; ninja alone would cope but slowly)
  if [ -f "$OUT_DIR/.aegis_head" ] && [ -n "${WANT_CHROMIUM_TAG:-}" ]; then
    read -r BANKED_TAG _ < "$OUT_DIR/.aegis_head"
    if [ "$BANKED_TAG" != "$WANT_CHROMIUM_TAG" ]; then
      log "bank is for Chromium $BANKED_TAG, building $WANT_CHROMIUM_TAG -> dropping banked tree"
      rm -rf "$OUT_DIR/obj" "$OUT_DIR/gen" "$OUT_DIR/.ninja_log" "$OUT_DIR/.ninja_deps"
    fi
  fi
  [ -d "$OUT_DIR/obj" ] || { log "tree dropped - seed run"; exit 0; }

  # mtime normalization: gen wave, then obj wave (obj strictly newer than
  # gen and than all freshly-checked-out sources -> ninja treats restored
  # outputs as up to date; patches applied afterwards re-dirty exactly the
  # patched files).
  find "$OUT_DIR/gen" -type f -print0 2>/dev/null | xargs -0 -r touch -m 2>/dev/null || true
  sleep 2
  find "$OUT_DIR/obj" -type f -print0 2>/dev/null | xargs -0 -r touch -m 2>/dev/null || true
  touch "$OUT_DIR/.ninja_deps" "$OUT_DIR/.ninja_log" 2>/dev/null || true

  EDGES=$(wc -l < "$OUT_DIR/.ninja_log" 2>/dev/null || echo 0)
  log "restored: $(du -sh "$OUT_DIR/obj" | cut -f1) obj, $(du -sh "$OUT_DIR/gen" 2>/dev/null | cut -f1) gen, ~$EDGES banked edges"
  echo "AEGIS-RESTORE flavor=$FLAVOR gen=$RESTORED_GEN edges=$EDGES"
  exit 0
fi

# ==================================================================== save
if [ "$CMD" = save ]; then
  if [ ! -d "$OUT_DIR/obj" ] || [ ! -f "$OUT_DIR/.ninja_log" ]; then
    log "nothing to bank (no obj/.ninja_log)"; exit 0
  fi
  EDGES=$(grep -c "$(printf '\t')" "$OUT_DIR/.ninja_log" 2>/dev/null || echo 0)
  if [ "${EDGES:-0}" -lt 500 ]; then
    log "only $EDGES banked edges - not worth a bank"; exit 0
  fi
  # .aegis_head must exist for the next run's tag gate
  [ -f "$OUT_DIR/.aegis_head" ] || \
    echo "${WANT_CHROMIUM_TAG:-unknown} ${AEGIS_HEAD_SHA:-unknown}" > "$OUT_DIR/.aegis_head"

  OBJ_MB=$(du -sm "$OUT_DIR/obj" | cut -f1)
  GEN_MB=$(du -sm "$OUT_DIR/gen" 2>/dev/null | cut -f1); GEN_MB="${GEN_MB:-0}"
  AVAIL_MB=$(df -Pm "$OUT_DIR" | tail -1 | awk '{print $4}')
  TARGETS="obj gen"
  if [ "$AVAIL_MB" -lt $((OBJ_MB + GEN_MB)) ]; then
    log "disk tight (${AVAIL_MB} MB free < ${OBJ_MB}+${GEN_MB} MB) - banking obj only (gen is regenerable)"
    TARGETS="obj"
  fi
  # Link-tool outputs are NOT covered by the atomic cc wrapper (cc_wrapper
  # wraps compile tools only): a killed link can leave a half-written .so /
  # binary that ninja would trust on resume. Delete them before banking -
  # relinking on the next run costs minutes. Archives inside obj/ are safe:
  # GN's alink rule rm -f's its output before ar runs.
  find "$OUT_DIR" -path "$OUT_DIR/obj" -prune -o -path "$OUT_DIR/gen" -prune -o \
    -type f \( -name '*.so' -o -name '*.so.*' -o -name '*.apk' -o -name '*.crx' -o -perm -u+x \) \
    -print -delete | wc -l | xargs -I{} echo "[bank] deleted {} link outputs (relit on resume)"

  rm -rf "$TMP"; mkdir -p "$TMP"
  TARLIST=""
  for t in $TARGETS; do TARLIST="$TARLIST $OUT_NAME/$t"; done
  log "streaming $((OBJ_MB + GEN_MB)) MB into $EXT volumes ..."
  ( cd "$SRC_DIR/out" && tar -cf - $TARLIST \
      "$OUT_NAME/.ninja_log" "$OUT_NAME/.ninja_deps" \
      "$OUT_NAME/.aegis_head" "$OUT_NAME/args.gn" 2>/dev/null ) \
    | { if [ "$EXT" = tar.zst ]; then zstd -1 -T0; else gzip -1; fi; } \
    | split -b "${PART_MB}m" - "$TMP/$GEN-vol-000." \
    || { die "tar/zstd/split failed"; rm -rf "$TMP"; exit 1; }
  i=0
  for p in "$TMP/$GEN"-vol-000.*; do
    mv "$p" "$TMP/$(printf '%s-vol-%03d.%s' "$GEN" "$i" "$EXT")"; i=$((i+1))
  done
  FILELIST=""
  for ((j=0; j<i; j++)); do FILELIST="$FILELIST $(printf '%s-vol-%03d.%s' "$GEN" "$j" "$EXT")"; done
  ( cd "$TMP" && sha256sum ${FILELIST} > "$GEN-sha256.txt" ) || { die "sha256 failed"; exit 1; }
  BYTES=$(du -sb "$TMP" | cut -f1)
  log "bank: $i volumes, $BYTES bytes"

  RID="$(find_release_id || true)"
  if [ -z "${RID:-}" ]; then
    RID=$(gh api -X POST "$API/releases" \
            -f tag_name="$RELEASE_TAG" -f name="$RELEASE_TAG" \
            -F draft=true -f body="Internal build-tree bank (incremental CI). Do not publish." \
            --jq '.id') || { die "cannot create draft release"; exit 1; }
    log "created draft release id=$RID"
  fi

  cat > "$TMP/up_one.sh" <<'UPEOF'
#!/usr/bin/env bash
# internal helper: upload one file to release RID (env: API UPLOADS GH_TOKEN TMP RID)
set -u
f="$1"; want=$(stat -c %s "$TMP/$f")
for t in 1 2 3; do
  if [ "$t" -gt 1 ]; then
    old=$(gh api "$API/releases/$RID/assets?per_page=100" --jq ".[] | select(.name==\"$f\") | .id" 2>/dev/null)
    [ -n "$old" ] && gh api -X DELETE "$API/releases/assets/$old" >/dev/null 2>&1
  fi
  got=$(curl -sS -X POST -H "Authorization: Bearer $GH_TOKEN" \
      -H "Content-Type: application/octet-stream" --data-binary @"$TMP/$f" \
      "$UPLOADS/releases/$RID/assets?name=$f" 2>/dev/null \
    | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("size",""))
except Exception: print("")')
  if [ -n "$got" ] && [ "$got" = "$want" ]; then exit 0; fi
  echo "[up] verify failed for $f (try $t)" >&2; sleep 5
done
exit 1
UPEOF

  export API UPLOADS GH_TOKEN TMP RID
  if ! ls "$TMP" | grep -E '^[0-9]+-(vol-|sha256)' | xargs -P 4 -I{} bash "$TMP/up_one.sh" {}; then
    die "some uploads failed - previous generation left intact"
    rm -rf "$TMP"; exit 1
  fi
  log "all $i volumes + manifest uploaded and size-verified"

  # retention: keep the TWO newest generations (the newest plus the previous
  # one as fallback if the newest ever proves bad), delete anything older.
  OLDGENS=$(list_assets "$RID" | cut -f1 | grep -E '^[0-9]+-(vol-|sha256)' | cut -d- -f1 | sort -un | head -n -2)
  for OG in $OLDGENS; do
    [ "$OG" = "$GEN" ] && continue
    list_assets "$RID" | cut -f1 | grep -E "^$OG-" | while read -r a; do
      aid=$(gh api "$API/releases/$RID/assets?per_page=100" --jq ".[] | select(.name==\"$a\") | .id")
      gh api -X DELETE "$API/releases/assets/$aid" >/dev/null 2>&1 || true
      log "deleted old-gen asset $a"
    done
  done
  rm -rf "$TMP"
  echo "AEGIS-BANK flavor=$FLAVOR gen=$GEN vols=$i bytes=$BYTES edges=$EDGES"
  exit 0
fi

die "unknown command: $CMD (use restore|save)"; exit 2
