#!/usr/bin/env python3
"""Aegis Shield - generate the compile-time blocklist source file.

Fetches EasyList + EasyPrivacy, extracts only simple `||domain^` network
rules (no wildcards, no regex, no element-hiding), de-duplicates, removes
anything covered by @@ exceptions or the Aegis ecosystem allowlist, and
emits a sorted C++ source file into the Patch Foundry additions tree.

Usage:
  python3 scripts/aegis-shield/gen_blocklist.py [--out FILE]

Output (default):
  scripts/patch-foundry/additions/aegis/shield/aegis_shield_blocklist.cc

Regenerate whenever you want fresher lists, then re-run the foundry and
commit the updated patch. The file is fully deterministic apart from the
"generated on" date.
"""

import argparse
import datetime
import os
import re
import sys
import urllib.request

SOURCES = [
    "EasyList",
    "https://easylist.to/easylist/easylist.txt",
    "https://raw.githubusercontent.com/easylist/easylist/master/easylist.txt",
    "EasyPrivacy",
    "https://easylist.to/easylist/easyprivacy.txt",
    "https://raw.githubusercontent.com/easylist/easylist/master/easyprivacy.txt",
]

# Never blocked, always checked first (suffix match): the Aegis ecosystem
# and the Aegis sync backend. "workers.dev" allows every *.workers.dev.
STATIC_ALLOW = [
    "am.infy.uk",
    "corex.site.je",
    "os-cloudflare.pages.dev",
    "agis-soc.whf.bz",
    "abdulmateen14.workers.dev",
    "workers.dev",
]

DOMAIN_RE = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

# Rule options that keep their semantics when widened to "block this host
# for ALL request types": request-type tokens, plus modifiers that agree
# with the engine's third-party-only policy. Everything else ($document,
# $domain=..., $csp, ~negations, ...) is skipped - widening those would
# change what the rule author meant.
SAFE_OPTIONS = {
    "third-party", "popup", "important", "all",
    "script", "image", "stylesheet", "xmlhttprequest", "subdocument",
    "object", "media", "font", "websocket", "ping", "other",
}

# Curated core ad/tracker infrastructure. These sit on top of the filter
# lists so that canonical trackers whose rules always carry options are
# covered regardless of parsing policy. Pruned by @@ exceptions like any
# other rule.
CORE_BLOCK = [
    "doubleclick.net", "google-analytics.com", "googlesyndication.com",
    "googletagservices.com", "googleadservices.com", "adsystem.google.com",
    "adservice.google.com", "2mdn.net", "admob.com", "crashlytics.com",
    "adnxs.com", "adsafeprotected.com", "amazon-adsystem.com",
    "appsflyer.com", "branch.io", "casalemedia.com", "criteo.com",
    "criteo.net", "demdex.net", "dotomi.com", "everesttech.net",
    "facebook.net", "gumgum.com", "hotjar.com", "improvedigital.com",
    "innovid.com", "yieldmo.com", "liveintent.com", "matomo.cloud",
    "mediavine.com", "moatads.com", "mookie1.com", "openx.net",
    "outbrain.com", "pixel.rubiconproject.com", "pubmatic.com",
    "quantserve.com", "quantcount.com", "revcontent.com", "rubiconproject.com",
    "scorecardresearch.com", "sharethrough.com", "smartadserver.com",
    "spotxchange.com", "taboola.com", "teads.tv", "thebrighttag.com",
    "tidaltv.com", "advertising.com", "yielddesk.com",
]


def fetch(url: str) -> str:
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "aegis-shield-gen/1.0"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.read().decode("utf-8", errors="replace")
        except Exception as exc:  # noqa: BLE001
            if attempt == 2:
                raise
            print(f"  retry {url}: {exc}", file=sys.stderr)


def parse(text: str, block: set, allow: set, stats: dict) -> None:
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("!") or line.startswith("["):
            continue
        # Element hiding / cosmetic filters are out of scope for v1.
        if "##" in line or "#@#" in line or "#$#" in line or "#?#@" in line or "#?#" in line:
            continue
        exception = line.startswith("@@||")
        if exception:
            body = line[4:]
        elif line.startswith("||"):
            body = line[2:]
        else:
            # Plain-domain and regex rules are not handled in v1.
            stats["skipped_other"] += 1
            continue

        # Split off rule options. A rule keeps its meaning when widened to
        # "block/allow this host for ALL request types" only if every one
        # of its option tokens is a request type or a policy-compatible
        # modifier (see SAFE_OPTIONS). $third-party matches the engine
        # exactly; $important/$popup only strengthen it.
        options = ""
        if "$" in body:
            body, options = body.split("$", 1)
        if options:
            tokens = [t for t in options.split(",") if t]
            if any(t not in SAFE_OPTIONS for t in tokens):
                stats["skipped_options"] += 1
                continue
            if exception:
                # Exceptions: keep only bare ones - an optioned exception
                # (e.g. @@||host^$document) means something narrower than
                # "never block this host".
                stats["skipped_options"] += 1
                continue
        elif exception:
            pass  # bare exception -> true allow-all

        # Path-anchored rules (||host/path) must not become host rules.
        if "/" in body:
            stats["skipped_path"] += 1
            continue
        # Drop a single trailing ^ anchor; what remains must be a plain host.
        body = body[:-1] if body.endswith("^") else body
        if not body or any(c in body for c in "*:=~,") or ":" in body:
            stats["skipped_complex"] += 1
            continue
        body = body.lower().rstrip(".")
        if not DOMAIN_RE.match(body):
            stats["skipped_invalid"] += 1
            continue
        if exception:
            allow.add(body)
            stats["allow"] += 1
        else:
            block.add(body)
            stats["block"] += 1


def prune(block: set, allow: set) -> set:
    """Remove blocked domains that are allowed themselves or allowed by
    an allow-list suffix (an allow rule wins for its whole subtree)."""
    out = set()
    for host in block:
        parts = host.split(".")
        covered = False
        for i in range(len(parts)):
            candidate = ".".join(parts[i:])
            if candidate in allow:
                covered = True
                break
        if not covered:
            out.add(host)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(
        os.path.dirname(__file__),
        "..", "patch-foundry", "additions", "aegis", "shield",
        "aegis_shield_blocklist.cc"))
    args = ap.parse_args()
    out = os.path.abspath(args.out)

    block: set = set()
    allow: set = set()
    stats = {"block": 0, "allow": 0, "skipped_other": 0, "skipped_complex": 0,
             "skipped_invalid": 0, "skipped_options": 0, "skipped_path": 0}

    it = iter(SOURCES)
    for name, primary, mirror in zip(it, it, it):
        print(f"fetching {name} ...")
        try:
            text = fetch(primary)
        except Exception:
            print(f"  primary failed, trying mirror ...", file=sys.stderr)
            text = fetch(mirror)
        parse(text, block, allow, stats)

    block |= set(d.lower() for d in CORE_BLOCK)
    allow |= set(STATIC_ALLOW)
    before = len(block)
    block = prune(block, allow)
    pruned = before - len(block)

    block_sorted = sorted(block)
    allow_sorted = sorted(allow)
    today = datetime.date.today().isoformat()

    with open(out, "w", encoding="utf-8") as f:
        f.write("// Copyright 2026 The Aegis Browser Authors\n")
        f.write("// Use of this source code is governed by a BSD-style license that can be\n")
        f.write("// found in the LICENSE file.\n")
        f.write("//\n")
        f.write("// GENERATED FILE - do not edit by hand.\n")
        f.write(f"// Generated on {today} by scripts/aegis-shield/gen_blocklist.py from:\n")
        f.write("//   EasyList + EasyPrivacy (network rules, `||domain^` form only)\n")
        f.write(f"// Blocklist: {len(block_sorted)} domains\n")
        f.write(f"// Allowlist: {len(allow_sorted)} domains "
                f"(EasyList/EasyPrivacy @@ exceptions + Aegis ecosystem)\n")
        f.write("\n")
        f.write('#include "services/network/aegis_shield_blocklist.h"\n')
        f.write("\n")
        f.write("namespace aegis {\n\n")
        f.write("const char* const kAegisBlockDomains[] = {\n")
        for d in block_sorted:
            f.write(f'    "{d}",\n')
        f.write("};\n\n")
        f.write("const size_t kAegisBlockDomainsCount =\n")
        f.write("    sizeof(kAegisBlockDomains) / sizeof(kAegisBlockDomains[0]);\n\n")
        f.write("const char* const kAegisAllowDomains[] = {\n")
        for d in allow_sorted:
            f.write(f'    "{d}",\n')
        f.write("};\n\n")
        f.write("const size_t kAegisAllowDomainsCount =\n")
        f.write("    sizeof(kAegisAllowDomains) / sizeof(kAegisAllowDomains[0]);\n\n")
        f.write("}  // namespace aegis\n")

    print(f"block rules parsed : {stats['block']}")
    print(f"allow rules parsed : {stats['allow']}")
    print(f"pruned by allowlist: {pruned}")
    print(f"final block domains: {len(block_sorted)}")
    print(f"final allow domains: {len(allow_sorted)}")
    print(f"skipped: other={stats['skipped_other']} complex={stats['skipped_complex']} "
          f"invalid={stats['skipped_invalid']} options={stats['skipped_options']} "
          f"path={stats['skipped_path']}")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
