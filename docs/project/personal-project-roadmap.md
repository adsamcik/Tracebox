# Tracebox Personal-Project Completion Roadmap

## Outcome

Complete every production implementation, host-test seam, fixture, and code
review before using Android runtime validation. At the candidate-freeze point,
the only unfinished Tracebox work is running the scripted validation suite on
the one required API 36 `x86_64`, 4 KiB emulator.

Tracker integration is downstream work. It begins only after Tracebox reaches
`PERSONAL_RELEASE_READY`.

## Inventory split

The persistent ledger currently contains 24 `IN_PROGRESS` work packages:

- five Phase 0 packages (`F0.3`-`F0.7`) whose remaining work is runtime
  validation and measurement;
- seventeen packages with unfinished production or host-tool implementation
  (`C1.6`, `R2.5`, `R2.7`, `R2.8`, `X3.1`-`X3.10`, and
  `T5.2`-`T5.4`);
- `T5.5`, which is the one-emulator resource baseline; and
- `T5.6`, which is the final personal-release decision.

The consolidated lab requirement (`PR-019`) is also unimplemented. It is
required infrastructure, not a substitute for production code.

## Host implementation sequence

No Android device or emulator execution is required to complete H1-H5.

| Stage | Work packages | Implementation to finish | Required host proof |
|---|---|---|---|
| H1: identity and storage ownership | `C1.6`, `R2.5`, `R2.7` | Finish the build/symbol identity contract; give one production owner responsibility for the UID-wide quota; account for every ordinary, raw, summary, journal, index, staging, temporary, and compaction byte/file; generate Direct-Boot C0 types; connect CE/DE pending/active policy transactions. | JVM/property tests for concurrent reservation and eviction; full-quota progress; metadata/file-count limits; CE/DE crash-boundary replay; schema drift and identity golden tests. |
| H2: handler and global policy transport | `X3.1`, `X3.2` | Connect the non-exported handler service to the bounded production transport; implement peer/version checks, death/restart state, census/leases, registration fencing, and global tightening/deletion barriers across CE and DE owners. | Fake-transport and Robolectric/JVM tests for bind races, malformed/versioned requests, timeouts, handler death, PID reuse, coordinator restart, registration during every barrier boundary, and honest partial failure. |
| H3: capture bridges and deletion closure | `R2.8`, `X3.3`-`X3.10` | Invoke the storage lifecycle from Crashpad capture start; call Rust `SummaryId::derive`; wire emergency startup ingestion; implement native policy/coexistence handoff; install the JVM handler; consume the Rust record ring; finish bounded watchdog installation seams; add the real `ApplicationExitInfo` adapter and raw-import journal; extend global deletion to raw artifacts, summaries, tombstones, snapshots, handler stores, and CE/DE state. | C/C++/Rust/JNI/JVM tests using fake clocks, lifecycle, Android-exit rows, IPC, filesystem, and crash hooks; kill-point tests at every journal/copy/append/acknowledgement/retire/delete boundary; exact-once and policy-denial tests; no-message, recursion, panic, timeout, and fallback tests. |
| H4: build tooling, symbols, and offline proof | `T5.2`-`T5.4` | Enforce release merged-manifest and dependency-lock checks; produce R8/ELF/Rust catalogs; connect exact-match CLI adapters; add DEX reference and native import scanners; complete the blocked-egress runner and control probe without executing it yet. | Gradle TestKit/consumer builds; fixture mapping and ELF catalogs; exact match/mismatch CLI tests; dependency graph and manifest negatives; malicious catalog/parser corpus; static no-network scans for every published module combination. |
| H5: consolidated lab and candidate automation | `PR-019` | Replace the missing eleven-app topology with one configurable `tracebox-lab`, no-internet/host-network/minified variants, stable scenario IDs, host malicious corpora, and one command that runs the required emulator scenarios and writes provenance-bound results. Production fault controls exist only in lab artifacts. | Variant/build-graph tests; scenario-registry completeness test; manifest and dependency assertions; host controller/state-machine tests; release artifact scan proving fault controls and approval bypasses are absent. |

H3 closes `R2.8` only after all Phase 3-owned stores participate in deletion.
H4 closes `C1.6` together with `T5.2` so build identity is exercised through
the real plugin and symbol catalogs rather than an isolated model.

## Host test bar

Before candidate freeze:

- all JVM, Robolectric, Gradle TestKit, CTest/native, Cargo, schema golden,
  property, fault-injection, parser, consumer, lint, and static-conformance
  suites pass;
- every Android framework input used by deterministic logic has a bounded host
  adapter or fake;
- production Android adapters contain no TODO, stub, test-only branch, or
  deferred connection;
- debug and minified release-like lab variants compile and package;
- `tbdiag` deterministic bytes and exact approval/package identity remain
  regression-tested;
- release artifacts contain no networking surface, uploader, fault injection,
  approval bypass, or unbounded free-form recording API; and
- the ledger and personal-release checklist point to the resulting host
  evidence.

Host simulation does not claim Android lifecycle behavior. Its purpose is to
make the emulator run a validation of completed code, not a place where missing
integration is discovered by design.

## Code-review sequence

All code reviews occur against immutable SHAs before the emulator validation
window:

1. storage, UID quota, CE/DE policy, and complete deletion;
2. handler transport, Crashpad/emergency/JVM/Rust capture, ANR, and exit import;
3. build identity, symbols, CLI, no-network tooling, and consolidated fixtures;
4. the complete baseline-to-candidate-HEAD implementation diff.

Each review fixes blocker, critical, and major findings, reruns the affected
host gates, and reviews the new SHA. Approval of review 4 freezes the emulator
candidate. If runtime validation exposes a defect, first add a host reproducer,
fix it, rerun the affected host suites, and reopen every review whose scope
changed before producing a new candidate.

## Emulator-only validation remaining after freeze

The frozen candidate has one required validation window:

1. install/readiness, handler cold/running/death/restart, and multiprocess
   policy barriers;
2. managed, C++, Rust, emergency, recursive, OOM, and stack-overflow fault
   scenarios;
3. live ANR candidate, responsive-main-looper, timeout/cancellation, lifecycle
   suppression, and restart/`ApplicationExitInfo` reconciliation;
4. Direct Boot, storage pressure, selective deletion, disable/delete/restart,
   and no-accessible-data results;
5. Standard package disclosure, exact approval, save/share, R8 retrace, and ELF
   symbolication smoke;
6. blocked-egress/DNS observation with a working control probe for the
   no-internet and host-network variants; and
7. one representative memory, idle CPU/wakeup, readiness, target-pause,
   capture-latency, package-memory, and artifact-size baseline.

One provenance-recorded successful run is enough. Physical devices, additional
API/ABI/OEM cells, long percentile campaigns, battery studies, and
byte-identical full-APK builds across independent environments are advisory.

`PERSONAL_RELEASE_READY` is reached when this validation passes, its evidence
matches the frozen candidate, and the release-readiness evidence audit finds no
scope or claim mismatch.

## Tracker-Android after Tracebox release

Use one immutable `PERSONAL_RELEASE_READY` artifact in
`G:\Github\Tracker-Android`. Implement the dependency/API migration, generated
Tracker event mapping, explicit opt-in, JVM-handler ownership migration,
separate exit-history responsibilities, combined deletion status, package
workflow, notices, and rollback variant described in
`docs/integration/tracker-android.md`.

Finish Tracker host unit tests and its code review before runtime evaluation.
Then run the small Tracker smoke list on the same emulator. That downstream
smoke evaluates the library in the real personal app; it does not reopen the
Tracebox release unless it reveals a reproducible Tracebox defect.
