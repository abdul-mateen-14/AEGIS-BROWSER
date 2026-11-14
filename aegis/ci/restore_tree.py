#!/usr/bin/env python3
"""Validate / repair a restored Aegis ninja build tree after a resume.

The banked tree (obj/ + gen/ + ninja logs) was compiled from:
  - chromium tag T   (the dispatch input; token 1 of out/<dir>/.aegis_head)
  - aegis repo sha S (token 2 of out/<dir>/.aegis_head)

On resume, compare with the current values:
  * chromium tag differs          -> drop the tree (wrong version; rebuild)
  * aegis repo sha identical      -> tree is exactly consistent; do nothing
  * aegis/ci/** changed           -> gn args / wrapper changed; drop the tree
  * aegis/branding/** changed    -> desktop lanes (linux_x64/win_x64): delete
                                    the theme-pak outputs the apply scripts
                                    feed (grit ACTION edges are invisible to
                                    ninja -t deps) and keep the bank;
                                    android (arm64): drop the tree (the res
                                    graph has no safe output mapping yet)
  * aegis/patches/*.patch changed -> map the chromium files those patches
    touch via `ninja -t deps` and delete ONLY the outputs that depend on
    them (a fix commit costs seconds of recompile, not the whole bank)
  * anything else changed         -> docs/workflows/etc.; ignore

Always exits 0; prints what it did.

2026-11-07 defect (#33 linux): drop_tree() removed obj/ + gen/ but left
build.ninja / toolchain.ninja / build.ninja.d behind. Those manifests
reference the deleted subninjas (toolchain.ninja -> obj/*/*.ninja) and
deleted gen inputs (build.ninja.d), and ninja's self-regeneration edge
lives INSIDE build.ninja, so it cannot repair itself: the run died with
"error in depfile build.ninja.d: deps input gen/cc/mojom/mojom.ts_typemap_
config not exist" before compiling a single file. drop_tree() now also
removes the manifest set and regenerates it via `gn gen` (args.gn survives
the drop and pins the args). The surgical path never deletes *.ninja /
build.ninja* / toolchain.ninja or anything build.ninja.d lists as a
manifest input.
"""
import os
import shutil
import subprocess
import sys


def sh(args, cwd=None, check=True, timeout=180):
    r = subprocess.run(args, cwd=cwd, capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(" ".join(args) + ": " + (r.stderr or "").strip()[:300])
    return r.stdout.strip()


MANIFEST_FILES = ("build.ninja", "build.ninja.d", "toolchain.ninja",
                  "build.ninja.stamp")

# grit/repack ACTION-edge outputs fed (directly or via copy) by the branding
# apply scripts. ninja -t deps cannot map them (ACTION edges, not depfiles),
# so a branding change deletes these explicitly on the desktop lanes instead
# of dropping the whole bank. Pak repack + (windows) mini_installer relink is
# minutes; the chrome binary itself never embeds pak content.
BRANDING_PAK_OUTPUTS = (
    "gen/chrome/app/theme/theme_resources_100_percent.pak",
    "gen/chrome/app/theme/theme_resources_200_percent.pak",
    "gen/chrome/app/theme/theme_resources_300_percent.pak",
    "gen/chrome/app/theme/webui_theme_resources_100_percent.pak",
    "gen/chrome/app/theme/webui_theme_resources_200_percent.pak",
    "gen/chrome/app/theme/webui_theme_resources_300_percent.pak",
    "gen/chrome/app/theme/chrome_unscaled_resources.pak",
    "gen/components/resources/components_resources_100_percent.pak",
    "gen/components/resources/components_resources_200_percent.pak",
    "gen/components/resources/components_resources_300_percent.pak",
    "chrome_100_percent.pak",
    "chrome_200_percent.pak",
    "chrome_300_percent.pak",
)


def find_gn(src):
    cands = [shutil.which("gn"),
             os.path.join(src, "buildtools", "linux64", "gn"),
             os.path.join(src, "buildtools", "win", "gn.exe")]
    for c in cands:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return None


def regenerate_manifest(src, rel_out):
    """Rebuild build.ninja + subninjas after a drop (args.gn survives)."""
    gn = find_gn(src)
    if not gn:
        print("WARNING: gn not found - run 'gn gen' before ninja or the "
              "stale manifest will block ninja's self-regeneration")
        return False
    try:
        r = subprocess.run([gn, "gen", rel_out], cwd=src,
                           capture_output=True, text=True, timeout=900)
    except Exception as e:  # noqa: BLE001
        print(f"WARNING: gn gen failed ({e}) - re-dispatch to retry")
        return False
    if r.returncode != 0:
        print(f"WARNING: gn gen failed: {(r.stderr or '').strip()[:200]} - "
              "re-dispatch to retry")
        return False
    print("manifest regenerated after drop (gn gen) - tree is loadable again")
    return True


def drop_tree(out, src=None, odir=None):
    for sub in ("obj", "gen", ".ninja_deps", ".ninja_log"):
        p = os.path.join(out, sub)
        if os.path.isdir(p):
            shutil.rmtree(p, ignore_errors=True)
        elif os.path.exists(p):
            os.remove(p)
    # The manifest set references the deleted subninjas and gen inputs and
    # cannot self-repair (the regen edge lives inside build.ninja). Wipe it
    # and regenerate coherently; without this the next ninja dies on
    # "error in depfile build.ninja.d: deps input ... not exist" (#33).
    for f in MANIFEST_FILES:
        p = os.path.join(out, f)
        if os.path.exists(p):
            os.remove(p)
    print("build tree dropped - the next ninja run compiles from scratch")
    if src and odir:
        regenerate_manifest(src, os.path.join("out", odir))


def manifest_protect_set(out):
    """Paths (relative to out/) ninja's own graph depends on; never delete."""
    prot = set(MANIFEST_FILES)
    dp = os.path.join(out, "build.ninja.d")
    if os.path.exists(dp):
        txt = open(dp, errors="replace").read().replace("\\\n", " ")
        for tok in txt.split():
            t = tok.strip()
            if t and ":" not in t and not t.startswith("out/"):
                prot.add(t)
    return prot


def patch_target_paths(content):
    """chromium-relative paths a patch file writes to (its '+++ b/' lines)."""
    paths = set()
    for line in content.splitlines():
        if line.startswith("+++ ") and not line.startswith("+++ /dev/null"):
            tok = line[4:].split("\t")[0].strip()
            if tok.startswith("b/"):
                tok = tok[2:]
            if tok:
                paths.add(tok)
    return paths


def main():
    if len(sys.argv) != 5:
        print("usage: restore_tree.py <src> <outdir_name> <aegis_repo> <current_tag>")
        sys.exit(0)
    src, odir, arepo, cur_tag = sys.argv[1:5]
    out = os.path.join(src, "out", odir)
    hf = os.path.join(out, ".aegis_head")
    if not os.path.exists(hf):
        print("no .aegis_head marker - nothing to validate")
        return
    tok = open(hf).read().split()
    if len(tok) < 2:
        print(".aegis_head malformed - dropping bank")
        drop_tree(out, src, odir)
        return
    btag, bsha = tok[0], tok[1]

    if btag != cur_tag:
        print(f"banked chromium tag {btag} != requested {cur_tag} - dropping bank")
        drop_tree(out, src, odir)
        return

    cur_sha = sh(["git", "rev-parse", "HEAD"], cwd=arepo, check=False)
    if not cur_sha:
        print("cannot resolve current aegis sha - keeping bank (assume unchanged)")
        return
    if cur_sha == bsha:
        print(f"aegis inputs unchanged since bank ({bsha[:10]}) - tree is consistent")
        return

    # aegis repo changed: diff it (deepen the shallow clone if needed)
    changed = None
    try:
        subprocess.run(["git", "fetch", "-q", "--depth=80", "origin", "HEAD"],
                       cwd=arepo, capture_output=True, timeout=240)
        r = subprocess.run(["git", "diff", "--name-only", bsha, "HEAD"],
                           cwd=arepo, capture_output=True, text=True, timeout=120)
        if r.returncode == 0:
            changed = [l.strip() for l in r.stdout.splitlines() if l.strip()]
    except Exception as e:  # noqa: BLE001
        print(f"diff unavailable ({e}) - dropping bank for safety")
        drop_tree(out, src, odir)
        return
    if changed is None:
        print("cannot diff against banked sha - dropping bank for safety")
        drop_tree(out, src, odir)
        return

    print(f"aegis changed since bank: {bsha[:10]} -> {cur_sha[:10]} ({len(changed)} files)")

    # bank.sh / restore_tree.py / restamp.sh do not affect gn args, compiler
    # flags or any compiled object - changing them must NOT drop the bank.
    # The remaining ci files are LANE-SCOPED: arm64.gn only affects the
    # arm64 (Android) bank; linux_x64.gn / win_x64.gn / atomic_cc.c only the
    # desktop banks. A change scoped to another lane must NOT drop this
    # lane's bank (e.g. adding optimize_android_resources=false to arm64.gn
    # keeps the linux bank intact). An unknown odir falls back to treating
    # every ci file as build-affecting (safe direction).
    ci_lane_files = {
        "arm64": {"arm64.gn"},
        "linux_x64": {"linux_x64.gn", "atomic_cc.c"},
        "win_x64": {"win_x64.gn", "atomic_cc.c"},
    }
    this_lane = ci_lane_files.get(
        odir, {"arm64.gn", "linux_x64.gn", "win_x64.gn", "atomic_cc.c"})
    ci_build_affecting = [
        p for p in changed
        if p.startswith("aegis/ci/") and os.path.basename(p) in this_lane
    ]
    if ci_build_affecting:
        print("gn args or compiler wrapper changed - dropping bank for safety")
        drop_tree(out, src, odir)
        return
    if any(p.startswith("aegis/branding/") for p in changed):
        if odir == "arm64":
            print("branding hooks changed - dropping bank for safety "
                  "(android res graph has no safe output mapping yet)")
            drop_tree(out, src, odir)
            return
        print(f"branding hooks changed - surgical pak invalidation "
              f"({len(BRANDING_PAK_OUTPUTS)} outputs); bank kept")
        for rel in BRANDING_PAK_OUTPUTS:
            p = os.path.join(out, rel)
            if os.path.isdir(p):
                shutil.rmtree(p, ignore_errors=True)
            elif os.path.exists(p):
                os.remove(p)
        # fall through: patch changes (if any) are mapped as usual below

    patch_files = [p for p in changed if p.startswith("aegis/patches/")]
    targets = set()
    for p in patch_files:
        cur_path = os.path.join(arepo, p)
        if os.path.exists(cur_path):                      # added or modified
            with open(cur_path, errors="replace") as f:
                targets |= patch_target_paths(f.read())
            if os.path.exists(os.path.join(arepo, ".git")):
                old = sh(["git", "show", f"{bsha}:{p}"], cwd=arepo, check=False)
                if old:
                    targets |= patch_target_paths(old)    # old-version targets too
        else:                                             # deleted patch
            old = sh(["git", "show", f"{bsha}:{p}"], cwd=arepo, check=False)
            if old:
                targets |= patch_target_paths(old)
    # force-invalidation list (see aegis/invalidation_extra.txt):
    # - plain paths: one-time marker-bug recovery (objects predating patches
    #   their marker claims) - remove once all lanes bank a truthful gen.
    # - "gen:<rel>" paths: DURABLE generated-artifact invalidation. ninja
    #   -t deps only records depfile edges (CXX), never ACTION edges
    #   (json_to_struct, grd -> pak), so a patched .json/.grd can never
    #   invalidate its own generated outputs through the mapping below -
    #   and restamp pins sources to the bank epoch while banked outputs
    #   keep newer mtimes, so ninja also sees the action as up to date.
    #   Deleting the gen paths directly forces the generating action to
    #   re-run before any consumer compiles (APK RED 42388197815: banked
    #   prepopulated_engines.h predated patch 098's 'aegis' engine).
    extra_f = os.path.join(arepo, "aegis", "invalidation_extra.txt")
    if os.path.exists(extra_f):
        extra = [l.strip() for l in open(extra_f, errors="replace")
                 if l.strip() and not l.strip().startswith("#")]
        if extra:
            print(f"force-invalidation: +{len(extra)} paths from invalidation_extra.txt")
        gen_extra = [p[len("gen:"):] for p in extra if p.startswith("gen:")]
        targets |= {p for p in extra if not p.startswith("gen:")}
        for rel in gen_extra:
            p = os.path.join(out, rel)
            if os.path.isdir(p):
                shutil.rmtree(p, ignore_errors=True)
                print(f"force-invalidated gen dir: {rel}")
            elif os.path.exists(p):
                os.remove(p)
                print(f"force-invalidated gen file: {rel}")
    # DURABLE webui/locale/theme invalidation (r68 lesson, 2026-11-14):
    # grit/repack/rollup products (grd/grdp -> pak, webui TS bundles, locale
    # paks, theme paks, copied raw resources) are ACTION edges - ninja -t
    # deps cannot map them (see the gen: comment above) and a pack action
    # whose input list omits the changed file never reruns, so patched
    # webui sources can ship a stale packed product (r68: patch 127's aegis
    # NTP glyph + neutral hint were absent from the deb while patch 128's
    # theme PNGs landed via explicit repack inputs). When a patch touches
    # anything in these domains, delete the packed gen subtrees AND the
    # final paks directly; webui + locale repack is minutes and the chrome
    # binary itself never embeds pak content.
    WEBUI_PREFIXES = (
        "chrome/browser/resources/", "ui/webui/resources/",
        "chrome/app/theme/", "chrome/app/resources/",
        "chrome/app/generated_resources.grd",
        "components/resources/", "components/search_provider_logos/",
    )
    if any(t.startswith(WEBUI_PREFIXES) for t in targets):
        print("webui/locale/theme domain touched - force-invalidating packed outputs")
        for rel in ("gen/chrome/browser/resources", "gen/ui/webui/resources",
                    "gen/chrome/app/resources", "gen/chrome/app/theme",
                    "gen/components/resources", "locales"):
            p = os.path.join(out, rel)
            if os.path.isdir(p):
                shutil.rmtree(p, ignore_errors=True)
                print(f"force-invalidated gen dir: {rel}")
        for rel in ("resources.pak", "chrome_100_percent.pak",
                    "chrome_200_percent.pak", "chrome_300_percent.pak"):
            p = os.path.join(out, rel)
            if os.path.exists(p):
                os.remove(p)
                print(f"force-invalidated pak: {rel}")
    if not targets:
        print("aegis changes touch no compiled sources - tree is consistent")
        return

    print(f"patches touch {len(targets)} chromium files - mapping dependents...")
    r = subprocess.run(["ninja", "-C", os.path.join("out", odir), "-t", "deps"],
                       cwd=src, capture_output=True, text=True, timeout=300)
    if r.returncode != 0:
        print("ninja -t deps failed - dropping bank for safety")
        drop_tree(out, src, odir)
        return

    prot = manifest_protect_set(out)
    cur_out, killed, skipped = None, 0, 0
    for line in r.stdout.splitlines():
        if not line:
            cur_out = None
            continue
        if not line[:1].isspace():
            cur_out = line.split(":", 1)[0].strip()
        elif cur_out:
            dep = line.strip()
            if dep.startswith("../../") and dep[6:] in targets:
                if cur_out.endswith(".ninja") or cur_out in prot:
                    skipped += 1
                else:
                    p = os.path.join(out, cur_out)
                    if os.path.exists(p):
                        os.remove(p)
                        killed += 1
                cur_out = None
    print(f"invalidated {killed} outputs that depend on patched files "
          f"({skipped} manifest files protected) - tree repaired")


if __name__ == "__main__":
    main()
