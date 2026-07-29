# Phase 0 Feasibility Status

## Terminal state

`INCOMPLETE`

The ADR-0009 required emulator targeted regression passes. Full qualification
remains `INCOMPLETE`.

ADR-0010 confirms that this is a personal-project release. The historical
percentile, battery, physical-device, OEM, and byte-identical full-APK results
below remain useful evidence but no longer block `PERSONAL_RELEASE_READY`.
Mandatory blockers are incomplete implementation, failed architectural
invariants or privacy/offline gates, and failure of the consolidated API 36
emulator suite.

## API 23 and matrix supersession

ADR-0009 records the user's 2026-07-22 decision to use `minSdk 23` with
`compileSdk` and `targetSdk` 37 and to require only the existing API 36
`x86_64`, 4 KiB emulator. Other API levels, ABIs, page sizes, physical devices,
and OEM families are advisory.

The API-23 Android modules compile and lint with NIO core-library desugaring.
Capture-only Crashpad builds for x86_64 and arm64 against Android API 23. The
fresh targeted run on the required emulator passes, including the capture-only
stream profile, seeded privacy scan, emergency restart reset, handler-unavailable
fallback, and prior-signal chaining.

## Current required matrix

| Cell | Status | Evidence |
|---|---|---|
| Existing API 36 x86_64 emulator, 4 KiB | IN_PROGRESS | Targeted PASS: `evidence/phase0/API36-x86_64-4096-review-fix-qualification.json`; full qualification pending |

## Historical matrix

The following results remain preserved but no longer block release under
ADR-0009:

## Explicit prerequisite supersession

ADR-0008 records the user's 2026-07-18 instruction:

> Explicitly supersede the ENGINEERING_FEASIBILITY_PASS prerequisite and implement Phases 1–5 despite these failures

This permits Phases 1–5 implementation to proceed at risk. It does not change
historical results or convert any result to `PASS`. ADR-0010 later supersedes
the enterprise percentile and reproducibility completion gates while retaining
the single required emulator lane and all correctness/privacy invariants.

| Cell | Status | Evidence |
|---|---|---|
| API 30 x86_64 emulator, 4 KiB | FAIL | `evidence/phase0/API30-x86_64-4096-qualification.json` |
| API 37 x86_64 emulator, 16 KiB | FAIL | `evidence/phase0/API37-x86_64-16384-environment-failure.json` |
| Connected representative arm64 physical devices | UNAVAILABLE_EXTERNAL | `evidence/phase0/arm64-external-lanes.json` |

The API 30 run stopped after the frozen thresholds below had already failed; it is not presented as the required 60-minute false-positive qualification.
Its preserved JSON now records `exit_status=2`, matching `result=FAIL` and the runner's failure exit. The correction is metadata-only with explicit provenance in the evidence; no measurement was rerun or changed.

| API 30 metric | Threshold | Observed | Result |
|---|---:|---:|---|
| Install to VolatileCapture p95 | 2 ms | 119.915 ms | FAIL |
| Cold Durable p95 | 500 ms | 1905 ms | FAIL |
| Handler CPU | < 0.05% | 0.1167% | FAIL |
| Handler PSS | <= 12 MiB | 7221 KiB | PASS |
| Heartbeats | <= 30/min | 32/min in short run | FAIL |
| Heartbeat main work p99 | < 50 us | 807.2 us | FAIL |
| Ineligible heartbeat delta | 0 | 0 | PASS |
| Nonfatal request deadline | <= 2 s | all completed | PASS |
| Target pause p95/max | <= 100 ms | 431.458/477.014 ms | FAIL |
| Hung-handler timeout/cancellation | <= 2 s | 2347–4005 ms | FAIL |
| Deterministic six-second stalls | 10/10 | 10/10 | PASS |
| Nonfatal watchdog rate limit | 1/10 min | 1 snapshot in 10 stalls | PASS |
| Fatal capture p95 | <= 2 s | 6533 ms | FAIL |
| Raw seeded secret / summary | present / absent | Historical 1 / hardcoded 0 | INVALID EVIDENCE |
| Frozen stream profile | exact allowlist | `ThreadNamesStream`, `CrashpadInfoStream`, and `MemoryListStream` observed | FAIL |
| Raw report bound | <= 8 | 8; ninth rejected | PASS |

## Targeted review-fix regression

This is not a complete Phase 0 rerun and does not change either required matrix
cell. `evidence/phase0/API30-x86_64-4096-review-fix-qualification.json` records:

| Assertion | API 30 result |
|---|---:|
| Registration connect/receive overall deadline | typed timeout, 1980.426 ms |
| Hung-handler cancellation overall deadline | cancelled, 1992.712 ms |
| Live process identity established before scanning | PASS |
| Raw seeded secret / serialized summary | 1 / 0 |
| Known identity encodings in raw / summary | 0 / 0 across 7 distinct byte encodings |
| Unexpected stream rejection | PASS; profile remains invalid |
| Handler unavailable fatal fallback | one valid record, sequence 1, flags 3, zero raw dump delta |
| Prior signal action and default death | invoked once; signal death observed |
| Prior-process emergency slot after restart without `pm clear` | previous valid record replaced by 256 zero bytes; validator rejected `InvalidMagic` |

No API 37 result is claimed by this targeted run.

## Host closeout checks

| Check | Result | Evidence |
|---|---|---|
| Configured host presubmit | PASS | `evidence/phase0/final-presubmit.json` |
| Final static no-network scan | FAIL | `evidence/phase0/final-no-network-static.json` |
| Full APK reproducibility comparison | FAIL | `evidence/phase0/final-reproducibility.json` |
| Artifact size inventory | PASS | `evidence/phase0/F0.7-artifact-sizes.json` |
| Targeted review-fix host/native/Rust regressions | PASS | `evidence/phase0/review-fix-host-validation.json` |
| Round-2 parser/archive/native structural regressions | PASS | `evidence/phase0/review-fix-round2-host-validation.json` |

The presubmit and targeted review-fix results record that their configured
checks completed successfully. They do not override the separate no-network,
stream-profile, required-lane, or complete-protocol failures. The historical
full-APK byte-reproducibility failure is advisory under ADR-0010; deterministic
schema and `.tbdiag` outputs remain mandatory.

## Implemented reversible foundation

- verified Crashpad source acquisition and ordered patch policy;
- capture-only Android handler build with uploader/network implementation excluded;
- one non-exported `:tracebox_handler` process serving main and worker clients over bounded private local IPC;
- handler death notification, explicit restart/reconnect, hang timeout/cancellation, and crash-loop start budget;
- CE app-private raw-artifact quarantine with eight-report/16 MiB hard admission bounds;
- actual nonfatal and fatal Crashpad capture paths;
- fixed 256-byte emergency writer with durable initialization-time slot invalidation, preopened descriptor, alternate stack, recursion guard, CRC32C, completion marker, one positional signal-path write, preserved prior actions, exactly-once chaining, and Android re-raise;
- live main-looper watchdog with lifecycle suspension, five-second candidate semantics, bounded stack, ten-minute snapshot rate limit, and two-second request timeout;
- bounded Rust minidump parser with exact stream allowlist enforcement, complete fixed-structure extents, and checked counted-list extents confined to each declared stream;
- non-vacuous seeded-secret and known internal-identity encoding scans over raw bytes and the actual serialized structural summary;
- minified, debuggable-release, and debug fixtures plus benchmark APKs.

## Blocker resolution needed

Provide on the existing required emulator:

1. a safe, bounded ANR snapshot implementation with no false confirmation,
   correct lifecycle suppression, and recorded target-pause/latency baselines;
2. startup/handler behavior with no polling or idle timer loop, bounded
   timeouts/retries, and recorded readiness/CPU/memory/capture baselines;
3. a no-network build graph that passes the final static scans and required
   emulator smoke paths with a working positive control;
4. two successful pinned-toolchain release builds with artifact hashes, while
   preserving deterministic generated schema and `.tbdiag` output; and
5. capture output that suppresses the forbidden streams without losing the
   mandatory useful streams.

Then rerun the complete qualification command and affected host gates. ADR-0009
changes the Android baseline and matrix; ADR-0010 changes the personal-project
completion and measurement bar. Mandatory Crashpad, emergency, ANR functional
behavior, privacy, offline operation, and hard bounds remain unchanged.
