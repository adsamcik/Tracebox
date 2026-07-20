# ADR-0001: Tracebox Foundation Architecture

## Status

Proposed

## Context

Tracebox must provide dependable offline diagnostics for JVM, C/C++, Rust, native crashes, and ANRs on Android API 30-37. Native crash and live ANR evidence are foundation requirements. The runtime must not introduce networking, and users must approve exact package content before export.

## Options considered

### Option A: In-process recorder with system exit history

- Lowest resource and implementation cost.
- Insufficient native crash fidelity.
- Cannot preserve live ANR evidence before process termination.

**Conviction:** 3/10

### Option B: Mandatory full telemetry/crash platform

- Rich data and broad capabilities.
- Excessive privacy, dependency, performance, and delivery scope.
- Conflicts with offline-only and allowlisted-data goals.

**Conviction:** 2/10

### Option C: Hybrid recorder with one capture-only native handler

- Per-process ordinary segments.
- Separate-process Crashpad for native capture.
- Minimal emergency fallback.
- In-process adaptive ANR watchdog with on-demand handler snapshot.
- Post-restart OS reconciliation.
- Deterministic user-approved offline packages.

**Conviction:** 9/10

## Trade-off matrix

| Dimension | Weight | A | B | C |
|---|---:|---:|---:|---:|
| Crash/ANR fidelity | 0.25 | 4 | 9 | 9 |
| Privacy control | 0.20 | 8 | 4 | 8 |
| Healthy-state overhead | 0.15 | 9 | 4 | 8 |
| Offline maintainability | 0.15 | 8 | 3 | 8 |
| Implementation risk | 0.15 | 9 | 3 | 5 |
| Future extensibility | 0.10 | 4 | 9 | 8 |
| **Weighted score** | | **6.8** | **5.5** | **7.9** |

## Decision

Adopt Option C.

Raw Crashpad minidumps are quarantined C2 artifacts with separate quota, retention, disclosure, and export rules. Structural summaries are the normal C0/C1 crash representation. The handler remains native-only, blocked on local IPC, and contains no uploader or networking.

The live ANR watchdog runs in eligible application processes. It posts a bounded main-looper heartbeat, suspends when no observable eligible component is active, and contacts the handler only for a credible rate-limited stall. Watchdog evidence remains a candidate and is never converted into an OS-confirmed record through ambiguous correlation.

## Reversibility

Mixed and mostly one-way after publication:

| Decision | Reversibility |
|---|---|
| Internal index implementation | Low-cost |
| Watchdog thresholds and measured budgets | Low-cost through configuration/ADR |
| Raw-artifact retention defaults | Moderate; privacy expectations constrain changes |
| Handler topology and Crashpad integration | High-cost |
| Public Kotlin/C/Rust ABI | High-cost |
| `.tbdiag` v1 format | One-way compatibility commitment |
| Privacy and consent claims | One-way product commitment |

This ADR remains conditional until Crashpad and live-ANR feasibility gates pass. The declared API 30-37 matrix and mandatory features are not silently narrowed on failure; any change requires a superseding product ADR.

## Dissenting views

- A persistent handler process adds memory that a minimal recorder avoids.
- Raw minidumps create a sensitive-data surface that generated schemas cannot fully inspect.
- OEM lifecycle and `ptrace` behavior may prevent one universal Android support claim.
- Live watchdog thresholds can create false positives and battery regressions.

These objections are addressed through early feasibility gates, C2 quarantine, adaptive monitoring, explicit evidence grades, and measured certification.

## Consequences

- Crashpad and ANR feasibility are tested before broad API implementation.
- One native handler process is part of the foundation resource budget.
- Raw minidumps are not ordinary records and cannot enter Standard packages.
- A minimal emergency path remains required.
- The runtime architecture contains no transport abstraction.
- Performance budgets and supported devices are evidence-based release claims.
- Raw-artifact creation, retention, and export use one explicit lifecycle across minidumps, ANR traces, tombstones, and nonfatal snapshots.
- Watchdog candidates and OS-confirmed exits remain distinct records with confidence-scored links.

## Related documents

- `docs/architecture/tracebox-design.md`
- `docs/implementation-plan.md`
