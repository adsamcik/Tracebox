# ADR-0002: Crashpad Source and Privacy Profile

## Status

Accepted by implementation assignment

## Decisions

This ADR freezes section-27 decisions 1, 2, and 11.

- Crashpad is pinned to upstream commit `efdc820b087c20eec9e32cb5e5b1a63dcf73a724`.
- Source is acquired only from immutable `chromium.googlesource.com` commit archives. The archive and every required DEPS archive are SHA-256 verified before extraction.
- Local patches are ordered files under `third_party/crashpad/patches/series`. A patch changes only Android build integration, uploader exclusion, hard bounds, page compatibility, or Tracebox capture hooks. Every patch records rationale, upstream status, and source-file hashes.
- Updating Crashpad requires a new reviewed pin, refreshed provenance and licenses, a clean patch rebase, and the complete Phase 0 feasibility matrix.

The permitted raw minidump stream profile is fixed:

1. `ThreadListStream` with Crashpad's normal captured context and stack memory.
2. `ModuleListStream`.
3. `ExceptionStream`.
4. `SystemInfoStream`.
5. `MiscInfoStream`.
6. Crashpad Linux streams for `/proc/cpuinfo`, `/proc/status`, `/etc/lsb-release`, and mappings when emitted by the pinned Android implementation.
7. `UnloadedModuleListStream` when naturally emitted.

No Tracebox custom stream, client annotation containing a Tracebox internal identity, extra memory region, heap range, or arbitrary attachment is permitted. Streams actually emitted are inventoried by the feasibility parser; an unexpected stream fails the gate.

Every minidump is C2 in full. Normalized structural summaries may use only exception code/signal, bounded generated thread role, ABI/system facts, module build IDs/ranges, and module-relative instruction/frame addresses. They exclude raw registers other than values transformed into approved normalized addresses, stack bytes, pointers, paths, annotations, and arbitrary strings.

Raw storage is credential-encrypted app-private `noBackupFilesDir`, at most 8 artifacts and 16 MiB total. Enhanced-session TTL is 24 hours. Minimal/Standard transient artifacts have two extraction attempts and a ten-minute deadline, then are deleted under the precedence rules. A raw artifact is never Standard-package eligible.

## Rationale

The pin is current, immutable, Apache-2.0 licensed, and supports the required Android architecture. The fixed stream inventory preserves useful unwinding while treating all opaque memory as sensitive. The quotas are stricter than the package bounds and do not expand collection.

