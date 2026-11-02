# Patch Foundry - author Chromium patches for $0

The free CI builds whatever is in `aegis/patches/` - but writing patches
against a moving 40-million-line tree by hand is how forks break. The
foundry replaces guesswork with **asserted edits**: every change declares an
anchor that must exist exactly once in the exact tag you build, and the
engine either produces a clean `.patch` or aborts loudly with the anchor
that moved. Nothing silently no-ops, nothing corrupts the build.

```
scripts/patch-foundry/
├── gen-aegis-patches.sh     the engine: specs -> aegis/patches/NNN-slug.patch
├── verify-gn-args.sh        confirms every hardening GN arg exists in your tag
├── gn-args-hardening.conf   batch-1 hardening args (de-Googling, service pruning)
├── fetch-chromium-src.sh    the cheap fetch (--no-history, ~25 GB, no build)
├── edits/                   your change specs (start from 000-TEMPLATE.conf)
└── additions/               whole NEW files to drop into the tree (no anchors)
```

## The free authoring loop

You never build Chromium locally - you only need the source tree readable
for 30-60 minutes. Two $0 options:

**Option A - GitHub Codespace (recommended).** A personal account gets
120 core-hours and 32 GB of storage free per month; a 4-core Codespace is
30 hours, i.e. several authoring sessions.

1. Push this repo to GitHub (public, as required by the free CI).
2. Repo page -> Code -> Codespaces -> Create (4-core / 32 GB is fine).
3. In the terminal:
   ```
   scripts/patch-foundry/fetch-chromium-src.sh 156.0.8078.79
   ```
   (Use the exact tag from your last CI run summary - `AEGIS_VERSION` in
   the build summary, or chromiumdash.appspot.com. ~25 GB, 30-60 min.)
4. Verify the hardening GN args for this tag:
   ```
   scripts/patch-foundry/verify-gn-args.sh ~/aegis-chromium/src
   ```
   Append the FOUND lines to `aegis/ci/arm64.gn`; remove any MISSING ones.
5. Write a spec: `cp scripts/patch-foundry/edits/000-TEMPLATE.conf \
   scripts/patch-foundry/edits/010-my-change.conf`, edit it, then
   ```
   scripts/patch-foundry/gen-aegis-patches.sh ~/aegis-chromium/src --dry-run
   scripts/patch-foundry/gen-aegis-patches.sh ~/aegis-chromium/src
   ```
6. Commit `aegis/patches/010-my-change.patch`, push, and run the CI
   (Actions -> Build Aegis Browser APK). The runner applies your patch
   before `gn gen`; a clean build is your compile test.
7. Stop the Codespace (it keeps billing core-hours while running).

**Option B - WSL2 on the 8 GB laptop.** Same commands; needs ~35 GB free
disk and nothing else. `fetch --no-history --nohooks` is I/O-bound, not
RAM-bound, so 8 GB is plenty *because we never compile*.

## Writing specs that survive

- `EDITn_GREP` must match **exactly once** (engine-enforced). Prefer long,
  distinctive literals over short generic ones; a unique line of real code
  beats any clever regex.
- `EDITn_OLD`/`EDITn_NEW` are literal. Real newlines are allowed in bash
  single quotes.
- One concern per patch (`010-uma-defaults`, `020-sync-menu`, ...). The CI
  applies them alphabetically; a failed patch aborts the build with its
  filename in the log.
- When a spec aborts: `git log`/cs.chromium.org for the file at your tag,
  find the new spelling, update the anchor. The failure message includes
  the pattern and the file, so this is a 5-minute fix, not a debug session.
- Prefer **additions** over edits for your own code (new files need no
  anchor). Small anchored edits then just wire them in (BUILD.gn source
  lists, settings screens, menu items).

## Why not just write .patch files by hand?

A hand-written diff made against the wrong context either fails `git apply`
in the CI (costing a 5-hour round trip per mistake) or, worse, applies
somewhere unintended. The foundry validates every anchor **against the
exact tag in the checkout** before emitting anything, and restores the tree
on failure. Same reviewable `git diff` output, none of the blind spots.

## Status

- The engine, verifier and fetch script are shell-tested (engine verified
  end-to-end on a mock tree: clean patch emitted, `git apply` clean, abort
  path restores the tree).
- `gn-args-hardening.conf` ships with self-verification because several of
  these args get renamed/dropped between Chromium releases.
- No `edits/*.conf` are enabled yet by design: the first anchors should be
  written against your first pinned CI tag (Option A step 3), not guessed.
