# Aegis Shield — built-in, Brave-style ad & tracker blocking

Aegis Shield blocks third-party ad and tracker traffic at the **network
service level** — inside the browser engine, before a connection is ever
opened. It needs no extension, no external app, and no network calls of
its own, which fits Aegis's $0 / no-cloud-dependency rule.

## How it works

| Layer | Piece |
| --- | --- |
| Hook | `NetworkServiceNetworkDelegate::OnBeforeURLRequest` (patch `070-aegis-shield-engine.patch`) |
| Engine | `services/network/aegis_shield.{h,cc}` — ~60 lines, no dependencies |
| Data | `services/network/aegis_shield_blocklist.cc` — 92,957 domains, generated |
| UI | none (always-on in v1; shield toggle is planned for a later batch) |

Every HTTP(S) request passes through the delegate before any connection is
opened. The engine answers one question — block or not — in O(log n) via
binary search over a sorted array. Blocked requests fail with
`net::ERR_BLOCKED_BY_CLIENT`, the exact error an adblock extension
produces, so pages degrade the same way they do under uBlock Origin.

## Blocking policy (three safety valves)

A request is blocked **only when all of the following hold**:

1. Scheme is HTTP or HTTPS (never blobs, websockets-as-handshakes, etc.).
2. The target host is **not** on the allowlist.
3. There **is** an initiator (browser-initiated requests — omnibox
   navigation, Aegis Sync, component updates — always pass).
4. The initiator host differs from the target host (**same-party requests
   always pass** — first-party browsing cannot break).
5. The target host is on the blocklist (exact match or subdomain).

Hard-allowlisted regardless of any filter list: `am.infy.uk`,
`corex.site.je`, `os-cloudflare.pages.dev`, `agis-soc.whf.bz`,
`abdulmateen14.workers.dev` and `*.workers.dev` (the Aegis ecosystem and
the Aegis Sync backend).

## Where the data comes from

`scripts/aegis-shield/gen_blocklist.py` fetches **EasyList** and
**EasyPrivacy** and keeps only network rules whose meaning survives
compilation to "this host, all request types":

- `||host^` bare rules — kept.
- Optioned rules where every option is a request type or a
  policy-compatible modifier (`$third-party`, `$script`, `$image`, …,
  `$important`, `$popup`) — kept, widened to all types.
- Everything else (`$domain=`, `$document`, `$csp`, negations, regex
  rules, path-anchored rules, element-hiding `##`) — skipped on purpose.
- `@@||host^` bare exceptions — kept as allowlist entries; optioned
  exceptions are skipped (they mean something narrower than "never block").

Regenerate and re-emit:

```bash
python3 scripts/aegis-shield/gen_blocklist.py
bash scripts/patch-foundry/gen-aegis-patches.sh <chromium-src>   # needs the mock-tree flow
```

## Scope (v1) and what comes later

| In v1 | Later batches |
| --- | --- |
| Network-level blocking of 92,957 ad/tracker hosts | Cosmetic (element) filtering |
| Third-party only, first-party safe | Per-site shield toggle in the UI |
| EasyList + EasyPrivacy host rules | Custom filter lists / user allowlist UI |
| Zero runtime cost, zero network calls | Blocked-count indicator |

Verification: `scripts/test-shield-semantics.py` (run against an applied
tree) asserts the canonical cases — DoubleClick/Google Analytics blocked
for third parties, same-party and browser-initiated traffic untouched,
Aegis ecosystem/sync untouchable.
