# Tracebox Implementation Plan

**Status:** Proposed  
**Companion design:** `docs/architecture/tracebox-design.md`

## 1. Delivery strategy

Implementation proceeds through feasibility gates and vertical slices. Crashpad and live ANR monitoring are foundation requirements, so their feasibility is tested before the rest of the platform is allowed to grow around unproven assumptions.

Size scale:

- **S:** localized, low uncertainty;
- **M:** multiple components or meaningful testing;
- **L:** cross-module or native/platform complexity;
- **XL:** major uncertainty, security exposure, or certification burden.

## 2. Critical path

```text
Product invariants and toolchains
  -> Crashpad privacy/handler feasibility
  -> Schema and identity contracts
  -> Runtime/storage foundation
  -> Crash/JVM/Rust/ANR pipelines
  -> Immutable package workflow
  -> Offline CLI and symbolication
  -> No-network and platform certification
```

## 3. Work packages

### Phase 0: Decisions and feasibility

| ID | Work | Depends on | Output / acceptance | Fallback | Size |
|---|---|---|---|---|---|
| F0.1 | Freeze terminology and invariants | — | Threat model, privacy classes, evidence grades, readiness states approved | Block coding | S |
| F0.2 | Pin toolchains and dependencies | F0.1 | Gradle, AGP, Kotlin, JDK, NDK, CMake, Rust, Crashpad revisions locked and verified | Reviewed lock update only | M |
| F0.3 | Crashpad privacy spike | F0.1-F0.2 | Raw stream inventory, structural-summary prototype, seeded-secret results | Revise raw-artifact policy | XL |
| F0.4 | Android handler spike | F0.2 | Multi-client handler works on the required existing API 36 x86_64 4 KiB emulator; restart/death behavior measured | Block foundation; matrix changes require a superseding ADR | XL |
| F0.5 | Emergency fallback spike | F0.2 | Fixed signal record survives startup/IPC failure and stack overflow on registered threads; unregistered behavior documented | Re-raise without capture | L |
| F0.6 | Live ANR spike | F0.3-F0.4 | Healthy overhead, candidate capture, nonfatal request, lifecycle adaptation, maximum target pause, timeout/cancellation, and raw-artifact size measured | Local-only candidate plus exit reconciliation | L |
| F0.7 | Baseline artifact and PSS measurement | F0.3-F0.6 | Per-ABI size, handler PSS/CPU/wakeups, app overhead recorded | Update provisional budgets by ADR | M |

**Phase gate:** Crashpad and ANR feasibility must pass before foundation API commitments are finalized.

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

**Phase gate:** managed, C++, Rust, Crashpad, fallback, and ANR fault matrices pass on the minimum supported matrix.

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

### Phase 5: Tooling and certification

| ID | Work | Depends on | Output / acceptance | Size |
|---|---|---|---|---|
| T5.1 | Bounded Rust parser/validator | P4.2-P4.3 | Fuzzed streaming parser and malicious corpus | XL |
| T5.2 | Gradle build/symbol plugin | C1.6 | R8, ELF, schema, provenance, manifest and dependency checks | XL |
| T5.3 | Offline retrace/symbolication | T5.1-T5.2 | Exact identity matching; mixed Rust/C++ stacks | L |
| T5.4 | No-network conformance | All foundation modules | Static, manifest, native and runtime proof | L |
| T5.5 | Performance/battery qualification | R2.x-X3.x-P4.x | Device baselines and invariant checks | L |
| T5.6 | Foundation release certification | All previous | Full declared platform matrix passes | XL |

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
- **QA lane:** device farm, crash lab, performance harness, and network observation fixtures.

The package lane begins only after record and identity contracts stabilize.

## 5. Required test applications

| Fixture | Purpose |
|---|---|
| `no-internet` | Prove merged manifest and runtime remain offline |
| `host-with-internet` | Prove Tracebox does not use host networking capability |
| `multiprocess` | Writers, handler clients, quotas, and reconciliation |
| `crash-lab` | Managed, C++, Rust, recursive, OOM, stack overflow |
| `handler-conflict` | Exclusive, chaining, and disable-on-conflict |
| `anr-lab` | Deadlock, busy loop, binder wait, starvation, lifecycle states |
| `responsive-main-anr` | OS ANRs that occur while the main looper remains responsive |
| `direct-boot` | C0-only pre-unlock behavior |
| `deletion-lab` | Process death and I/O failure at every deletion-journal transition |
| `release-r8` | Mapping IDs, inlining, obfuscation and symbol catalog |
| `malicious-package` | Parser and archive security corpus |

## 6. Performance plan

### Invariants

- zero healthy-state handler polling;
- zero ANR heartbeat when no observable eligible component is active;
- zero main-thread ordinary disk I/O;
- bounded crash and ANR work;
- no unbounded retries or queues;
- hard process-role and UID-wide quota enforcement.

### Measurements

Measure app process, handler process, and aggregate UID separately:

- PSS/RSS and private dirty memory;
- CPU time and scheduling wakeups;
- allocation count and retained heap;
- disk bytes and fsync latency;
- crash-to-artifact latency;
- ANR heartbeat and candidate overhead;
- package throughput and working memory;
- APK/AAB and per-ABI native size;
- energy during foreground, background, cached, and crash-loop scenarios.

Report p50/p95/p99 by API, ABI, page size, vendor, and process state.

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

## 8. Definition of foundation complete

Foundation is complete only when:

- Crashpad and emergency capture pass the supported device/fault matrix.
- Live ANR overhead and false-positive gates pass.
- All generated privacy invariants pass.
- Segments recover correctly under fault injection.
- Handler summary persistence/import survives handler or client death without deleting the only raw evidence.
- Summary replay repairs every append/journal/delete crash boundary without duplicate authoritative records.
- Recovery after extractor/schema upgrade reuses the pre-append frozen summary derivation tuple.
- Recovery after journal commit appends the exact staged canonical summary bytes and verifies their digest.
- Import acknowledgement cannot retire the source before the target summary is durably recoverable.
- Selective deletion removes an in-scope summary ID from both spool and imported stores before reporting completion.
- Full-quota compaction stays within the reserved workspace and permits forward progress.
- No Tracebox internal ID is intentionally encoded in package metadata, paths, generated records, annotations, or custom streams; raw artifacts matching known encodings are rejected.
- Policy loosening cannot import an older OS artifact whose capture-time token did not permit raw import.
- OS import recovery at every copy/journal/promote/ledger boundary produces at most one local artifact.
- Equal-timestamp, reordered, paginated, and late-visible OS records are processed without cursor loss.
- Process registration at every tightening/loosening boundary cannot escape the target epoch or token acknowledgement set.
- Disabled never reports success with any accessible library-owned data remaining.
- Selective deletion never reports success with accessible in-scope data remaining.
- Exact preview/package digest equality is proven.
- Offline retracing and symbolication reject mismatched artifacts.
- No-network conformance passes for every published module combination.
- Resource budgets are backed by measurements on the required existing emulator.
- Unsupported devices or configurations are explicitly documented.
