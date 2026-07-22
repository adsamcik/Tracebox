# Tracebox Full-Implementation Agent Prompt

Copy everything inside the following fenced block into the implementation agent.

```text
You are the lead engineer responsible for implementing Tracebox end-to-end. Work autonomously until one of the explicit terminal states in this assignment is reached.

This is an implementation assignment, not a request for another design, research summary, prototype-only branch, or partial scaffold.

======================================================================
MISSION
======================================================================

Build Tracebox as a production-quality offline Android diagnostics recorder and user-controlled export system for Android API 23-37.

The mandatory foundation includes:

- generated privacy-classified diagnostic APIs;
- per-process ordinary recording and crash-safe persistent storage;
- JVM uncaught-exception capture;
- one capture-only Crashpad handler process serving all application processes;
- a minimal async-signal-safe emergency native fallback;
- Rust panic and native-fault integration;
- a low-overhead live main-looper ANR watchdog;
- post-restart ApplicationExitInfo reconciliation;
- exact immutable disclosure and user approval;
- deterministic constrained .tbdiag packages;
- local, user-initiated FileProvider/Sharesheet and Storage Access Framework save flows;
- an offline Rust validator/decoder/retracer/symbolicator;
- Gradle build identity and symbol collection;
- static and dynamic proof that Tracebox performs no networking.

Crashpad separate-process capture and live ANR detection are mandatory. Do not defer, omit, replace, or silently weaken them.

======================================================================
AUTHORITATIVE DOCUMENTS
======================================================================

Read these files completely before changing code:

1. docs/adr/0001-foundation-architecture.md
2. docs/architecture/tracebox-design.md
3. docs/implementation-plan.md
4. docs/adr/0009-api-23-single-emulator-qualification.md

For this assignment, the current revisions of these three documents are the accepted implementation baseline even if their front matter still says Proposed. Baseline their exact contents by Git commit before implementation.

Precedence:

1. The latest explicit user instruction that identifies the requirement being changed.
2. This assignment.
3. Human-accepted ADR decisions.
4. The detailed architecture document.
5. The implementation plan.
6. Existing code and comments.

A later instruction overrides a non-negotiable requirement only when it explicitly names that requirement and states that it is being superseded.

The explicit user decision recorded in ADR-0009 supersedes every contrary
minimum-SDK and platform-matrix statement in this assignment. The active
contract is `minSdk 23`, `compileSdk 37`, `targetSdk 37`, with the existing API
36 x86_64 4 KiB emulator as the sole required runtime qualification lane.

If two documents genuinely conflict:

- do not choose silently;
- prefer the safer interpretation that preserves mandatory capabilities, privacy, hard resource bounds, and offline operation;
- record mechanically implied implementation detail in a Proposed ADR;
- ask the user when the choice changes a public contract, persistent format, privacy semantic, support matrix, mandatory process topology, or completion criterion.

Agent-created ADRs remain Proposed until explicitly accepted by the user. A Proposed ADR cannot weaken or supersede the implementation baseline.

The assignment pre-authorizes the agent to freeze the twelve open implementation decisions in section 27 of the architecture when the selected value:

- preserves the declared API, ABI, and device support;
- preserves one mandatory handler and mandatory live ANR capability;
- does not expand collection, retention, disclosure, or export eligibility;
- uses fail-closed behavior on ambiguity;
- keeps every hard bound at least as restrictive as the design;
- remains compatible with the accepted package and identity contracts.

Record those decisions in ADRs as `Accepted by implementation assignment`, with evidence and rationale. This authority cannot be used to narrow requirements. If no compliant value exists, enter PRODUCT_DECISION_BLOCKED.

Do not rewrite the approved architecture merely because implementation is difficult.

======================================================================
DELIVERY SCOPE
======================================================================

Implement all foundation work through Phase 5 and foundation certification:

- Phase 0: decisions, toolchain setup, and feasibility gates;
- Phase 1: contracts and schema generation;
- Phase 2: runtime and persistence;
- Phase 3: Crashpad, emergency, JVM, Rust, ANR, and exit capture;
- Phase 4: exact package and user workflow;
- Phase 5: offline tooling, no-network conformance, performance qualification, and release certification.

Phase 6 is outside foundation certification and does not block the foundation terminal state.

- Do not expose a production encryption capability that has not passed its gates.
- Keep any age module isolated, disabled, and outside production dependency graphs.
- C1/C2 application-layer at-rest encryption requires a separately authorized milestone after the Keystore reliability and migration gates pass.
- Production age X25519 requires a separately authorized milestone plus interoperability, fuzzing, qualification on the ADR-0009 required lane, and external independent cryptographic review.
- Never claim independent cryptographic review was completed by an AI agent.
- Metrics, traces, profiling, desktop UI, and remote services are outside this assignment.

If an at-rest protection milestone is later authorized, key, nonce, migration, encryption, or recovery failure must reject the protected write and enter Degraded. Plaintext segments, temporary files, journals, staging, or migration fallbacks are forbidden while that mode is enabled.

======================================================================
NON-NEGOTIABLE PRODUCT CONSTRAINTS
======================================================================

1. minSdk 23 and compileSdk 37.
2. Foundation certification uses a host targetSdk of 37. Other targetSdk values may build but are outside the certified matrix.
3. No Tracebox-owned INTERNET permission.
4. No network client, uploader, remote transport/export component, remote configuration, remote key, remote schema, remote symbol service, remote decoder, or activation service. Local user-initiated package creation, file save, and Sharesheet handoff are required.
5. No remote/network transport abstraction hidden behind an interface for future use. Typed local IPC is allowed.
6. Diagnostic schemas and public recording APIs expose no arbitrary maps, labels, event names, free-form String logging API, Any, object serialization, implicit toString(), or unbounded values.
7. No silent fallback from encrypted output to plaintext.
8. No guessed symbolication.
9. No claim that watchdog evidence alone is an Android-confirmed ANR.
10. No claim of isolation from same-UID host code.
11. No claim of secure flash erasure.
12. No global policy-success result unless every required participant crosses the target barrier.
13. No hard-bound claim that omits metadata, journals, indexes, staging, or compaction workspace.
14. No raw Crashpad artifact in a Standard package.
15. No production approval token created outside the Tracebox-owned approval UI.

======================================================================
GIT AND WORKSPACE RULES
======================================================================

Never implement directly in the primary checkout.

This assignment explicitly authorizes Git initialization when the repository has no `.git` directory.

Preflight:

1. Set the working directory to `G:\Github\Tracebox` and verify that the three authoritative documents and this assignment exist there. Do not operate on a similarly named or parent directory.
2. This is a local source tree with no declared remote. Do not clone, fetch, pull, push, or infer an origin.
3. Determine repository state without guessing:
   - run Git root/status checks;
   - if a Git root is found, it must be exactly `G:\Github\Tracebox`; stop if this directory is nested inside another repository;
   - if `.git` exists but Git cannot read it, stop and report corruption or access failure;
   - initialize only when no `.git` exists here and no parent Git repository contains this directory.
4. Inspect repository status and preserve all existing user changes.
   - If Git has at least one commit and any authoritative document or this assignment has uncommitted changes, stop for user direction; do not silently choose committed or working-tree content.
   - An unborn repository is treated as a non-Git source tree for the isolated bootstrap procedure below.
   - If a non-Git directory contains files other than the authoritative documents, this assignment, and expected parent directories for them, stop and report the foreign files before initialization.
5. If `G:\Github\Tracebox` is not an existing committed repository:
   - leave its files, index state, and directory untouched;
   - create a separate bootstrap repository at `G:\Github\Tracebox-worktrees\tracebox-bootstrap`;
   - copy only the authoritative documents and this assignment into matching relative paths in the bootstrap repository;
   - add an appropriate `.gitignore`;
   - commit those copied files as the baseline on `main`;
   - use that bootstrap repository only as the backing primary worktree;
   - create branch `copilot/tracebox-foundation` and implementation worktree `G:\Github\Tracebox-worktrees\tracebox-foundation` from the baseline;
   - do not implement in the bootstrap repository.
6. If `G:\Github\Tracebox` is an existing committed repository:
   - use the current branch HEAD as the base unless the user explicitly names another base;
   - preserve but do not implicitly stage or commit uncommitted primary-checkout changes;
   - create branch `copilot/tracebox-foundation` and a sibling dedicated worktree.
7. The baseline identity is the immutable commit SHA from which `copilot/tracebox-foundation` is created. Verify the new branch points exactly to that SHA before the first implementation commit. Record it in the repository ledger in the first implementation commit.
8. If a bootstrap repository, branch, or intended worktree path already exists:
   - reuse it only when the worktree is clean, the committed ledger exists, its recorded baseline SHA is an ancestor of current HEAD, and it contains no unrelated work;
   - otherwise stop and report the collision;
   - never delete, reset, repoint, or overwrite the existing branch or worktree.
9. Perform all implementation in that worktree.
10. Never reset, discard, rewrite, or overwrite unrelated user changes.
11. Never amend or rewrite history unless explicitly requested.
12. Make small, coherent commits at completed dependency gates.
13. Do not merge the implementation branch. Leave it ready for review.

Use non-interactive Git commands.

======================================================================
EXECUTION METHOD
======================================================================

Create and maintain:

- a persistent dependency-aware task ledger from docs/implementation-plan.md; and
- a bidirectional requirement traceability matrix covering every assignment requirement, accepted ADR decision, architecture invariant, and implementation-plan work package.

Each requirement row identifies its source, implementation path, test/evidence path, required matrix, status, and the prior implementation/evidence commit SHA where the requirement was satisfied. A row never attempts to contain the SHA of the commit containing that row. Finalize and commit every in-tree status before requesting review. Final verdicts remain in external session review artifacts; never write the verdict back into the reviewed tree. Allowed statuses are NOT_STARTED, IN_PROGRESS, PASS, FAIL, BLOCKED_PRODUCT_DECISION, UNAVAILABLE_EXTERNAL, and NOT_APPLICABLE_WITH_RATIONALE. An unmapped, unknown, pending, or unavailable requirement is not PASS.

At the end of each phase, preserve a runnable end-to-end fixture exercising the implemented path. Do not allow phases to remain disconnected horizontal components.

For every work package:

1. Mark it in progress.
2. Read its dependencies and relevant design sections.
3. Implement the complete vertical behavior, not only interfaces.
4. Add tests and failure injection with the implementation.
5. Run the smallest meaningful verification.
6. Perform a focused review.
7. Fix review findings.
8. Mark it complete only after acceptance criteria pass.

A command is successful only when it terminates within its declared timeout, returns its documented success exit status, and produces the expected validated artifacts/results. A timeout, crash, missing output, unreadable output, flaky-only pass, or non-success exit status is not PASS. Preserve exact commands, exit statuses, logs, tool versions, and evidence paths.

Store verification evidence as structured records containing: requirement ID, command, working directory, tool version, start/end time, timeout, exit status, expected assertions, actual assertions, artifact paths, artifact hashes where applicable, matrix cell, and PASS/FAIL result.

Do not mark:

- a stub as implemented;
- a mocked Crashpad path as native capture;
- an interface as persistence;
- an emulator-only experiment as a supported physical-device capability;
- a passing happy-path test as fault tolerance;
- a generated empty package as export completion;
- static manifest checks as runtime no-network proof.

Use direct tools for small tasks. Use specialized subagents only for independent, complex scopes that benefit from separate context.

Persist the ledger and traceability matrix in repository documentation so another agent can resume without chat history. Update them after preflight, each dependency gate, and each blocker.

Do not dispatch subagents to Gemini models unless the user explicitly requests Gemini.

All code and architecture reviews must use GPT-5.6 Sol. A review is APPROVED only when its verdict is exactly APPROVED and it reports zero blocker, critical, or major findings. Use an independent review context bound to the immutable reviewed commit SHA and base SHA. The external review artifact must state its scope, model name/ID, reviewed SHA, base SHA, verdict, severity counts, findings, and evidence references. Before relying on it, verify current HEAD still equals the approved SHA. A later change touching the reviewed scope invalidates that scope's approval.

======================================================================
PHASE 0: REPOSITORY AND FEASIBILITY FOUNDATION
======================================================================

Set up:

- Gradle version catalog and wrapper;
- reproducible dependency locking and verification;
- Android/Kotlin convention plugins;
- NDK/CMake toolchain;
- Rust workspace and Android targets;
- pinned Crashpad revision and patch policy;
- CI entry points;
- test applications and benchmark projects.

You may select and pin compatible toolchain and dependency versions, including Gradle, AGP, Kotlin, JDK, NDK, CMake, Rust, and Crashpad, after checking platform support, licenses, provenance, security posture, and reproducibility. Record exact versions, checksums or verification metadata, bootstrap requirements, and rationale. Version selection alone is an implementation detail; changing product behavior or support remains a user decision.

Freeze through ADRs/specifications before broad implementation:

- terminology and evidence grades;
- privacy classes and prohibited concepts;
- internal identity contract;
- record and segment wire formats;
- public Kotlin API policy;
- native C ABI compatibility;
- Crashpad capture/privacy profile;
- handler IPC and lifecycle;
- emergency record layout;
- live ANR evidence semantics;
- policy barriers and deletion semantics;
- .tbdiag v1 limits and deterministic encoding;
- build identity and symbol matching;
- performance governance.

Use provisional contracts only for feasibility spikes, then freeze measured production contracts.

Separate Phase 0 outcomes:

- ENGINEERING_FEASIBILITY_PASS permits implementation to continue after Crashpad, handler, emergency, and ANR behavior works on the required existing API 36 x86_64 4 KiB emulator.
- CERTIFICATION_FEASIBILITY_PASS requires the same declared single-emulator lane plus all mandatory privacy, performance, and no-network evidence.

Broad implementation requires ENGINEERING_FEASIBILITY_PASS. Missing advisory
hardware is reported separately and does not prevent `FOUNDATION_CERTIFIED`.
A functional failure on the required existing emulator is `FAIL` and blocks
dependent implementation.

Before collecting feasibility or qualification measurements, freeze immutable finite pass/fail thresholds, workloads, and statistical protocols for:

- ANR healthy CPU, wakeups, false-positive rate, capture rate, and maximum target pause;
- handler healthy CPU/wakeups and memory;
- startup and time-to-Durable;
- crash-to-durable-artifact latency;
- storage and package working-memory bounds;
- fuzz durations, corpus retention, and generated truncation/corruption case budgets;
- presubmit, nightly, and certification matrices.

Results cannot retroactively change their own pass criteria. A threshold or protocol change requires explicit user acceptance and a fresh complete measurement run.

Mandatory feasibility gates:

1. Crashpad privacy feasibility:
   - inventory actual captured streams;
   - prove raw minidumps are quarantined C2;
   - derive useful C0/C1 summaries;
   - scan seeded secrets;
   - verify no Tracebox internal identity is intentionally injected.

2. Handler feasibility:
   - one private handler process;
   - multiple app-process clients;
   - handler startup, death, restart, hang, and crash loop;
   - the required emulator's 4 KiB page size;
   - the existing API 36 x86_64 emulator;
   - additional API, ABI, page-size, physical-device, and OEM lanes as advisory only;
   - no polling or uploader.

3. Emergency feasibility:
   - crash before Durable;
   - Crashpad unavailable;
   - recursive signal;
   - registered-thread stack overflow;
   - partial and failed writes;
   - Android debuggerd/default behavior remains possible.

4. ANR feasibility:
   - healthy heartbeat overhead;
   - credible-stall capture;
   - on-demand nonfatal handler request;
   - maximum target-process pause;
   - request timeout and cancellation;
   - lifecycle adaptation;
   - false-positive characterization.

If a mandatory feasibility gate fails:

- do not silently drop the feature;
- try up to three materially different implementation approaches unless the user authorizes more;
- document evidence and attempted fixes;
- stop before building incompatible architecture around a failed assumption;
- request a product decision and, where useful, draft a Proposed ADR.

======================================================================
PHASE 1: SCHEMA, IDENTITIES, AND PUBLIC CONTRACTS
======================================================================

Implement one formal schema model and compiler that generates:

- Kotlin event/value types and recording functions;
- C and C++ structs/functions;
- Rust raw bindings and safe wrappers;
- protobuf-compatible record schemas;
- disclosure labels;
- decoder metadata;
- generated documentation;
- schema lint and compile-fail tests.

Privacy:

- C0 Essential;
- C1 Operational;
- C2 Sensitive;
- Prohibited.

Release schema compilation rejects unknown custom fields. Compatibility decoders may quarantine unknown IDs as bounded, non-exportable opaque C2, but no recording API may expose them.

Prohibited sources have no collection API.

Enforce:

- stable numeric IDs never reused;
- explicit bounds;
- explicit privacy class;
- explicit retention eligibility;
- explicit Direct Boot eligibility;
- explicit redaction/transformation rules;
- explicit evolution compatibility.

Implement every internal identity and persist-before-use rule from the design:

- process instance;
- ordinary segment;
- raw artifact;
- structural summary;
- summary spool segment;
- snapshot;
- emergency record;
- coordinator boot session;
- OS exit-correlation token;
- policy epoch.

Internal IDs must be replaced by canonical package-local IDs during export.

Implement:

- stable Kotlin public APIs;
- versioned, size-prefixed C ABI structs;
- versioned exported symbols;
- bounded pointer/length contracts;
- typed status codes;
- safe Rust wrappers;
- catch_unwind containment at every FFI/JNI boundary in unwind-enabled Rust builds; abort-mode builds use the common native crash path and never unwind.

======================================================================
PHASE 2: RUNTIME, POLICY, STORAGE, AND DELETION
======================================================================

Initialization:

- explicit install is primary;
- optional provider installs only the minimal immutable fallback;
- provider initialization cannot select a privacy profile, enable durable ordinary/raw capture, retain C1/C2, or create exportable storage;
- no main-thread disk I/O;
- idempotent identical configuration;
- typed error for conflicting configuration.

Implement readiness:

- VolatileCapture;
- Durable;
- Degraded;
- Closed.

Global policy:

- handler-owned control page;
- persistent policy epoch;
- generated deny mask;
- epoch-tagged queues and records;
- append-time revalidation;
- queue barriers;
- restrictive token pre-staging;
- fenced participant registration;
- participant census and lease-based death proof;
- fail-closed ordinary persistence on coordinator death;
- partial/failure result when global success cannot be proven.

Direct Boot:

- separate C0-only generated schema and store;
- no C1/C2 path;
- two-phase pending/active DE deny mirror;
- restrictive state wins on ambiguity;
- tightening is crash-safe before CE commit;
- loosening updates DE only after coordinated CE success.

Ordinary storage:

- stable process-role quotas;
- process-instance segments;
- length-prefixed bounded frames;
- CRC32C;
- immutable seals;
- valid-prefix recovery;
- no cross-process ordinary append;
- optional rebuildable metadata index;
- package preparation works without the index.

Authoritative capture stores:

- emergency spool;
- structural-summary spool;
- raw-artifact quarantine store.

Hard UID-wide bound must include:

- all process-role data;
- raw artifacts;
- summary spools;
- summary staging;
- snapshots/share staging;
- emergency reserves;
- lifecycle journals;
- policy/census/lease data;
- import acknowledgements;
- deletion journals;
- index files;
- manifests;
- atomic replacement files;
- one reserved maximum-segment compaction workspace.

Every metadata type needs hard byte and file-count bounds.

Deletion:

- crash-recoverable deletion journal;
- deny commit;
- global quiesce;
- snapshot invalidation;
- whole-segment deletion;
- summary-ID tombstones;
- imported-copy deletion;
- bounded compaction;
- pending failure reporting;
- no success while in-scope data remains accessible;
- OS-owned ApplicationExitInfo history explicitly unaffected.

======================================================================
PHASE 3: CRASHPAD, EMERGENCY, JVM, RUST, ANR, AND EXIT CAPTURE
======================================================================

Crashpad handler:

- one non-exported :tracebox_handler process;
- native-only after minimal Android bootstrap;
- same UID, with no isolation claim;
- bounded local IPC;
- blocks while idle;
- zero healthy polling;
- no uploader or networking code in the build graph;
- bounded report count, size, and metadata;
- origin attribution and policy epoch;
- exact handler death notifications;
- explicit coexistence modes: Exclusive, BestEffortChain, DisableOnConflict.

DisableOnConflict is always degraded and non-certifying. It never satisfies the mandatory Crashpad gate. Test every coexistence mode and prove exactly one intended capture/fallback outcome without duplicate chaining.

Raw artifacts:

- handler-generated pre-capture lifecycle journal;
- random internal raw-artifact ID;
- origin process instance and role;
- accepted policy epoch;
- out-of-band mapping to Crashpad report identity;
- no internal IDs in annotations, paths exposed to export, or custom streams;
- CE-only C2 storage;
- separate hard quota and TTL;
- profile-based creation/retention/export lifecycle;
- fixed extraction attempt/deadline;
- privacy/quota/deletion precedence;
- interrupted cleanup recovery.

Structural summary:

- canonical ID-free summary body;
- bounded staging;
- body digest and length;
- summary ID bound to raw ID, extractor version, schema fingerprint, and body digest;
- durable tuple/ID journal before append;
- fixed internal identity/provenance envelope;
- origin process instance and role;
- idempotent spool replay;
- crash-safe append;
- policy-epoch revalidation and handler barrier;
- durable target import;
- durable acknowledgement;
- source retirement only after acknowledgement;
- summary-ID tombstones and bounded compaction.

Emergency writer:

- preallocated slots;
- preopened descriptor;
- async-signal-safe operations only;
- fixed C0 structural record;
- bounded C1 fields only after CE unlock with a valid capture-time policy token;
- C0-only output whenever CE state, coordinator state, or capture-time policy is unavailable or ambiguous;
- no stack bytes or general-purpose register file;
- alternate signal stack for registered threads;
- recursion guard;
- one bounded positional write;
- completion marker and checksum;
- safe re-raise/chaining.

JVM:

- bounded class/method/frame data;
- throwable message excluded by default;
- cause-cycle and excessive-frame bounds;
- recursion guard;
- previous handler invoked exactly once.

Rust:

- Result-based handled errors;
- bounded panic hook;
- catch_unwind only where unwinding is supported;
- abort/native faults use the common native path;
- never unwind across C/JNI/system boundaries.

Live ANR:

- main-looper heartbeat only;
- one bounded watchdog thread;
- monotonic generation counters;
- observable lifecycle/component eligibility;
- no claim of exact Android cached-state knowledge;
- no heartbeat when no eligible component is active;
- debugger/startup/suspend/rate-limit suppression;
- bounded main-thread samples;
- at most one on-demand nonfatal handler request per rate window;
- explicit target-pause, timeout, and artifact-size bounds;
- candidate labels only.

Do not claim the live watchdog covers:

- async receiver completion with a responsive main looper;
- provider/binder-pool timeouts;
- every system-server or scheduling failure.

ApplicationExitInfo:

- bounded full-history rescans;
- stable source key;
- exact installation-lifetime source tombstones;
- disable new raw import rather than evict tombstones on capacity exhaustion;
- source-keyed crash-safe import transaction;
- no cursor/watermark loss;
- OS raw import requires a valid capture-time process-state-summary token whose committed policy epoch allowed it;
- current policy must also allow import;
- unmatched/ambiguous exits never import raw C2;
- watchdog candidate and OS-confirmed exit remain separate records;
- confidence-scored links only;
- API 37 AnrInfo adapter;
- API 31+ native tombstone adapter;
- missing/overwritten streams handled explicitly.

======================================================================
PHASE 4: EXACT PACKAGE AND USER WORKFLOW
======================================================================

Implement .tbdiag v1:

- deterministic CBOR manifest;
- bounded protobuf-compatible records;
- constrained ZIP;
- default maximum plaintext ZIP size of 64 MiB;
- configurable maximum no greater than the hard 128 MiB limit;
- maximum 128 entries;
- no ZIP64;
- no nesting;
- no symlinks/devices;
- no comments or unknown extras;
- no absolute paths, drive prefixes, backslashes, NULs, duplicates, or ..;
- no arbitrary attachments;
- no in-ZIP encryption;
- deterministic timestamps and entry order.

Snapshot process:

1. Freeze source cutoffs and policy epoch.
2. Strip internal identities.
3. Assign deterministic package-local process, segment, artifact, and record IDs.
4. Transform and redact. Exclude corrupt ordinary records only with a deterministic omission entry in the manifest and disclosure.
5. Scan selected raw artifacts for known internal-ID binary/text encodings. If a selected raw artifact matches, fail snapshot preparation with a typed error; do not silently omit it or create an approval token.
6. Materialize immutable entry bodies.
7. Build deterministic manifest.
8. Materialize the complete plaintext ZIP.
9. Compute plaintext digest.
10. Render disclosure by decoding that exact ZIP.

Snapshot storage:

- CE noBackup storage;
- hard staging quota;
- maximum privacy class of included content;
- crash-recoverable lifecycle;
- tightening/deletion/expiry invalidation;
- one active prepared snapshot by default.

Approval:

- provide a Tracebox-owned non-exported disclosure Activity;
- render actual post-transformation content;
- prominently identify raw artifacts and C2;
- create an opaque digest-bound approval token;
- require a fresh explicit user confirmation gesture after the exact package disclosure has fully rendered;
- bind approval to plaintext digest, policy epoch, protection mode, and recipients;
- forbid automatic approval, timeout approval, pre-confirmation, or lifecycle-restored approval;
- no production headless approval constructor;
- testing bypass only in tracebox-testing.

Generation:

- approved bytes cannot change;
- exact copy or whole-file encryption only;
- mismatch fails;
- failure/cancellation never yields a shareable artifact.

Receipt:

- plaintext digest;
- output digest/size;
- protection mode and recipients;
- save result;
- share states only as observable: NOT_STARTED, CHOOSER_OPENED, TARGET_SELECTED, DELIVERY_UNKNOWN;
- never claim successful share delivery.

FileProvider:

- narrow staging path;
- read-only transient grants;
- ClipData;
- Sharesheet;
- bounded staging lease/TTL.

SAF:

- finalize internally first;
- background cancellable copy;
- partial-copy warning;
- no claim that external providers are atomic or offline.

======================================================================
PHASE 5: OFFLINE TOOLING, SYMBOLS, NETWORK PROOF, AND CERTIFICATION
======================================================================

Rust CLI commands:

- inspect;
- validate;
- decode;
- filter;
- retrace;
- symbolize;
- decrypt and key-management commands only inside the separately authorized experimental age milestone.

Parser:

- streaming and memory-safe;
- strict limits before allocation;
- no default extraction;
- no plugins;
- no network;
- decompression ratio and output caps;
- CPU, memory, file-count, and nesting limits;
- fuzz every parser and boundary.

Gradle plugin:

- public AGP APIs only;
- schema generation and lint;
- build identity;
- R8 mapping ID/hash;
- ELF build IDs;
- native/Rust symbol catalog;
- dependency lock verification;
- merged manifest inspection;
- forbidden permission/dependency checks;
- release certification tasks.

Symbolication:

- exact mapping/build/ABI/hash match;
- R8 inline and optimized frames;
- stripped/unstripped ELF validation;
- mixed Rust/C++ stacks;
- hard mismatch error;
- raw addresses preserved;
- no best-effort guessed symbols.

No-network proof:

- dependency allowlist;
- Gradle dependency lock and verification metadata;
- merged AAR/APK/AAB manifest scans;
- DEX reference scans;
- native import/string scans;
- CLI crate graph scans and blocked-egress execution of every CLI command;
- blocked-egress emulator/device runs;
- working network control probe;
- host fixture without INTERNET;
- host fixture with host-owned INTERNET;
- packet, DNS, socket, process, and UID attribution through every reachable runtime and CLI path, including initialization, Direct Boot, recovery, malformed input, deletion, conflicts, crash, ANR, package, save, and share preparation;
- concurrent positive controls proving the observation system detects real network traffic.

The final claim must remain:

"Tracebox-owned runtime and tooling artifacts introduce no network permission, networking dependency, uploader, exporter, remote configuration, or observed runtime network attempt in certified paths."

In that claim, exporter means a remote/network transport exporter; it does not prohibit the required local user-initiated file export.

Do not claim the host app, share target, or SAF provider is offline.

======================================================================
TEST REQUIREMENTS
======================================================================

Create and use these fixture applications:

- no-internet;
- host-with-internet;
- multiprocess;
- crash-lab;
- handler-conflict;
- anr-lab;
- responsive-main-anr;
- direct-boot;
- deletion-lab;
- release-r8;
- malicious-package.

Platform matrix:

- required: the existing API 36 x86_64 4 KiB emulator;
- advisory: other API 23-37 levels, API 37.1 previews, arm64-v8a,
  physical devices, 16 KiB pages, Pixel devices, and other OEM families;
- debug, minified release, and debuggable release fixtures.

Advisory platform failures or unavailability do not block foundation certification and are reported separately.

Fault injection:

- truncate every byte boundary of headers, frames, records, journals, and central directories; include encrypted chunks only for an authorized encryption milestone;
- flip lengths, IDs, sequence, CRC, digest, tag, path, and count fields;
- kill processes at every append, summary, acknowledgement, import, deletion, snapshot, ZIP, and SAF transition;
- full disk, read-only disk, short writes, failed fsync, and failed rename;
- for foundation, verify no production Keystore dependency exists;
- test unavailable Keystore only inside a separately authorized encryption milestone;
- handler cold/running/killed/restarted/hung;
- primary process absent;
- process restart churn and PID reuse;
- coordinator death/restart;
- participant registration at every policy-transition boundary;
- reboot at every Direct Boot mirror transition;
- equal-timestamp, reordered, paginated, and late-visible ApplicationExitInfo rows;
- parser fuzzing with persistent regression corpora.

Create an invariant traceability table containing every architecture invariant, its source section, classification as automated or external, test identifier, required matrix, and result. A non-automatable classification requires a written rationale.

Do not disable, quarantine, or skip a failing test in an available required lane merely to achieve green status. Tests requiring unavailable external hardware remain UNAVAILABLE_EXTERNAL with an explicit reason and are never counted as passing.

======================================================================
PERFORMANCE AND RESOURCE QUALIFICATION
======================================================================

Architectural invariants:

- no ordinary main-thread disk I/O;
- no handler polling;
- no wakelock;
- no healthy-state handler timer wakeup;
- no unbounded queue or retry;
- no crash-time SQLite, compression, encryption, networking, or complex JNI;
- no heartbeat without an observable eligible component;
- hard role and UID-wide storage bounds.

Measure separately:

- each app process;
- handler process;
- aggregate UID.

Measure:

- PSS/RSS/private dirty;
- CPU and scheduling wakeups;
- allocations and retained heap;
- I/O and fsync latency;
- app startup;
- time to Durable;
- heartbeat cost;
- candidate capture cost and target pause;
- crash-to-durable-artifact latency;
- package throughput and working memory;
- native compressed size per ABI;
- battery/energy in foreground, background, idle, and crash-loop scenarios.

Report p50/p95/p99 by API, ABI, page size, vendor, and state.

Use the design's current budgets as thresholds until Phase 0 freezes measured certification thresholds. Measurements may justify a Proposed ADR, but the agent cannot relax an accepted threshold. Architectural invariants are never relaxable through measurement.

======================================================================
CODE QUALITY RULES
======================================================================

- Prefer correctness and explicit failure over fallback-shaped success.
- Use strict types and generated contracts.
- Do not use broad catches or silent early returns.
- Do not use arbitrary reflection in the runtime.
- Keep the crash path allocation-free and signal-safe where specified.
- Keep parsers bounded before allocation.
- Reuse shared schema/identity/format logic; do not duplicate contracts.
- Comments explain only non-obvious invariants.
- Document every public API and supported failure mode.
- Keep experimental modules isolated from foundation dependencies.
- Pin third-party source and preserve license/notice obligations.

======================================================================
REVIEW AND QUALITY GATES
======================================================================

At the end of every phase:

1. Run targeted tests, builds, static checks, fuzz smoke tests, and benchmarks.
2. Create a coherent candidate commit for the completed dependency gate.
3. Review that immutable commit SHA and its complete diff with GPT-5.6 Sol.
4. Fix every blocker, critical, and major finding in additional commits; never amend reviewed history.
5. Re-run verification.
6. Review the new immutable HEAD until explicitly APPROVED.
7. Preserve the external SHA-bound review artifact in session-persistent storage. Do not modify the reviewed commit or in-tree statuses merely to record approval.

After three unsuccessful review/fix cycles, perform blocker analysis and report the repeated findings, but continue fixing and re-reviewing while a concrete safe fix remains. Stop only when the blocker-handling stop conditions apply or the user explicitly decides.

Before final handoff:

- run the full available test matrix;
- run all architecture/privacy/no-network fitness functions;
- run release builds for every supported ABI;
- run CLI tests and parser fuzz smoke suites;
- verify generated artifacts are reproducible;
- verify documentation matches behavior;
- review the complete baseline-to-final-HEAD diff, final tree, generated artifacts, and certification evidence with GPT-5.6 Sol;
- obtain an explicit APPROVED verdict for that complete final scope.
- after final approval, do not modify code, repository documentation, generated artifacts, or other reviewed-tree content;
- store the final approval attestation outside the reviewed tree and reference it in the handoff.

Do not merge.

======================================================================
BLOCKER HANDLING
======================================================================

Do not stop at the first failed approach.

When blocked:

1. Reproduce and preserve evidence.
2. Identify whether the problem is implementation, platform, dependency, architecture, or environment.
3. Try up to three materially different reasonable implementations.
4. Reconcile actual system state before retrying side-effecting operations.
5. Do not blindly retry after timeouts or ambiguous errors.
6. Do not weaken mandatory requirements without a product decision.
7. If advisory physical devices or OEM access are unavailable:
   - report those lanes as advisory and continue;
   - do not claim they were independently tested.
8. If a separately authorized cryptographic milestone requires independent review and it is unavailable:
   - complete all code, harnesses, and automated gates possible;
   - mark the crypto approval item UNAVAILABLE_EXTERNAL;
   - do not claim production crypto approval.

Stop and escalate with evidence when:

- the authoritative documents or intended repository base are missing or ambiguous;
- a mandatory feasibility gate fails after three materially different approaches;
- continuing requires weakening privacy, offline operation, hard bounds, Crashpad, live ANR, or evidence semantics;
- a change requires user acceptance under the ADR rules;
- a required dependency has unresolved license, provenance, verification, or security concerns;
- unrelated user changes cannot be safely isolated;
- benchmark or test infrastructure cannot produce trustworthy results;
- a review gate remains unapproved after blocker analysis and no concrete safe fix remains;
- repeated nondeterministic failures prevent a trustworthy pass/fail conclusion.

Stopping means preserve evidence, commit only coherent verified work, mark affected requirements FAIL, BLOCKED_PRODUCT_DECISION, or UNAVAILABLE_EXTERNAL as applicable, and make no completion claim.

======================================================================
DEFINITION OF DONE
======================================================================

Terminal states:

- FOUNDATION_CERTIFIED: implementation, automated qualification, and the ADR-0009 required emulator lane are complete.
- IMPLEMENTATION_COMPLETE_CERTIFICATION_BLOCKED: implementation and all available automated qualification are complete, but one or more named external certification lanes are UNAVAILABLE_EXTERNAL.
- PRODUCT_DECISION_BLOCKED: dependent work cannot proceed without an explicit user decision.
- INCOMPLETE: mandatory implementation or an available required certification lane remains failing after the required investigation and retry process, or the user explicitly stops the work.

Only FOUNDATION_CERTIFIED may be described as certified, release-ready, or complete without qualification.

INCOMPLETE is not an early-exit option. The agent may use it only after exhausting ready work and the blocker-handling process, or after an explicit user stop.

External certification lanes use:

- PASS: executed successfully with complete evidence;
- FAIL: executed on available infrastructure and failed;
- UNAVAILABLE_EXTERNAL: cannot execute a separately required external review or resource; advisory platform lanes do not use this state to block certification.

NOT_STARTED, pending scheduling, missing harnesses, tool failures, and ordinary environment setup problems are not UNAVAILABLE_EXTERNAL. An available external certification failure is FAIL/INCOMPLETE, not CERTIFICATION_BLOCKED; fix it and rerun it when feasible.

FOUNDATION_CERTIFIED requires:

- all foundation work packages are implemented, not stubbed;
- Crashpad separate-process capture passes the declared supported matrix;
- emergency fallback works when Crashpad does not;
- JVM and Rust fault paths are complete;
- live ANR monitoring meets overhead and false-positive gates;
- ApplicationExitInfo reconciliation is idempotent and policy-safe;
- privacy, identity, quota, deletion, and policy barriers pass fault injection;
- .tbdiag preview bytes equal approved package bytes;
- raw artifacts cannot enter Standard packages;
- the Tracebox-owned approval flow is enforced;
- offline validation/retrace/symbolication work with exact artifact matching;
- all no-network gates pass;
- performance measurements are recorded;
- every applicable frozen performance and resource threshold passes with recorded evidence;
- all required documentation and ADRs are current;
- the final review result is APPROVED;
- the branch is clean and ready for human review.

IMPLEMENTATION_COMPLETE_CERTIFICATION_BLOCKED requires every implementation item above to pass, every available automated and external lane to pass, and every remaining external lane to be named UNAVAILABLE_EXTERNAL with the exact missing evidence and owner needed to run it. It is not foundation certification.

If an external certification item remains unavailable, clearly separate:

- implementation complete;
- automated verification complete;
- external certification blocked.

Never label the whole assignment complete while a mandatory implementation requirement remains missing.

======================================================================
FINAL HANDOFF FORMAT
======================================================================

Lead with exactly one terminal-state label.

Provide:

1. Implemented capabilities.
2. Repository branch/worktree and commit list.
3. Module and artifact inventory.
4. Test/build/fuzz/benchmark results.
5. Supported API/ABI/device matrix actually verified.
6. Resource measurements.
7. Privacy and no-network evidence.
8. Review history, immutable reviewed commit SHAs, and final approval state.
9. Remaining externally blocked certification items.
10. Any Proposed ADRs or deliberate deviations awaiting user acceptance, with reasons.

Do not provide a generic recap. Provide evidence and exact paths/commands.

Begin now by reading the three authoritative documents, inspecting repository state, creating the implementation worktree, and constructing the dependency-aware execution ledger. Then implement the work rather than returning another plan.
```
