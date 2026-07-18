# Phase 0 Feasibility Status

## Terminal state

`INCOMPLETE`

`ENGINEERING_FEASIBILITY_PASS` is not reached.

The available API 30 x86_64 lane executes the real handler, emergency, and ANR paths, but fails immutable startup, handler CPU, heartbeat, target-pause, timeout, and fatal-latency thresholds. The target-pause gate remained failing after three materially different approaches, recorded in `evidence/phase0/anr-target-pause-approaches.json`.

The mandatory API 37 x86_64 emulator cell also cannot provide trustworthy test infrastructure. The API 37.0 revision-6 16 KiB images repeatedly crash SurfaceFlinger in region sampling or fail to boot across the materially different emulator, renderer, feature, and image approaches recorded in `evidence/phase0/API37-x86_64-16384-environment-failure.json`.

This is a local required-lane `FAIL`, not `UNAVAILABLE_EXTERNAL`.

## Explicit prerequisite supersession

ADR-0008 records the user's 2026-07-18 instruction:

> Explicitly supersede the ENGINEERING_FEASIBILITY_PASS prerequisite and implement Phases 1–5 despite these failures

This permits Phases 1–5 implementation to proceed at risk. It does not change this terminal state, convert any result to `PASS`, relax any frozen threshold or required lane, or authorize certification.

## Matrix

| Cell | Status | Evidence |
|---|---|---|
| API 30 x86_64 emulator, 4 KiB | FAIL | `evidence/phase0/API30-x86_64-4096-qualification.json` |
| API 37 x86_64 emulator, 16 KiB | FAIL | `evidence/phase0/API37-x86_64-16384-environment-failure.json` |
| Connected representative arm64 physical devices | UNAVAILABLE_EXTERNAL | `evidence/phase0/arm64-external-lanes.json` |

The API 30 run stopped after the frozen thresholds below had already failed; it is not presented as the required 60-minute false-positive qualification.

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
| Raw seeded secret / summary | present / absent | 1 / 0 | PASS |
| Raw report bound | <= 8 | 8; ninth rejected | PASS |

## Host closeout checks

| Check | Result | Evidence |
|---|---|---|
| Configured host presubmit | PASS | `evidence/phase0/final-presubmit.json` |
| Final static no-network scan | FAIL | `evidence/phase0/final-no-network-static.json` |
| Full APK reproducibility comparison | FAIL | `evidence/phase0/final-reproducibility.json` |
| Artifact size inventory | PASS | `evidence/phase0/F0.7-artifact-sizes.json` |

The presubmit result records that its configured checks completed successfully. It does not override the separate final no-network and reproducibility failures.

## Implemented reversible foundation

- verified Crashpad source acquisition and ordered patch policy;
- capture-only Android handler build with uploader/network implementation excluded;
- one non-exported `:tracebox_handler` process serving main and worker clients over bounded private local IPC;
- handler death notification, explicit restart/reconnect, hang timeout/cancellation, and crash-loop start budget;
- CE app-private raw-artifact quarantine with eight-report/16 MiB hard admission bounds;
- actual nonfatal and fatal Crashpad capture paths;
- fixed 256-byte emergency writer with preopened descriptor, alternate stack, recursion guard, CRC32C, completion marker, one positional write, and Android re-raise;
- live main-looper watchdog with lifecycle suspension, five-second candidate semantics, bounded stack, ten-minute snapshot rate limit, and two-second request timeout;
- bounded Rust minidump stream inventory and structural-summary parser;
- seeded-secret and internal-identity scanning harness;
- minified, debuggable-release, and debug fixtures plus benchmark APKs.

## Blocker resolution needed

Provide:

1. a new safe ANR snapshot implementation that keeps p95 and maximum target pause at or below 100 ms;
2. startup/handler changes that meet the frozen readiness, CPU, heartbeat, timeout, and fatal-latency thresholds without weakening emergency capture or the single-handler topology; and
3. a stable API 37 x86_64 16 KiB emulator image/emulator combination on which Android framework services remain available;
4. a no-network build graph that passes the final static scan; and
5. byte-identical full APK rebuilds for every artifact in the reproducibility claim.

Then rerun the complete frozen qualification command and affected host gates. Changing a threshold, required API/ABI/page-size lane, or mandatory Crashpad/ANR behavior requires separate explicit user acceptance and is not authorized by ADR-0008 or this status record.
