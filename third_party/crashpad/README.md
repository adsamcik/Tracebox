# Crashpad Pin and Patch Policy

Tracebox uses Crashpad revision `efdc820b087c20eec9e32cb5e5b1a63dcf73a724`.

Run `tools\crashpad\Acquire-Crashpad.ps1` to materialize the verified source under the ignored `third_party\crashpad\checkout` directory. `source-lock.json` records immutable archive URLs, revisions, sizes, SHA-256 digests, provenance, and licenses.

Patches are never edited in-place in downloaded source and are never fetched dynamically. `patches/series` is the ordered allowlist. Every non-empty patch must:

1. identify its requirement and upstream source revision;
2. contain no uploader, network, remote configuration, or transport code;
3. affect only Android build integration, capture-only exclusions, hard bounds, 4/16 KiB compatibility, or Tracebox lifecycle hooks;
4. be reproducibly applied by the acquisition script;
5. trigger the complete Crashpad privacy/handler/emergency/ANR feasibility matrix.

The empty initial series means the upstream pin is acquired without an unreviewed local modification. Feasibility changes add explicit patches and refresh the patch-set digest.
