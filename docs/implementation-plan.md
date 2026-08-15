# Tracebox Implementation Plan

**Status:** Active under ADR-0010 personal-project scope

**Companion design:** `docs/architecture/tracebox-design.md`

**Execution roadmap:** `docs/project/personal-project-roadmap.md`

## 1. Delivery strategy

Implementation proceeds through feasibility gates and vertical slices. Crashpad and live ANR monitoring remain foundation requirements. Host tests carry most deterministic logic, and one API 36 `x86_64` emulator is the only required Android runtime lane under ADR-0009 and ADR-0010.

Size scale:

- **S:** localized, low uncertainty;
- **M:** multiple components or meaningful testing;
- **L:** cross-module or native/platform complexity;
- **XL:** major uncertainty, security exposure, or validation burden.

## 2. Critical path

```text
Product invariants and toolchains
  -> Crashpad privacy/handler feasibility
  -> Schema and identity contracts
  -> Runtime/storage foundation
  -> Crash/JVM/Rust/ANR pipelines
  -> Immutable package workflow
  -> Offline CLI and symbolication
  -> No-network proof and personal release readiness
```

## 3. Work packages

### Phase 0: Decisions and feasibility

| ID | Work | Depends on | Output / acceptance | Fallback | Size |
|---|---|---|---|---|---|
| F0.1 | Freeze terminology and invariants | — | Threat model, privacy classes, evidence grades, readiness states approved | Block coding | S |
| F0.2 | Pin toolchains and dependencies | F0.1 | Gradle, AGP, Kotlin, JDK, NDK, CMake, Rust, Crashpad revisions locked and verified | Reviewed lock update only | M |
| F0.3 | Crashpad privacy spike | F0.1-F0.2 | Raw stream inventory, structural-summary prototype, seeded-secret results | Revise raw-artifact policy | XL |
| F0.4 | Android handler spike | F0.2 | Consolidated handler restart smoke proves cold attach, client survival, replacement, and a post-restart policy action on the required emulator | Host lifecycle tests cover the broader fault matrix | L |
| F0.5 | Emergency fallback spike | F0.2 | Host/native fault tests plus retained targeted API 36 fallback evidence | Re-raise without capture | M |
| F0.6 | Live ANR spike | F0.3-F0.4 | Host lifecycle/state-machine tests plus one real bounded `ANR.CANDIDATE` smoke | Local-only candidate plus exit reconciliation | M |
| F0.7 | Optional artifact/resource observation | F0.3-F0.6 | Retain historical artifact evidence; record one-emulator startup/PSS observations only when useful | Hard bounds are covered by T5.5; no dedicated measurement campaign under ADR-0010 | S |

**Historical phase gate:** Superseded by ADR-0008. Personal release uses the
mandatory host gates and representative emulator set defined by ADR-0010.

### Phase 1: Contracts and generation

| ID | Work | Depends on | Output / acceptance | Size |
|---|---|---|---|---|
| C1.1 | Formal privacy/event schema | F0.1, F0.3 | Stable IDs, bounds, C0/C1/C2, Prohibited, evolution rules | L |
| C1.2 | Schema model and compiler | C1.1 | Kotlin/C/C++/Rust/protobuf/docs generation and golden tests | XL |
| C1.3 | Kotlin public API | C1.2 | Generated-only recording surface; no generic maps/strings | M |
| C1.4 | Versioned C ABI | C1.2 | Size-prefixed structs and compatibility tests | M |
| C1.5 | Rust bindings/wrapper | C1.4 | Generated `-sys`, safe wrapper, boundary panic containment | M |
| C1.6 | Identity and build contract | F0.2, C1.2 | Internal identity table including boot session and OS correlation token; persist-before-use/export replacement rules; schema/build/R8/ELF catalog | M |

### Phase 2: Runtime and persistence

| ID | Work | Depends on | Output / acceptance | Size |
|---|---|---|---|---|
| R2.1 | Lifecycle/readiness runtime | C1.3 | Explicit install, provider fallback, four readiness states | M |
| R2.2 | Policy epoch protocol and local gate | F0.4, C1.1, R2.1 | Epoch-tagged records, enqueue/append revalidation, stale-writer rejection, queue-barrier protocol | L |
| R2.3 | Bounded queues, barrier, and health codes | R2.2 | Fixed capacity, priority drops, disallowed queued-record purge before acknowledgement, recursion-free counters | M |
| R2.4 | Segment framing/recovery | C1.2, C1.6 | Valid-prefix recovery and immutable sealing | L |
| R2.5 | Role and UID-wide quotas/retention | R2.4 | Hard total bound covers data, summary staging, journals, indexes, atomic temp files, file-count limits, and reserved one-segment compaction workspace | L |
| R2.6 | Lazy metadata summaries/index | R2.4-R2.5 | Package planning works without DB; index rebuild proven | M |
| R2.7 | Direct Boot C0 module | C1.1, R2.2, R2.4 | C1/C2 rejected; two-phase pending/active DE deny mirror survives crashes, reboot, unlock, and delayed loosening | M |
| R2.8 | Crash-recoverable deletion engine | R2.2-R2.7 | Deletion journal, quiesce/invalidate/delete states, mixed-segment policy, pending-failure recovery | L |

### Phase 3: Failure capture

| ID | Work | Depends on | Output / acceptance | Size |
|---|---|---|---|---|
| X3.1 | Handler service and IPC | F0.4, C1.4, R2.1 | One handler, bounded requests, death notification, no polling | XL |
| X3.2 | Production global policy coordinator | R2.2-R2.3, R2.8, X3.1 | Control page, census/leases, fail-closed death, transition-fenced registration, client/token barriers, global deletion orchestration, partial-failure reporting | L |
| X3.3 | Capture-only Crashpad integration | F0.3, C1.1, C1.6, R2.5, R2.8, X3.1-X3.2 | Handler-generated pre-capture journal binds artifact ID, origin process/role, and epoch; unverifiable Tracebox orphans deleted; uploader removed; lifecycle/quota/TTL/cleanup | XL |
| X3.4 | Structural summary derivation and spool | X3.3, C1.2, C1.6, R2.5, R2.8 | Canonical ID-free body staged/digested; envelope preserves origin process/role; upgrade-safe replay, epoch barrier, crash-safe import/retirement, bounded compaction | L |
| X3.5 | Emergency writer production path | F0.5, R2.5 | Async-signal-safe audit and full fault corpus | L |
| X3.6 | Crash dispatch/coexistence state machine | X3.3-X3.5 | Exactly one valid primary/fallback result; explicit conflict mode | L |
| X3.7 | JVM uncaught exception capture | C1.2, R2.5 | Bounded stacks, no message by default, exact handler chaining | M |
| X3.8 | Rust panic/fault integration | C1.5, X3.6 | Result, unwind, abort, JNI/C boundary tests | M |
| X3.9 | Live ANR watchdog | F0.6, X3.3, R2.1, R2.5 | Adaptive heartbeat, suppression, bounded candidate capture, on-demand raw snapshot lifecycle, target-pause and timeout gates | L |
| X3.10 | Exit reconciliation | C1.6, R2.4-R2.5, R2.8, X3.6-X3.9 | Capture-time policy token gates raw import; full rescans use installation-lifetime exact source tombstones and crash-safe import; tombstone exhaustion disables raw import rather than evicting | L |

**Personal-release gate:** the manifest's representative JVM, C++, ANR, exit,
handler, deletion, package, and network scenarios pass on the required emulator.
Rust, emergency, recursive-fault, and extended lifecycle cases remain available
as host tests or opt-in diagnostics.

### Phase 4: Package and user workflow

| ID | Work | Depends on | Output / acceptance | Size |
|---|---|---|---|---|
| P4.1 | Snapshot selection and transformation | X3.2, R2.4-R2.5, R2.8, X3.10 | Freeze cutoffs/policy, strip internal identities, reject raw artifacts matching known ID encodings, assign package-local IDs, transform/redact, enforce staging quota, materialize entry blobs | XL |
| P4.2 | Deterministic CBOR manifest | P4.1 | Manifest derived from exact entry blobs; cross-language canonical golden vectors | L |
| P4.3 | Constrained deterministic ZIP materialization | P4.1-P4.2 | Final plaintext `.tbdiag` bytes and digest; all v1 bounds and malicious corpus pass | L |
| P4.4 | Disclosure UI and approval | P4.3 | Tracebox-owned activity decodes exact package and creates opaque digest-bound approval | L |
| P4.5 | FileProvider and Sharesheet | P4.4 | Narrow read-only grant; chooser and observable handoff states recorded | M |
| P4.6 | SAF save | P4.4 | Internal finalization, cancellable background copy, partial warning | M |
| P4.7 | Final receipt integration | P4.5-P4.6 | Accurate generation, save, handoff, cancellation, and unknown-delivery outcomes | M |

### Phase 5: Tooling and personal release readiness

| ID | Work | Depends on | Output / acceptance | Size |
|---|---|---|---|---|
| T5.1 | Bounded Rust parser/validator | P4.2-P4.3 | Fuzzed streaming parser and malicious corpus | XL |
| T5.2 | Gradle build/symbol plugin | C1.6 | R8, ELF, schema, provenance, manifest and dependency checks | XL |
| T5.3 | Offline retrace/symbolication | T5.1-T5.2 | Exact identity matching; mixed Rust/C++ stacks | L |
| T5.4 | No-network conformance | All foundation modules | Static, manifest, DEX/native and one-emulator blocked-egress smoke proof | L |
| T5.5 | Personal-project resource qualification | R2.x-X3.x-P4.x | Hard architectural bounds plus optional emulator observations | S |
| T5.6 | Personal release readiness | All mandatory implementation | Host gates, representative one-emulator smoke, documentation, and final review pass | M |

### Phase 6: Gated extensions

| ID | Work | Depends on | Release gate | Size |
|---|---|---|---|---|
| E6.1 | C1/C2 segment AEAD | Stable storage | Keystore reliability, nonce, migration, fail-closed matrix | XL |
| E6.2 | Age X25519 | Stable package | CCTV, age/rage interop, fuzzing, Android qualification, review | XL |
| E6.3 | Metrics and traces | Stable schema/performance | Cardinality, privacy, priority, and overhead proofs | XL |

## 4. Parallel execution lanes

After Phase 0:

- **Schema lane:** C1.1-C1.6.
- **Native lane:** X3.1-X3.5 prototypes hardened against the frozen ABI.
- **Runtime lane:** R2.1-R2.7.
- **Tooling lane:** initial Rust parser and symbol-catalog scaffolding.
- **QA lane:** consolidated lab app, host fault corpora, one-emulator smoke runner, and network observation controls.

The package lane begins only after record and identity contracts stabilize.

## 5. Diagnostic scenario inventory

ADR-0010 preserves the broader scenarios as an opt-in diagnostic inventory and
permits a smaller fixture topology:

| Fixture or variant | Scenarios |
|---|---|
| `tracebox-lab` | Multiprocess writers, handler clients, managed/C++/Rust faults, recursive faults, OOM, stack overflow, handler conflict/death, ANR variants, Direct Boot, deletion, restart, and storage pressure |
| `tracebox-lab-no-internet` | Merged manifest and runtime without host networking capability |
| `tracebox-lab-host-network` | Prove Tracebox does not use host-owned networking capability |
| `tracebox-lab-release-r8` | Minification, mapping IDs, inlining, obfuscation, and symbol catalogs |
| Host malicious corpora | Parser, archive, symbol-catalog, and bounded-allocation security cases |

Only IDs listed in `personal_release_required` in
`tooling/fixtures/personal-release-scenarios.json` block personal release.
`-FullDiagnosticSuite` runs the retained inventory when deeper investigation is
useful. Separate Android applications are not required.

## 6. Performance plan

### Invariants

- zero healthy-state handler polling;
- zero ANR heartbeat when no observable eligible component is active;
- zero main-thread ordinary disk I/O;
- bounded crash and ANR work;
- no unbounded retries or queues;
- hard process-role and UID-wide quota enforcement.

### Personal-project observations

The architectural invariants above and their host tests are release gates.
Startup time or PSS may be recorded once when diagnosing a regression.
CPU/wakeup campaigns, latency percentiles, battery studies, package-memory
benchmarks, and API/ABI/vendor/device matrices are optional.

## 7. ADR sequence

1. Product boundary, threat model, and offline guarantee.
2. Privacy taxonomy and generated schema.
3. Crashpad raw-artifact privacy model.
4. Separate handler process and IPC.
5. Emergency fallback and handler coexistence.
6. Readiness and lifecycle semantics.
7. Global policy epoch and acknowledgement semantics.
8. Kotlin/C/Rust compatibility contracts.
9. Segment storage and quota model.
10. Live ANR evidence and exit reconciliation.
11. Snapshot, disclosure, approval, and receipt.
12. `.tbdiag` package format.
13. Build identity and symbolication.
14. Performance governance.
15. Application-layer at-rest encryption after qualification.
16. Age recipient encryption after review.
17. Personal-project release scope and Tracker integration.

## 8. Definition of personal release ready

Tracebox is `PERSONAL_RELEASE_READY` only when:

- all foundation implementation work packages are connected and contain no production stubs;
- host/native tests cover Crashpad and emergency fallback, while the
  representative JVM and C++ fatal scenarios pass on the required emulator;
- host state-machine tests cover ANR bounds/suppression, while
  `ANR.CANDIDATE` and exit reconciliation pass once on the required emulator;
- All generated privacy invariants pass.
- Segments recover correctly under fault injection.
- Handler summary persistence/import survives handler or client death without deleting the only raw evidence.
- Summary replay repairs every append/journal/delete crash boundary without duplicate authoritative records.
- Recovery after extractor/schema upgrade reuses the pre-append frozen summary derivation tuple.
- Recovery after journal commit appends the exact staged canonical summary bytes and verifies their digest.
- Import acknowledgement cannot retire the source before the target summary is durably recoverable.
- Full-quota compaction stays within the reserved workspace and permits forward progress.
- No Tracebox internal ID is intentionally encoded in package metadata, paths, generated records, annotations, or custom streams; raw artifacts matching known encodings are rejected.
- Policy loosening cannot import an older OS artifact whose capture-time token did not permit raw import.
- OS import recovery at every copy/journal/promote/ledger boundary produces at most one local artifact.
- Equal-timestamp, reordered, paginated, and late-visible OS records are processed without cursor loss.
- Process registration at every tightening/loosening boundary cannot escape the target epoch or token acknowledgement set.
- Disabled never reports success with any accessible library-owned data remaining.
- Exact preview/package digest equality is proven.
- Offline retracing and symbolication reject mismatched artifacts.
- No-network static conformance passes for every published module combination and the required emulator smoke paths show no Tracebox-owned attempt.
- Architectural resource invariants pass; emulator resource observations are optional.
- Unsupported devices or configurations are explicitly documented.
- The final release-diff review is approved.
- No physical-device, OEM-family, broad API-matrix, or independent-certification claim is made.

Completion of Phase 6 extensions and selective diagnostic-record deletion is
not required. Tracker is the downstream personal-project evaluation host
described by ADR-0010.
