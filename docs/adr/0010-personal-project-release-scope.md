# ADR-0010: Personal-Project Release Scope and Tracker Integration

## Status

Accepted by explicit user decision on 2026-07-29.

## Context

Tracebox is maintained for a personal Android project rather than by a large
organization with a device lab, independent certification program, or multiple
release teams. The intended reference host is the `Tracker-Android` repository;
the current development checkout is `G:\Github\Tracker-Android`.

The user explicitly accepted:

- no physical-device or OEM-family certification requirement;
- one Android emulator as sufficient runtime validation;
- host/unit validation for the important deterministic logic; and
- a release process proportionate to a personal project.

ADR-0009 already reduced the mandatory Android matrix to the existing API 36
`x86_64`, 4 KiB emulator. This ADR defines the corresponding completion,
testing, performance, fixture, review, and Tracker-integration scope.

## Decision

### Requirements that remain mandatory

Personal-project scope does not weaken Tracebox's correctness or privacy
boundary. A release still requires:

- all Phase 1-5 foundation implementation paths to be complete and connected;
- no production stubs, test-only crash controls, approval bypasses, or unwired
  capture/storage adapters;
- generated and bounded recording APIs with the C0/C1/C2/Prohibited policy;
- hard storage, queue, retry, parser, archive, and staging bounds;
- correct policy barriers, deletion reporting, crash recovery, and exact
  package approval;
- capture-only Crashpad, the emergency fallback, JVM/Rust capture, live ANR
  candidates, and `ApplicationExitInfo` reconciliation;
- no Tracebox-owned networking permission, dependency, uploader, or observed
  attempt in the required emulator smoke paths;
- exact build/symbol identity matching and deterministic `.tbdiag` bytes; and
- passing host unit, property, golden, fault-injection, native, Rust, build,
  lint, static conformance, and consumer tests.

### Runtime validation

The sole required Android endpoint remains:

- API 36;
- `x86_64`;
- 4 KiB page size.

Host builds and unit tests cover debug code. The one emulator smoke installs
only the minified qualification fixture; a second debug device pass is not
required.

One successful run of the representative functional, privacy, and no-network
smoke set in `tooling/fixtures/personal-release-scenarios.json` is sufficient.
Physical devices, additional Android versions, arm64, 16 KiB pages, Pixel
devices, and other OEM families are optional observations and never block a
personal release.

### Test-fixture topology

The logical scenarios from the original eleven-app plan remain an available
diagnostic inventory, but only the manifest's `personal_release_required` set
blocks this personal release. They are implemented as:

1. one configurable `tracebox-lab` app covering multiprocess capture, managed
   and native/Rust faults, handler conflict/death, ANR variants, Direct Boot,
   deletion, restart, and storage-pressure scenarios;
2. network-disabled and host-network-enabled build variants of that lab;
3. one minified `release-r8` variant; and
4. host-side malicious-package and parser corpora.

Stable scenario identity matters; Gradle module count and running every
diagnostic scenario for each release do not.

### Performance and reproducibility

Architectural invariants remain release blockers: no ordinary main-thread disk
I/O, no handler polling or idle timer loop, no heartbeat when ineligible, no
unbounded queue/retry, and hard storage bounds.

The numerical budgets in architecture section 22.2 are engineering targets,
not personal-release blockers. Startup time and PSS may be recorded once as
observations when useful. Idle CPU/wakeup, percentile, battery, package-memory,
and cross-device campaigns are not required.

Deterministic schema output, `.tbdiag` output, manifests, and symbol identity
remain mandatory. Byte-identical full APK rebuilds across independent
environments are advisory; a release must still build twice successfully on the
same pinned toolchain and record artifact hashes.

### Traceability and review

Traceability is maintained at work-package level plus a checklist of critical
privacy, storage, capture, package, symbol, and no-network invariants. A
line-by-line row for every sentence in the architecture and implementation
prompt is not required.

One final review of the release diff is required. Focused subsystem reviews are
useful while implementing risky storage, capture, or tooling changes, but they
are not separate release gates. Independent external certification is not a
personal-release requirement.

### Completion terminology

- `IMPLEMENTATION_COMPLETE`: every foundation implementation item and available
  host/static automated gate passes.
- `PERSONAL_RELEASE_READY`: `IMPLEMENTATION_COMPLETE`, the representative
  single-emulator smoke passes, documentation matches the observed scope, and
  the final review is approved.
- `PRODUCT_DECISION_BLOCKED`: remaining implementation requires a product
  decision.
- `INCOMPLETE`: a mandatory implementation or available required gate is
  failing.

`FOUNDATION_CERTIFIED` is retained only as a historical enterprise-style term.
Tracebox may be released for this personal project as
`PERSONAL_RELEASE_READY`; it must not claim physical-device, OEM, broad-matrix,
or independent certification.

### Tracker integration

Tracker is the reference downstream application, but its integration does not
replace Tracebox's own unit and lab tests. Source migration and host tests may
be prepared after `IMPLEMENTATION_COMPLETE`; the final immutable candidate pin
and downstream smoke follow `PERSONAL_RELEASE_READY`.

The Tracker integration must:

- remain compatible with Tracker's API-26 baseline;
- consume the immutable Tracebox candidate from Tracker's normal application
  variant, with no diagnostics flavor or rollback flavor;
- use generated structural event types rather than Tracker's free-form logs;
- start with `STANDARD_DIAGNOSTICS` on first install, persist explicit user
  profile choices (including `DISABLED`), and keep package export
  user-initiated;
- make Tracebox the sole active owner of JVM/native/Rust crash capture and stop
  installing or writing through Tracker's legacy crash handler;
- replace Tracker's active logging writers with a fixed, bounded
  Tracker-to-Tracebox event mapping; arbitrary log strings, exception messages,
  and throwable payloads are not forwarded;
- avoid duplicate ownership of `ApplicationExitInfo` reconciliation;
- connect Tracker's collected-data deletion and diagnostics settings to
  Tracebox deletion and package workflows; and
- retain existing legacy crash/log data only as a migration surface for
  bounded read, redacted export, and deletion; the legacy stores are never
  active writers and free-form legacy values are never imported into Tracebox.

Tracker evaluation on the required emulator is a downstream practical smoke
after the standalone Tracebox release gates pass. It is not a prerequisite for
Tracebox's own `PERSONAL_RELEASE_READY` state.

## Explicitly deferred

The following remain outside foundation and do not block a personal release:

- application-layer C1/C2 AEAD;
- age/X25519 recipient encryption and key-management commands;
- metrics and traces;
- desktop UI;
- API 37.1-specific adapters; and
- advanced profiling; and
- selective per-record or per-summary deletion. The personal release exposes
  only crash-recoverable whole-store deletion (plus expiry of package staging);
  selective deletion may be added later without weakening the mandatory
  whole-store privacy control.

## Consequences

- Existing historical failures and evidence remain preserved; they describe
  prior runs but do not reintroduce superseded matrix or percentile gates.
- Correctness, privacy, offline behavior, deterministic packages, and hard
  bounds are not relaxed.
- Documentation and automation must use personal-release terminology and must
  not silently restore physical-device or eleven-app requirements.
