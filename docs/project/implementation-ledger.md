# Tracebox Persistent Implementation Ledger

- Immutable baseline SHA: `dc87c6f9e2a6576cc554f7cb181ce80a02bf0802`
- Implementation branch: `copilot/tracebox-foundation`
- Worktree: `G:\Github\Tracebox-worktrees\tracebox-foundation`
- Scope currently authorized: Phase 0 only
- Allowed states: `NOT_STARTED`, `IN_PROGRESS`, `PASS`, `FAIL`, `BLOCKED_PRODUCT_DECISION`, `UNAVAILABLE_EXTERNAL`, `NOT_APPLICABLE_WITH_RATIONALE`
- Commit rule: `satisfied-by-prior-commit` is filled only by a later commit; a row never records its containing commit.

## Dependency-aware work packages

| ID | Work | Dependencies | Acceptance/output | State | Satisfied by prior commit | Notes/evidence |
|---|---|---|---|---|---|---|
| F0.1 | Freeze terminology and invariants | — | Threat model, privacy classes, evidence grades, readiness states approved | PASS |  | Frozen by ADR-0002 through ADR-0007 and `specs/`. |
| F0.2 | Pin toolchains and dependencies | F0.1 | Gradle, AGP, Kotlin, JDK, NDK, CMake, Rust, Crashpad revisions locked and verified | IN_PROGRESS |  | Toolchain provenance and reproducible build setup in progress. |
| F0.3 | Crashpad privacy spike | F0.1;F0.2 | Raw stream inventory, structural-summary prototype, seeded-secret results | NOT_STARTED |  |  |
| F0.4 | Android handler spike | F0.2 | Multi-client handler on API 30/37, page compatibility, restart/death evidence | NOT_STARTED |  |  |
| F0.5 | Emergency fallback spike | F0.2 | Fixed signal record survives pre-Durable and Crashpad-unavailable faults | NOT_STARTED |  |  |
| F0.6 | Live ANR spike | F0.3;F0.4 | Measured watchdog, candidate capture, nonfatal request, lifecycle, timeout/cancellation | NOT_STARTED |  |  |
| F0.7 | Baseline artifact and PSS measurement | F0.3;F0.4;F0.5;F0.6 | Per-ABI size and handler/app resource evidence | NOT_STARTED |  |  |
| C1.1 | Formal privacy/event schema | F0.1;F0.3 | Stable bounded privacy-classified schema | NOT_STARTED |  |  |
| C1.2 | Schema model and compiler | C1.1 | Kotlin/C/C++/Rust/protobuf/docs generation and golden tests | NOT_STARTED |  |  |
| C1.3 | Kotlin public API | C1.2 | Generated-only recording API | NOT_STARTED |  |  |
| C1.4 | Versioned C ABI | C1.2 | Size-prefixed ABI and compatibility tests | NOT_STARTED |  |  |
| C1.5 | Rust bindings/wrapper | C1.4 | Generated sys crate and safe wrapper | NOT_STARTED |  |  |
| C1.6 | Identity and build contract | F0.2;C1.2 | Internal identities and build/symbol catalog | NOT_STARTED |  |  |
| R2.1 | Lifecycle/readiness runtime | C1.3 | Install and four readiness states | NOT_STARTED |  |  |
| R2.2 | Policy epoch protocol and local gate | F0.4;C1.1;R2.1 | Epoch revalidation and barrier protocol | NOT_STARTED |  |  |
| R2.3 | Bounded queues, barrier, health codes | R2.2 | Fixed-capacity policy-aware queues | NOT_STARTED |  |  |
| R2.4 | Segment framing/recovery | C1.2;C1.6 | Valid-prefix append-only segment recovery | NOT_STARTED |  |  |
| R2.5 | Role and UID-wide quotas/retention | R2.4 | Complete hard storage bounds | NOT_STARTED |  |  |
| R2.6 | Lazy metadata summaries/index | R2.4;R2.5 | Rebuildable index and index-free planning | NOT_STARTED |  |  |
| R2.7 | Direct Boot C0 module | C1.1;R2.2;R2.4 | C0-only DE store and two-phase deny mirror | NOT_STARTED |  |  |
| R2.8 | Crash-recoverable deletion engine | R2.2;R2.3;R2.4;R2.5;R2.6;R2.7 | Journaled deletion and bounded compaction | NOT_STARTED |  |  |
| X3.1 | Handler service and IPC | F0.4;C1.4;R2.1 | One handler and bounded local IPC | NOT_STARTED |  |  |
| X3.2 | Production global policy coordinator | R2.2;R2.3;R2.8;X3.1 | Control page, census, leases, global barriers | NOT_STARTED |  |  |
| X3.3 | Capture-only Crashpad integration | F0.3;C1.1;C1.6;R2.5;R2.8;X3.1;X3.2 | Private capture-only Crashpad lifecycle | NOT_STARTED |  |  |
| X3.4 | Structural summary derivation and spool | X3.3;C1.2;C1.6;R2.5;R2.8 | Canonical summary staging and replay | NOT_STARTED |  |  |
| X3.5 | Emergency writer production path | F0.5;R2.5 | Signal-safe emergency capture | NOT_STARTED |  |  |
| X3.6 | Crash dispatch/coexistence state machine | X3.3;X3.4;X3.5 | Exactly one primary/fallback result | NOT_STARTED |  |  |
| X3.7 | JVM uncaught exception capture | C1.2;R2.5 | Bounded JVM capture and exact chaining | NOT_STARTED |  |  |
| X3.8 | Rust panic/fault integration | C1.5;X3.6 | Contained panic and native fault paths | NOT_STARTED |  |  |
| X3.9 | Live ANR watchdog | F0.6;X3.3;R2.1;R2.5 | Adaptive bounded watchdog | NOT_STARTED |  |  |
| X3.10 | Exit reconciliation | C1.6;R2.4;R2.5;R2.8;X3.6;X3.7;X3.8;X3.9 | Policy-safe idempotent OS exit import | NOT_STARTED |  |  |
| P4.1 | Snapshot selection and transformation | X3.2;R2.4;R2.5;R2.8;X3.10 | Frozen transformed snapshot | NOT_STARTED |  |  |
| P4.2 | Deterministic CBOR manifest | P4.1 | Canonical manifest vectors | NOT_STARTED |  |  |
| P4.3 | Constrained deterministic ZIP materialization | P4.1;P4.2 | Bounded deterministic tbdiag bytes | NOT_STARTED |  |  |
| P4.4 | Disclosure UI and approval | P4.3 | Exact decode and opaque approval | NOT_STARTED |  |  |
| P4.5 | FileProvider and Sharesheet | P4.4 | Read-only local handoff | NOT_STARTED |  |  |
| P4.6 | SAF save | P4.4 | Cancellable local document copy | NOT_STARTED |  |  |
| P4.7 | Final receipt integration | P4.5;P4.6 | Accurate observable outcome receipt | NOT_STARTED |  |  |
| T5.1 | Bounded Rust parser/validator | P4.2;P4.3 | Fuzzed streaming parser | NOT_STARTED |  |  |
| T5.2 | Gradle build/symbol plugin | C1.6 | Build identity and conformance plugin | NOT_STARTED |  |  |
| T5.3 | Offline retrace/symbolication | T5.1;T5.2 | Exact-match offline symbols | NOT_STARTED |  |  |
| T5.4 | No-network conformance | ALL_FOUNDATION | Static, manifest, native, CLI and runtime proof | NOT_STARTED |  |  |
| T5.5 | Performance/battery qualification | R2.*;X3.*;P4.* | Measured resource qualification | NOT_STARTED |  |  |
| T5.6 | Foundation release certification | ALL_PREVIOUS | Complete declared matrix | NOT_STARTED |  |  |
| E6.1 | C1/C2 segment AEAD | STABLE_STORAGE;SEPARATE_AUTHORIZATION | Gated post-foundation encryption | NOT_APPLICABLE_WITH_RATIONALE |  | Outside foundation and requires separate authorization. |
| E6.2 | Age X25519 | STABLE_PACKAGE;SEPARATE_AUTHORIZATION | Gated recipient encryption | NOT_APPLICABLE_WITH_RATIONALE |  | Outside foundation and requires separate authorization. |
| E6.3 | Metrics and traces | STABLE_SCHEMA;STABLE_PERFORMANCE | Out of foundation scope | NOT_APPLICABLE_WITH_RATIONALE |  | Outside foundation and requires separate authorization. |

## Ready-work rule

A package is ready only when every dependency is `PASS`. Phase 0 mandatory gates block broad Phase 1+ implementation until `F0.3` through `F0.7` pass. Available required lanes that fail remain `FAIL`; unavailable named external hardware alone may be `UNAVAILABLE_EXTERNAL`.

## Resume protocol

1. Verify branch, worktree, baseline ancestry, and cleanliness.
2. Select the first `IN_PROGRESS` package, otherwise the first dependency-ready `NOT_STARTED` package.
3. Read its traceability rows and frozen protocols before changing implementation.
4. Preserve structured evidence under `evidence/` and update rows only after verified commands finish successfully.
5. Commit coherent dependency gates without amending history.
