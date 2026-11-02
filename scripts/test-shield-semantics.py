#!/usr/bin/env python3
"""Sanity-test the generated blocklist semantics exactly as the C++ does."""
import re
import sys

SRC = "services/network/aegis_shield_blocklist.cc"
src = open(SRC).read()

block_part = src.split("kAegisBlockDomains[]")[1].split("kAegisBlockDomainsCount")[0]
allow_part = src.split("kAegisAllowDomains[]")[1].split("kAegisAllowDomainsCount")[0]
block = set(re.findall(r'"([^"]+)"', block_part))
allow = set(re.findall(r'"([^"]+)"', allow_part))

def host_in_list(host, lst):
    parts = host.split(".")
    return any(".".join(parts[i:]) in lst for i in range(len(parts)))

def should_block(host, initiator_host):
    if host_in_list(host, allow):
        return False, "allow"
    if initiator_host is None:
        return False, "browser-initiated"
    if initiator_host == host:
        return False, "same-party"
    return (host_in_list(host, block), "list")

cases = [
    ("ads.doubleclick.net", "example.com", True),
    ("www.googletagservices.com", "example.com", True),
    ("google-analytics.com", "example.com", True),
    ("googleads.g.doubleclick.net", "example.com", True),
    ("example.com", "example.com", False),          # same-party
    ("example.com", None, False),                   # omnibox
    ("am.infy.uk", "example.com", False),           # ecosystem allow
    ("sync.abdulmateen14.workers.dev", "example.com", False),  # sync allow
    ("example.com", "ads.doubleclick.net", False),  # normal site
]
ok = True
for host, init, want in cases:
    got, why = should_block(host, init)
    mark = "PASS" if got == want else "FAIL"
    if got != want:
        ok = False
    print(f"{mark}  {host} (init={init}) -> {got} ({why})")
print("block:", len(block), "allow:", len(allow))
sys.exit(0 if ok else 1)
