#!/usr/bin/env python3
"""Generate 087-aegis-id-signin.patch from the foundry conf.

Stages the post-054 tree exactly like check087.py does, applies ADDn/EDITn,
and emits a git-apply-able unified diff with proper a/ b/ paths and
new-file-mode lines. Pre-087 AegisEcosystemSettings.java /
aegis_ecosystem_preferences.xml are reconstructed from the ADD hunks of
054-aegis-ecosystem-page.patch (the /tmp/pre087 captures of the original
session are gone).
"""
import difflib
import importlib.util
import pathlib
import re
import shutil
import subprocess
import tempfile

REPO = pathlib.Path("/home/z/my-project/download/aegis-repo")
CACHE = pathlib.Path("/home/z/my-project/chromium-cache/156.0.8078.79")
PATCHES = REPO / "aegis" / "patches"
FOUNDRY = REPO / "scripts" / "patch-foundry"
CONF = FOUNDRY / "edits" / "087-aegis-id-signin.conf"
STAGE = pathlib.Path("/tmp/gen087")
PATCH = REPO / "aegis" / "patches" / "087-aegis-id-signin.patch"
P054 = REPO / "aegis" / "patches" / "054-aegis-ecosystem-page.patch"

spec = importlib.util.spec_from_file_location(
    "check087", "/home/z/my-project/scripts/check087.py")
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)  # noqa: E402 - runs only its defs


def extract_add_from_patch(patch: pathlib.Path, dest: str) -> str:
    """Extract the added lines of a `new file` diff for dest from a patch."""
    text = patch.read_text(encoding="utf-8")
    # split per diff --git section
    secs = re.split(r"(?m)^diff --git ", text)
    for sec in secs:
        if f" b/{dest}" not in sec.split("\n", 1)[0]:
            continue
        if "new file mode" not in sec and "--- /dev/null" not in sec:
            raise SystemExit(f"054 section for {dest} is not a new-file diff")
        body = sec.split("@@", 1)[1]
        lines = []
        for dl in body.splitlines():
            if dl.startswith("@@"):
                continue
            if dl.startswith("+") and not dl.startswith("+++"):
                lines.append(dl[1:])
            elif dl.startswith("\\"):
                lines[-1] = lines[-1]  # keep last line, marker dropped
        return "\n".join(lines) + "\n"
    raise SystemExit(f"no diff section for {dest} in {patch.name}")


def emit(paths, stage):
    """Emit the patch: unified diffs a/... b/... for each changed path."""
    diffs = []
    for rel in paths:
        st = stage / rel
        new = st.read_text(encoding="utf-8")
        if rel in ADDS:
            lines = new.splitlines(keepends=True)
            n = len(lines)
            out = [f"diff --git a/{rel} b/{rel}\n",
                   "new file mode 100644\n",
                   "--- /dev/null\n",
                   f"+++ b/{rel}\n",
                   f"@@ -0,0 +1,{n} @@\n"]
            for l in lines:
                out.append("+" + l)
            diffs.append("".join(out))
        else:
            orig = PRISTINE[rel]
            diffs.append("".join(difflib.unified_diff(
                orig.splitlines(keepends=True), new.splitlines(keepends=True),
                fromfile=f"a/{rel}", tofile=f"b/{rel}", n=3)))
    header = ("# 087 - Aegis ID sign-in: account layer for the aegis-sync backend\n"
              "# (aegis-sync.abdulmateen14.workers.dev). AegisIdManager (email + device\n"
              "# UUID persistence, token/vault keys memory-only) on top of byte-exact\n"
              "# Java ports of the backend's crypto chain (PBKDF2-SHA256 600k -> HKDF\n"
              "# auth key -> PBKDF2 verifier / KEK -> AES-256-GCM vault) and its REST\n"
              "# client (register/login/refresh/devices/revoke/push/pull/cursor). The\n"
              "# Aegis Ecosystem settings page gains an 'Aegis ID' section: status,\n"
              "# sign-in / create-account dialog, device list with revoke, sign-out.\n"
              "# Staged against the post-054 tree (alphabetical application order).\n"
              "# Anchors count-asserted at tag 156.0.8078.79 post-054.\n\n")
    PATCH.write_text(header + "\n".join(diffs), encoding="utf-8")
    print("wrote", PATCH)


conf = check.parse_conf(CONF.read_text(encoding="utf-8"))
add_count = int(conf.get("ADD_COUNT", "0"))
edit_count = int(conf.get("EDIT_COUNT", "0"))
adds = [(conf[f"ADD{i}_SRC"], conf[f"ADD{i}_DEST"]) for i in range(1, add_count + 1)]
edits = [(conf[f"EDIT{i}_FILE"], conf[f"EDIT{i}_GREP"], conf[f"EDIT{i}_OLD"],
          conf[f"EDIT{i}_NEW"]) for i in range(1, edit_count + 1)]

ADDS = {dest for _, dest in adds}
PRISTINE = {}

# --- stage post-054 (real patch staging: full cache sandbox + git apply)
if STAGE.exists():
    shutil.rmtree(STAGE)
STAGE.mkdir(parents=True)
for f in CACHE.rglob("*"):
    if f.is_file():
        dst = STAGE / f.relative_to(CACHE)
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(f.read_bytes())
# The gni anchor (EDIT1) lives in the chrome_java_sources.gni AFTER patches
# 080/081 (they run alphabetically before 087 and touch the same file), so
# the staging chain is 054 + 080 + 081 - the exact prefix the CI applies.
for pn in ("054-aegis-ecosystem-page.patch", "080-aegis-dock.patch",
           "081-aegis-search-sheet.patch"):
    r = subprocess.run(["git", "apply", str(REPO / "aegis/patches" / pn)],
                       cwd=STAGE, capture_output=True, text=True)
    assert r.returncode == 0, f"{pn} staging failed: {r.stderr[:400]}"

gni_rel = "chrome/android/chrome_java_sources.gni"
gni_src = (STAGE / gni_rel).read_text(encoding="utf-8")
PRISTINE[gni_rel] = gni_src
print(f"staged {gni_rel} (054 applied to full cache sandbox)")

# pre-087 files from the 054 patch ADD hunks
pre_rel = {}
for i, (_, dest) in enumerate(edits[:0]):  # placeholder - edits[] uses below
    pass
java_rel = None
xml_rel = None
for idx, (rel, grep, old, new) in enumerate(edits, start=1):
    if rel.endswith("AegisEcosystemSettings.java"):
        java_rel = rel
    elif rel.endswith("aegis_ecosystem_preferences.xml"):
        xml_rel = rel
pre_map = {
    java_rel: ("chrome/android/java/src/org/chromium/chrome/browser/aegis/"
               "AegisEcosystemSettings.java"),
    xml_rel: ("chrome/android/java/res/xml/"
              "aegis_ecosystem_preferences.xml"),
}
for rel, dest in pre_map.items():
    src = extract_add_from_patch(P054, dest)
    PRISTINE[rel] = src
    (STAGE / rel).parent.mkdir(parents=True, exist_ok=True)
    (STAGE / rel).write_text(src, encoding="utf-8")
    print(f"staged pre-087 {rel} from 054 hunk ({len(src)} bytes)")

# --- apply ADDs
for src_rel, dest_rel in adds:
    s = (FOUNDRY / "additions" / src_rel).read_text(encoding="utf-8")
    d = STAGE / dest_rel
    if d.exists():
        raise SystemExit(f"ADD dest exists: {dest_rel}")
    d.parent.mkdir(parents=True, exist_ok=True)
    d.write_text(s, encoding="utf-8")
    print(f"ADD {src_rel} -> {dest_rel}")

# --- apply EDIs
for idx, (rel, grep, old, new) in enumerate(edits, start=1):
    p = STAGE / rel
    s = p.read_text(encoding="utf-8")
    n_lines = sum(1 for line in s.splitlines() if re.search(grep, line))
    assert n_lines == 1, f"EDIT{idx} grep matched {n_lines} lines in {rel}"
    cnt = s.count(old)
    assert cnt == 1, f"EDIT{idx} OLD found {cnt} times in {rel}"
    p.write_text(s.replace(old, new, 1), encoding="utf-8")
    print(f"EDIT{idx} ok ({rel})")

# --- final equality assertions (same as check087)
want_java = (FOUNDRY / "additions/aegis/AegisEcosystemSettings.java").read_text(encoding="utf-8")
want_xml = (FOUNDRY / "additions/aegis/res/xml/aegis_ecosystem_preferences.xml").read_text(encoding="utf-8")
assert (STAGE / java_rel).read_text(encoding="utf-8") == want_java, "java final != additions"
assert (STAGE / xml_rel).read_text(encoding="utf-8") == want_xml, "xml final != additions"
print("final equality assertions PASS")

emit(sorted(set(PRISTINE) | ADDS), STAGE)
