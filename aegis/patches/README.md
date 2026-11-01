# aegis/patches - Aegis Browser Chromium patches

Drop `.patch` files here. The CI applies them to the Chromium checkout in
alphabetical order, before `gn gen`. Name them so the order is meaningful:

    001-aegis-branding.patch
    010-disable-google-url-tracker.patch
    ...

Rules of thumb for the free CI:

- Keep patches SMALL and independent. Every CI run is a full ~5-hour
  rebuild, so a broken patch costs you half a day. One feature per patch.
- Generate patches against the exact Chromium tag you build
  (see `AEGIS_VERSION` in the workflow run summary):

      cd src
      git diff > ../aegis/patches/001-my-change.patch

- The CI first tries `git apply --3way`, then plain `git apply`.
  If both fail the whole build stops with the patch name in the log.
- Prefer changes in Java/Kotlin UI code (chrome/android/java/...) over
  C++: they are easier to write, easier to rebase, and do not slow the
  link step.

Phase 2 of the blueprint (Brave-style hardening) is delivered as a series
of patches here, one batch at a time, tested through this CI.
