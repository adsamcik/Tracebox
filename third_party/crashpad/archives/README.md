# Verified source archives

The Chromium archive endpoint regenerates gzip wrappers for immutable commits, so
its byte stream is not stable even when the extracted source tree is unchanged.
These checked-in archives are the exact authenticated source inputs for the
revisions in `../source-lock.json`.

`Acquire-Crashpad.ps1` verifies each archive size and SHA-256 before parsing or
extracting it, then verifies the normalized source-tree hash. The archives are
upstream Apache-2.0, BSD-3-Clause, and Zlib-licensed source material identified
by the lock file; they are not compiled artifacts.
