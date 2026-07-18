# Tracebox Persistent Implementation Ledger

- Immutable baseline SHA: `dc87c6f9e2a6576cc554f7cb181ce80a02bf0802`
- Implementation branch: `copilot/tracebox-foundation`
- Worktree: `G:\Github\Tracebox-worktrees\tracebox-foundation`
- Scope currently authorized: Phase 0 closeout; Phase 1–5 implementation may proceed under the explicit user supersession in ADR-0008
- Allowed states: `NOT_STARTED`, `IN_PROGRESS`, `PASS`, `FAIL`, `BLOCKED_PRODUCT_DECISION`, `UNAVAILABLE_EXTERNAL`, `NOT_APPLICABLE_WITH_RATIONALE`
- Commit rule: `satisfied-by-prior-commit` is filled only by a later commit; a row never records its containing commit.

## Dependency-aware work packages

| ID | Work | Dependencies | Acceptance/output | State | Satisfied by prior commit | Notes/evidence |
|---|---|---|---|---|---|---|
| F0.1 | Freeze terminology and invariants | — | Threat model, privacy classes, evidence grades, readiness states approved | PASS | 76c57c4482ead7e45c900a170014761b692fd8fd | Frozen by ADR-0002 through ADR-0007 and `specs/`. |
| F0.2 | Pin toolchains and dependencies | F0.1 | Gradle, AGP, Kotlin, JDK, NDK, CMake, Rust, Crashpad revisions locked and verified | PASS | 4e7959b70097426b253dda3671eef4a8b16ae1d9 | Crashpad reuse rehashes the complete expected post-patch source tree against a tracked lock value. Round-2 acquisition additionally authenticates every archive's locked byte size/SHA-256 before extraction and rejects absolute, drive-prefixed, traversal, link, device, and FIFO entries before destination writes. See `evidence/phase0/review-fix-round2-host-validation.json`. |
| F0.3 | Crashpad privacy spike | F0.1;F0.2 | Raw stream inventory, structural-summary prototype, seeded-secret results | FAIL |  | The reviewed API 30 evidence was invalid: it accepted forbidden `ThreadNamesStream`, `CrashpadInfoStream`, and `MemoryListStream`, hardcoded the summary seed count, and had no live identity. The corrected targeted run establishes an identity and scans the serialized summary, but truthfully rejects the observed stream profile. See `evidence/phase0/API30-x86_64-4096-review-fix-qualification.json`; API 37 remains unusable. |
| F0.4 | Android handler spike | F0.2 | Multi-client handler on API 30/37, page compatibility, restart/death evidence | FAIL |  | Targeted API 30 registration receive now returns the typed deadline outcome in 1980.426 ms, but the frozen fatal-latency/CPU matrix was not rerun and API 37 infrastructure still fails. |
| F0.5 | Emergency fallback spike | F0.2 | Fixed signal record survives pre-Durable and Crashpad-unavailable faults | FAIL |  | Targeted API 30 proof covers a killed handler, exactly one valid fallback record, zero raw-dump delta, prior-action chaining exactly once, observable signal death, and restart without `pm clear` durably invalidating the prior-process slot before handler installation. The complete required matrix was not rerun and API 37 remains blocked. |
| F0.6 | Live ANR spike | F0.3;F0.4 | Measured watchdog, candidate capture, nonfatal request, lifecycle, timeout/cancellation | FAIL |  | Targeted API 30 hung-handler cancellation now returns in 1992.712 ms, but the frozen target-pause matrix still fails historically, the complete protocol was not rerun, and API 37 is blocked. |
| F0.7 | Baseline artifact and PSS measurement | F0.3;F0.4;F0.5;F0.6 | Per-ABI size and handler/app resource evidence | FAIL |  | Size/PSS pass; readiness, handler CPU, heartbeat, timeout, fatal latency, final static no-network, full APK reproducibility, and required API 37 evidence fail. |
| C1.1 | Formal privacy/event schema | F0.1;F0.3 | Stable bounded privacy-classified schema | PASS |  | `schema/events.json`; compiler rejects prohibited, unknown, reused, unbounded, and non-C0 Direct Boot fields. Evidence: `evidence/phase1/schema-compiler.json`. |
| C1.2 | Schema model and compiler | C1.1 | Kotlin/C/C++/Rust/protobuf/docs generation and golden tests | PASS |  | One strict model generates Kotlin/C-compatible/Rust/protobuf/decoder/labels/docs; golden and compile-fail tests pass. Evidence: `evidence/phase1/schema-compiler.json`. |
| C1.3 | Kotlin public API | C1.2 | Generated-only recording API | PASS |  | `android/tracebox-api` exposes bounded generated values and opaque approval input only. Evidence: `evidence/phase1/kotlin-api-and-identity.json`. |
| C1.4 | Versioned C ABI | C1.2 | Size-prefixed ABI and compatibility tests | PASS |  | `native/include/tracebox/abi.h` uses v1 headers and typed statuses; compatibility tests pass. Evidence: `evidence/phase1/native-abi.json`. |
| C1.5 | Rust bindings/wrapper | C1.4 | Generated sys crate and safe wrapper | PASS |  | `rust/tracebox-sys` and `rust/tracebox` compile; unwind boundary test returns `DROPPED`. Evidence: `evidence/phase1/rust-bindings-and-identities.json`. |
| C1.6 | Identity and build contract | F0.2;C1.2 | Internal identities and build/symbol catalog | PASS |  | `rust/tracebox-identity` enforces persist-before-use; Gradle scaffold captures schema/build identity. Evidence: `evidence/phase1/kotlin-api-and-identity.json`; `evidence/phase1/rust-bindings-and-identities.json`. |
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

Ordinarily, a package is ready only when every dependency is `PASS`, and the failed F0.3–F0.7 gates would block broad Phase 1+ implementation. ADR-0008 records the user's explicit supersession of the `ENGINEERING_FEASIBILITY_PASS` prerequisite, so Phases 1–5 may proceed at implementation risk despite those failed Phase 0 dependencies. The supersession does not change any dependency or lane to `PASS`, relax a threshold, or permit certification. Available required lanes that fail remain `FAIL`; unavailable named external hardware remains `UNAVAILABLE_EXTERNAL`.

## Explicit gate disposition

| Gate | Measured state | Decision | Effect |
|---|---|---|---|
| `ENGINEERING_FEASIBILITY_PASS` | NOT REACHED | Explicitly superseded for implementation by ADR-0008 | Phases 1–5 may proceed at risk; Phase 0 results remain unchanged. |
| `CERTIFICATION_FEASIBILITY_PASS` | NOT REACHED | Not superseded | Certification remains blocked. |
| `FOUNDATION_CERTIFIED` | NOT REACHED | Not superseded | No certification claim is permitted. |

## Resume protocol

1. Verify branch, worktree, baseline ancestry, and cleanliness.
2. Select the first `IN_PROGRESS` package, otherwise the first dependency-ready `NOT_STARTED` package. Under ADR-0008, failed Phase 0 feasibility dependencies are explicit implementation risks rather than a stop condition for Phases 1–5; no other dependency is waived.
3. Read its traceability rows and frozen protocols before changing implementation.
4. Preserve structured evidence under `evidence/` and update rows only after verified commands finish successfully.
5. Commit coherent dependency gates without amending history.
