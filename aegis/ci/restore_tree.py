#!/usr/bin/env python3
"""Validate / repair a restored Aegis ninja build tree after a resume.

The banked tree (obj/ + gen/ + ninja logs) was compiled from:
  - chromium tag T   (the dispatch input; token 1 of out/<dir>/.aegis_head)
  - aegis repo sha S (token 2 of out/<dir>/.aegis_head)

On resume, compare with the current values:
  * chromium tag differs          -> drop the tree (wrong version; rebuild)
  * aegis repo sha identical      -> tree is exactly consistent; do nothing
  * aegis/ci/** changed           -> gn args / wrapper changed; drop the tree
  * aegis/branding/** changed     -> apply scripts edit sources in place; drop
  * aegis/patches/*.patch changed -> map the chromium files those patches
    touch via `ninja -t deps` and delete ONLY the outputs that depend on
    them (a fix commit costs seconds of recompile, not the whole bank)
  * anything else changed         -> docs/workflows/etc.; ignore

Always exits 0; prints what it did.
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


def drop_tree(out):
    for sub in ("obj", "gen", ".ninja_deps", ".ninja_log"):
        p = os.path.join(out, sub)
        if os.path.isdir(p):
            shutil.rmtree(p, ignore_errors=True)
        elif os.path.exists(p):
            os.remove(p)
    print("build tree dropped - the next ninja run compiles from scratch")


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
        drop_tree(out)
        return
    btag, bsha = tok[0], tok[1]

    if btag != cur_tag:
        print(f"banked chromium tag {btag} != requested {cur_tag} - dropping bank")
        drop_tree(out)
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
        drop_tree(out)
        return
    if changed is None:
        print("cannot diff against banked sha - dropping bank for safety")
        drop_tree(out)
        return

    print(f"aegis changed since bank: {bsha[:10]} -> {cur_sha[:10]} ({len(changed)} files)")

    # bank.sh / restore_tree.py do not affect gn args, compiler flags or any
    # compiled object - changing them must NOT drop the bank. Only the
    # build-affecting ci files (gn args, atomic_cc wrapper) do.
    ci_build_affecting = [
        p for p in changed
        if p.startswith("aegis/ci/") and os.path.basename(p) not in (
            "bank.sh", "restore_tree.py", "restamp.sh")
    ]
    if ci_build_affecting:
        print("gn args or compiler wrapper changed - dropping bank for safety")
        drop_tree(out)
        return
    if any(p.startswith("aegis/branding/") for p in changed):
        print("branding hooks changed - dropping bank for safety")
        drop_tree(out)
        return

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
    # one-time force-invalidation list (see aegis/invalidation_extra.txt):
    # the marker bug shipped banks whose objects predate patches their marker
    # claims are included; the git diff alone can never re-discover those, so
    # the extra list adds them unconditionally until it is removed.
    extra_f = os.path.join(arepo, "aegis", "invalidation_extra.txt")
    if os.path.exists(extra_f):
        extra = [l.strip() for l in open(extra_f, errors="replace")
                 if l.strip() and not l.strip().startswith("#")]
        if extra:
            targets |= set(extra)
            print(f"force-invalidation: +{len(extra)} paths from invalidation_extra.txt")
    if not targets:
        print("aegis changes touch no compiled sources - tree is consistent")
        return

    print(f"patches touch {len(targets)} chromium files - mapping dependents...")
    r = subprocess.run(["ninja", "-C", os.path.join("out", odir), "-t", "deps"],
                       cwd=src, capture_output=True, text=True, timeout=300)
    if r.returncode != 0:
        print("ninja -t deps failed - dropping bank for safety")
        drop_tree(out)
        return

    cur_out, killed = None, 0
    for line in r.stdout.splitlines():
        if not line:
            cur_out = None
            continue
        if not line[:1].isspace():
            cur_out = line.split(":", 1)[0].strip()
        elif cur_out:
            dep = line.strip()
            if dep.startswith("../../") and dep[6:] in targets:
                p = os.path.join(out, cur_out)
                if os.path.exists(p):
                    os.remove(p)
                    killed += 1
                cur_out = None
    print(f"invalidated {killed} outputs that depend on patched files - tree repaired")


if __name__ == "__main__":
    main()
