#!/usr/bin/env bash
# bank.sh - Aegis CI build-tree persistence across GitHub Actions runs.
#
# WHY: Chromium 156 compiles all C++ with C++20 modules (clang PCM), and
# ccache cannot cache module compilation units (it silently passes them
# through - proven: the old design's "Save compiler cache" step stored
# 37 MB while ~40 GB of objects were compiled). So we bank the BUILD TREE
# itself: out/<dir>/obj + gen + .ninja_log + .ninja_deps are streamed
# (zstd level 1 - object files are incompressible, so no gzip waste),
# split into small volumes and uploaded as assets of a DRAFT release.
# Draft release assets have no total storage quota and are invisible to
# end users; only the 2 GiB per-file limit applies. Nothing goes through
# actions/cache, so the 10 GB repo cache limit and its eviction policy
# never touch us.
#
# Usage:
#   bank.sh restore <flavor> <src_dir> <out_name>
#       Download the newest bank generation, verify sha256 against the
#       generation manifest, extract into <src_dir>/out/<out_name> and
#       sanity-gate it. MTIME CONTRACT (ninja >= 1.12, build log v6): the
#       v6 log records each output's mtime, so the restore must NOT touch
#       the banked outputs (tar already preserved their banked mtimes,
#       which is exactly what the log expects). Instead every freshly
#       checked-out SOURCE file is stamped into the past, so banked
#       outputs stay strictly newer than all sources. Patches and branding
#       hooks run AFTER this step with current mtimes, so exactly the
#       patched/branded targets (and their dependents) recompile.
#       The previous design (touch gen, then obj) desynchronized every
#       logged mtime from every file and pushed the next run into a
#       near-full recompile; never do that to a v6 tree.
#       Exit codes: 0 = no bank existed (legit seed run) or the bank
#       restored cleanly; 1 = a bank EXISTS but could not be restored
#       (loud RED - re-dispatch to retry instead of silently burning a
#       multi-hour seed run).
#
#   bank.sh save <flavor> <src_dir> <out_name>
#       Stream the ENTIRE out/<dir> tree (obj, gen AND every out-root output
#       class: lib.java/, clang_x64/ host tree, package.json, resources,
#       .ninja_log, .ninja_deps, .aegis_head, args.gn) into volumes, upload
#       to draft release "banking-<flavor>", verify sizes via the API, then
#       delete other generations. Whole-tree banking matters because ninja
#       tracks out-root outputs too: banking only obj+gen left lib.java/ and
#       package.json missing on every restore, and [why-dirty] proved that
#       re-dirtied 10-17k edges EVERY leg (android: the whole androidx/java
#       tree via "lib.java/...filtered.jar doesn't exist"; linux: the
#       devtools typescript chain via "output package.json doesn't exist")
#       even with perfect mtimes. Prints one machine-readable line:
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
# 700 MB volumes (kept from 1c9fe89): short uploads, tiny retry blast radius.
# Combined with the streaming upload (-T) below, runner RAM no longer scales
# with volume size - the real root cause of run 41870785941's loss was curl
# slurping the whole 1.16 GB body into RAM ("option --data-binary: out of
# memory" on every retry), which a 700 MB volume only shrinks, never removes.
PART_MB=700
# python resolution: windows runners ship 'python' but not always 'python3'
PY="python3"; command -v python3 >/dev/null 2>&1 || PY="python"
# AEGIS_BANK_STREAM=1 -> streaming mode: upload bank volumes ONE BY ONE as
# they are produced (disk peak = a single 700 MB part) and download+extract
# one volume at a time on restore. Needed on the windows runners where the
# disk next to the build tree is too tight to also hold the whole compressed
# bank; linux/android keep the two-pass behavior (stream off = old path,
# byte-identical).
log() { echo "[bank] $*"; }
die()  { echo "[bank] ERROR: $*" >&2; }

if command -v zstd >/dev/null 2>&1; then EXT="tar.zst"; else EXT="tar.gz"; fi

find_release_id() {
  # 3 attempts: a transient API failure here must not look like "no bank"
  # and silently trigger a multi-hour seed rebuild.
  local id=""
  for t in 1 2 3; do
    id=$(gh api "$API/releases?per_page=100" --jq \
      ".[] | select(.draft and .tag_name==\"$RELEASE_TAG\") | .id" 2>/dev/null | head -1)
    [ -n "$id" ] && break
    sleep 5
  done
  printf '%s' "$id"
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

  # STREAM mode (AEGIS_BANK_STREAM=1): download + verify + extract ONE volume
  # at a time - disk peak is a single 700 MB part; classic mode downloads all
  # parts first (xargs -P 4) and extracts once.
  restore_streaming() { # $1 = gen; rc 0 = extracted cleanly
    local G="$1" n
    rm -rf "$TMP/parts"; mkdir -p "$TMP/parts"
    list_assets "$RID" | cut -f1 | grep -E "^$G-(vol-[0-9]+\.${EXT}|sha256\.txt)$" > "$TMP/names"
    [ -s "$TMP/names" ] || { log "gen $G has no parts, skipping"; return 1; }
    export API GH_TOKEN TMP RID
    if ! bash "$TMP/dl_one.sh" "$G-sha256.txt"; then
      log "manifest download failed for gen $G"; return 1
    fi
    log "gen $G: streaming download + extract (one volume on disk at a time) ..."
    (
      set -e
      for n in $(grep -E "^$G-vol-[0-9]+\.${EXT}$" "$TMP/names" | sort); do
        bash "$TMP/dl_one.sh" "$n"
        grep -F " $n" "$TMP/parts/$G-sha256.txt" \
          | ( cd "$TMP/parts" && sha256sum -c --quiet ) >/dev/null
        cat "$TMP/parts/$n"
        rm -f "$TMP/parts/$n"
      done
    ) | $DECOMP | tar -xf - -C "$SRC_DIR/out"
    [ "${PIPESTATUS[0]}" = 0 ] && [ "${PIPESTATUS[1]}" = 0 ] && [ "${PIPESTATUS[2]}" = 0 ]
  }

  for G in $(echo "$GENLIST" | tac); do
    log "trying generation $G ..."
    mkdir -p "$SRC_DIR/out"
    if [ "${AEGIS_BANK_STREAM:-0}" = "1" ]; then
      if ! restore_streaming "$G"; then
        log "gen $G failed - trying older generation"
        rm -rf "$OUT_DIR"
        continue
      fi
    else
      rm -rf "$TMP/parts"; mkdir -p "$TMP/parts"
      list_assets "$RID" | cut -f1 | grep -E "^$G-(vol-[0-9]+\.${EXT}|sha256\.txt)$" > "$TMP/names"
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
      if ! cat $(ls "$TMP/parts/$G"-vol-*."$EXT" | sort) | $DECOMP | tar -xf - -C "$SRC_DIR/out"; then
        log "extraction failed for gen $G - trying older generation"
        rm -rf "$OUT_DIR"
        continue
      fi
    fi
    rm -rf "$TMP"
    RESTORED=1; RESTORED_GEN="$G"
    break
  done

  if [ -z "$RESTORED" ] || [ ! -d "$OUT_DIR/obj" ]; then
    die "bank exists for '$RELEASE_TAG' (gens: $(echo "$GENLIST" | tr '\n' ' ')) but nothing usable restored - failing loudly (re-dispatch to retry the restore)"
    exit 1
  fi

  # sanity gate: banked for a different Chromium tag -> drop it (the toolchain
  # and every command hash change; ninja alone would cope but slowly)
  if [ -f "$OUT_DIR/.aegis_head" ] && [ -n "${WANT_CHROMIUM_TAG:-}" ]; then
    read -r BANKED_TAG _ < "$OUT_DIR/.aegis_head"
    if [ "$BANKED_TAG" != "$WANT_CHROMIUM_TAG" ]; then
      die "bank is for Chromium $BANKED_TAG but we are building $WANT_CHROMIUM_TAG - re-dispatch with the banked version or knowingly accept a seed rebuild"
      exit 1
    fi
  fi
  [ -d "$OUT_DIR/obj" ] || { log "tree dropped - seed run"; exit 0; }

  # Informational: ninja >= 1.12 (build log v6, kOldestSupportedVersion=6)
  # discards v5 logs wholesale ("build log version is too old; starting
  # over") and a missing deps log forces a full recompile too. v5 logs are
  # only produced by Siso-era or ancient ninja runs; a ninja-written bank
  # always carries v6 + a real .ninja_deps. Warn loudly so a wasted cycle
  # is never a mystery again.
  if head -c 15 "$OUT_DIR/.ninja_log" 2>/dev/null | grep -q 'v5'; then
    log "WARNING: banked .ninja_log is v5 (Siso-era?) - ninja >=1.12 will discard it and recompile the tree; banking stays safe, this run is just slow"
  fi
  if [ ! -s "$OUT_DIR/.ninja_deps" ]; then
    log "WARNING: banked tree has no usable .ninja_deps - ninja will recompile every deps-tracked edge"
  fi

  # MTIME CONTRACT (see header): stamp freshly checked-out SOURCES into the
  # past instead of touching banked outputs. out/ and .git are pruned (out
  # holds the banked outputs; .git is huge and build-irrelevant). The fixed
  # epoch predates every banked output (all written during real builds), so
  # ordering becomes: sources(2020) < gen/obj(2026 bank) - exactly what
  # ninja expects, with the v6 log's recorded mtimes left untouched.
  SRC_EPOCH="${AEGIS_SRC_EPOCH:-1577836800}"   # 2020-01-01T00:00:00Z
  # python stamps a ~500k-file source tree 5-10x faster than find|xargs touch
  # (process spawn is expensive on windows); identical semantics: every file
  # outside out/ and .git goes to SRC_EPOCH, banked outputs untouched.
  stamp_tree() {
    "$PY" - "$SRC_DIR" "$SRC_EPOCH" <<'STAMPEOF'
import os, sys
src, epoch = sys.argv[1], int(sys.argv[2]) * 1000000000
ns = (epoch, epoch)
skip = {os.path.join(src, "out"), os.path.join(src, ".git")}
n = 0
for root, dirs, files in os.walk(src):
    dirs[:] = [d for d in dirs if os.path.join(root, d) not in skip]
    for f in files:
        try:
            os.utime(os.path.join(root, f), ns=ns, follow_symlinks=False)
            n += 1
        except OSError:
            pass
print(f"[bank] stamped {n} source files @{src}")
STAMPEOF
  }
  if ! stamp_tree 2>/dev/null; then
    find "$SRC_DIR" \( -path "$SRC_DIR/out" -o -path "$SRC_DIR/.git" \) -prune -o \
        -type f -print0 2>/dev/null \
      | xargs -0 -r touch -d "@$SRC_EPOCH" 2>/dev/null || true
  fi
  log "src tree stamped @$SRC_EPOCH; banked outputs keep their banked mtimes (v6 contract)"

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
  TOTAL_MB=$(du -sm "$OUT_DIR" | cut -f1)
  AVAIL_MB=$(df -Pm "$OUT_DIR" | tail -1 | awk '{print $4}')
  # Full tree by default; fall back to the old restricted list only when the
  # disk cannot even hold the compressed parts (compressed is typically
  # ~40% of the tree, so needing 2x TOTAL free is a conservative gate).
  if [ "$AVAIL_MB" -lt $((TOTAL_MB * 2)) ]; then
    log "disk tight (${AVAIL_MB} MB free < 2x tree ${TOTAL_MB} MB) - banking obj+gen+root files only"
    TARPATHS=("$OUT_NAME/obj" "$OUT_NAME/gen" "$OUT_NAME/.ninja_log" \
              "$OUT_NAME/.ninja_deps" "$OUT_NAME/.aegis_head" "$OUT_NAME/args.gn")
  else
    TARPATHS=("$OUT_NAME")
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

  # Draft release must exist before ANY upload; the streaming path uploads
  # mid-pipeline, so resolve/create it before the split (same behavior as
  # before for the classic path, just earlier).
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
# Hardened after run 41782592027 lost its whole bank: 3 retries 5s apart all
# landed inside a GitHub API blip (instant non-JSON responses). Now: 5 tries
# with exponential backoff (~15s..150s = ~5 min of blip tolerance), the HTTP
# status and response head are logged on failure, stale assets are purged
# before every re-attempt, and every request has a hard timeout.
set -u
f="$1"; want=$(stat -c %s "$TMP/$f")
try_up() {
  old=$(gh api "$API/releases/$RID/assets?per_page=100" --jq ".[] | select(.name==\"$f\") | .id" 2>/dev/null)
  [ -n "$old" ] && gh api -X DELETE "$API/releases/assets/$old" >/dev/null 2>&1
  # -T streams the body from disk with constant memory (~256K buffers);
  # --data-binary slurps the WHOLE file into RAM and OOM'd the runner on the
  # 1.16 GB Linux volume (run 41870785941: "curl: option --data-binary: out
  # of memory" on every retry - volume never uploaded, run RED, progress
  # lost). Streaming decouples runner RAM from volume size entirely (audit
  # 2026-11-05); GitHub's asset-upload endpoint takes POST with a streamed
  # body fine.
  code=$(curl -sS -X POST -T "$TMP/$f" -H "Authorization: Bearer $GH_TOKEN" \
      -H "Content-Type: application/octet-stream" \
      --max-time 1800 -o "$TMP/$f.resp" -w '%{http_code}' \
      "$UPLOADS/releases/$RID/assets?name=$f" 2>"$TMP/$f.curlerr")
  got=$("$PY" -c 'import json,sys
try: print(json.load(open(sys.argv[1])).get("size",""))
except Exception: print("")' "$TMP/$f.resp" 2>/dev/null)
  if [ -n "$got" ] && [ "$got" = "$want" ]; then
    rm -f "$TMP/$f.resp" "$TMP/$f.curlerr"; return 0
  fi
  echo "[up] attempt failed for $f: http=${code:-none} got='${got:-none}' want=$want resp=$(head -c 160 "$TMP/$f.resp" 2>/dev/null | tr '\n' ' ') err=$(head -c 120 "$TMP/$f.curlerr" 2>/dev/null | tr '\n' ' ')" >&2
  return 1
}
for t in 1 2 3 4 5; do
  case $t in 2) sleep 15 ;; 3) sleep 45 ;; 4) sleep 90 ;; 5) sleep 150 ;; esac
  if try_up; then echo "[up] $f uploaded, size-verified (try $t)" >&2; exit 0; fi
done
exit 1
UPEOF

  export API UPLOADS GH_TOKEN TMP RID PY

  if [ "${AEGIS_BANK_STREAM:-0}" = "1" ]; then
    # ---- streaming save (windows: one 700 MB part on disk at a time) ----
    # split --filter hands each compressed part to the filter via stdin; the
    # filter materializes + hashes + uploads + DELETES it. A failed upload
    # aborts the stream (nonzero filter exit propagates through split) with
    # the previous generation left intact - same failure semantics as before.
    cat > "$TMP/stream_filter.sh" <<'SFEOF'
#!/usr/bin/env bash
# split --filter handler (env: TMP RID API UPLOADS GH_TOKEN GEN EXT PY)
set -u
n=$(cat "$TMP/.idx" 2>/dev/null || echo 0)
printf '%s\n' $((n + 1)) > "$TMP/.idx"
cur="$TMP/.part.cur"
cat > "$cur"
name=$(printf '%s-vol-%03d.%s' "$GEN" "$n" "$EXT")
mv "$cur" "$TMP/$name"
sha256sum "$name" | awk '{print $1 "  " $2}' >> "$TMP/$GEN-sha256.txt"
sz=$(stat -c %s "$TMP/$name")
echo $(( $(cat "$TMP/.bytes" 2>/dev/null || echo 0) + sz )) > "$TMP/.bytes"
for t in 1 2 3 4 5; do
  case $t in 2) sleep 15 ;; 3) sleep 45 ;; 4) sleep 90 ;; 5) sleep 150 ;; esac
  old=$(gh api "$API/releases/$RID/assets?per_page=100" --jq ".[] | select(.name==\"$name\") | .id" 2>/dev/null)
  [ -n "$old" ] && gh api -X DELETE "$API/releases/assets/$old" >/dev/null 2>&1
  code=$(curl -sS -X POST -T "$TMP/$name" -H "Authorization: Bearer $GH_TOKEN" \
      -H "Content-Type: application/octet-stream" \
      --max-time 1800 -o "$TMP/$name.resp" -w '%{http_code}' \
      "$UPLOADS/releases/$RID/assets?name=$name" 2>"$TMP/$name.curlerr")
  got=$("$PY" -c 'import json,sys
try: print(json.load(open(sys.argv[1])).get("size",""))
except Exception: print("")' "$TMP/$name.resp" 2>/dev/null)
  if [ -n "$got" ] && [ "$got" = "$sz" ]; then
    rm -f "$TMP/$name.resp" "$TMP/$name.curlerr" "$TMP/$name"
    echo "[up] $name uploaded, size-verified (try $t)" >&2
    exit 0
  fi
  echo "[up] part $name try $t failed: http=${code:-none} got='${got:-none}' want=$sz err=$(head -c 120 "$TMP/$name.curlerr" 2>/dev/null | tr '\n' ' ')" >&2
done
rm -f "$TMP/$name.resp" "$TMP/$name.curlerr"
exit 1
SFEOF
    export TMP RID API UPLOADS GH_TOKEN GEN EXT PY
    log "streaming ${TOTAL_MB} MB out tree (obj ${OBJ_MB} + gen ${GEN_MB} + root extras $((TOTAL_MB - OBJ_MB - GEN_MB))) into $EXT volumes (one part on disk at a time) ..."
    ( cd "$SRC_DIR/out" && tar -cf - "${TARPATHS[@]}" ) \
      | { if [ "$EXT" = tar.zst ]; then zstd -1 -T0; else gzip -1; fi; } \
      | split -b "${PART_MB}m" --filter="bash '$TMP/stream_filter.sh'" - "$TMP/raw-part-" \
      || { die "tar/zstd/split (streamed) failed"; rm -rf "$TMP"; exit 1; }
    i=$(cat "$TMP/.idx" 2>/dev/null || echo 0)
    [ "$i" -ge 1 ] || { die "streamed bank produced no volumes"; rm -rf "$TMP"; exit 1; }
    BYTES=$(cat "$TMP/.bytes")
    log "bank: $i volumes, $BYTES bytes (streamed)"
    if ! bash "$TMP/up_one.sh" "$GEN-sha256.txt"; then
      die "manifest upload failed - previous generation left intact"
      rm -rf "$TMP"; exit 1
    fi
    log "all $i volumes + manifest uploaded and size-verified"
  else
    # ---- classic two-pass save (linux/android: roomy disk) --------------
    log "streaming ${TOTAL_MB} MB out tree (obj ${OBJ_MB} + gen ${GEN_MB} + root extras $((TOTAL_MB - OBJ_MB - GEN_MB))) into $EXT volumes ..."
    ( cd "$SRC_DIR/out" && tar -cf - "${TARPATHS[@]}" ) \
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
    # Two full passes: correlated failures (API-wide blips) killed every part
    # at once under -P 4; -P 2 plus a 120s cool-down and a second pass now
    # rides out multi-minute incidents before the bank is declared lost.
    up_pass() { ls "$TMP" | grep -E "^[0-9]+-(vol-[0-9]+\.${EXT}|sha256\.txt)$" | xargs -P 2 -I{} bash "$TMP/up_one.sh" {}; }
    if ! up_pass; then
      log "upload pass 1 failed - cooling down 120s, then one more pass"
      sleep 120
      if ! up_pass; then
        die "some uploads failed - previous generation left intact"
        rm -rf "$TMP"; exit 1
      fi
    fi
    log "all $i volumes + manifest uploaded and size-verified"
  fi

  # retention: keep the TWO newest generations (the newest plus the previous
  # one as fallback if the newest ever proves bad), delete anything older.
  # Volume-based on purpose: an orphan manifest (from a run whose volume
  # upload failed) must never count as a generation - it could otherwise
  # evict the previous-good gen while being unrestorable itself.
  OLDGENS=$(list_assets "$RID" | cut -f1 | grep -E '^[0-9]+-vol-' | cut -d- -f1 | sort -un | head -n -2)
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
