# Tracker-Android Integration Requirements

## Purpose

`Tracker-Android` is Tracebox's reference personal-project host. In the current
development environment its checkout is:

```text
G:\Github\Tracker-Android
```

This document defines a small, reversible downstream integration. It is not a
new Tracebox validation matrix and it does not broaden Tracebox's data
collection.

## Observed host baseline

The current Tracker tree:

- has `minSdk 26`, `compileSdk 37`, and `targetSdk 37`;
- already has `standard` and `traceboxTrial` diagnostics flavors;
- consumes `io.github.tracebox:tracebox:0.1.0-alpha.1` in the trial flavor;
- installs the alpha disabled through `TraceboxTrialController`;
- has a user-facing Tracebox trial settings screen;
- installs its own `Thread.UncaughtExceptionHandler`;
- stores legacy crash messages, stacks, device/battery/network context, and
  file/database exports; and
- reads `ApplicationExitInfo` for Tracker's tracking-recovery decisions.

Tracebox foundation targets API 23, so it is compatible with Tracker's API-26
baseline. The alpha and foundation Kotlin package/API migration must be handled
explicitly when the dependency is updated.

## Entry gate

Begin Tracker code changes only after Tracebox reaches
`PERSONAL_RELEASE_READY`:

- production capture and storage paths are connected;
- production artifacts contain no fault-injection or approval-bypass controls;
- host/static and required-emulator gates pass; and
- the candidate is published or locally resolved by an immutable version.

Tracker evaluation is then a downstream practical smoke. It does not block
Tracebox's already-established `PERSONAL_RELEASE_READY` state unless it exposes
a reproducible Tracebox defect.

## Integration requirements

### Dependency and build

- Pin one immutable Tracebox candidate version.
- Keep Tracebox repositories content-filtered to `io.github.tracebox`.
- Preserve Tracker's API-26 standard build.
- Build both the rollback/no-Tracebox variant and the Tracebox-enabled variant.
- Include Tracebox and Crashpad notices in Tracker's third-party license output.

### User control

- Reuse Tracker's existing Tracebox settings surface.
- Install disabled unless the user has explicitly enabled the chosen profile.
- Persist only the user's requested profile, never inferred consent.
- Display Tracebox readiness, degraded state, deletion result, package
  preparation, disclosure, save, and share outcomes without claiming delivery.
- Connect Tracker's collected-data deletion flow to Tracebox deletion and show a
  pending/partial result when Tracebox cannot prove completion.

### Recording boundary

- Define Tracker events in Tracebox's generated schema.
- Record only bounded structural codes and generated fields.
- Never pass Tracker log strings, exception messages, location coordinates,
  paths, URLs, map/search text, activity history, database objects, or the
  legacy crash payload to Tracebox.
- Treat Tracker's host-owned networking as outside Tracebox's no-network claim.

### JVM crash-handler migration

Tracker's current handler performs database/file work and captures free-form
payloads. Do not feed that payload into Tracebox and do not leave two unrelated
handlers competing indefinitely.

Use this migration:

1. Add a startup coordinator with a unit-tested deterministic installation
   order.
2. During the trial, configure Tracebox's explicit coexistence mode and prove
   that Tracebox, Tracker's prior handler, and Android's prior handler are each
   invoked at most once.
3. Prefer Tracebox as the outer handler so its bounded capture is not delayed by
   Tracker's database/file path.
4. After the single-emulator migration smoke passes, stop installing Tracker's
   handler for new crashes in the Tracebox-enabled variant.
5. Keep legacy crash records readable/exportable/deletable until their existing
   retention path removes them; do not import them into Tracebox.
6. Retain the standard/rollback variant until practical evaluation is accepted.

The desired steady state is one Tracebox JVM capture owner, not permanent
double recording.

### Exit-history ownership

- Tracebox owns diagnostic `ApplicationExitInfo` correlation, tokens,
  tombstones, and raw-import decisions.
- Tracker may continue reading documented exit reasons for its tracking
  recovery behavior.
- Tracker must not overwrite Tracebox's process-state-summary token.
- The two consumers must not share cursors, tombstones, or claim that one has
  imported the other's evidence.

### Package and deletion workflow

- Launch Tracebox's non-exported disclosure activity for approval.
- Use only the approved immutable `.tbdiag` bytes for save/share.
- Keep Tracker's trip/data export formats separate from `.tbdiag`.
- A Tracker "delete collected data" operation reports complete only after both
  Tracker-owned deletion and Tracebox-owned deletion complete.
- A Tracebox failure must not block deletion of unrelated Tracker data, but the
  UI must accurately report the Tracebox portion as pending or failed.

## Host-side tests

Before emulator evaluation, Tracker should have unit tests for:

- profile persistence and disabled-by-default startup;
- generated-event mapping and rejection of free-form Tracker values;
- deterministic crash-handler installation/chaining;
- no duplicate JVM capture;
- Tracker recovery reads without owning Tracebox import state;
- combined deletion result mapping;
- package/disclosure/save/share result mapping;
- dependency version and min-SDK compatibility; and
- rollback variant behavior.

Use a fake `TraceboxHandle`; these tests must not depend on native libraries or
an Android runtime.

## Single-emulator acceptance

On the ADR-0010 required emulator, exercise:

- clean install disabled;
- explicit enable, process restart, and profile restoration;
- one managed crash and legacy-handler migration behavior;
- one native or Rust fault;
- one ANR candidate and restart reconciliation;
- package disclosure and local save;
- disable/delete/restart with no accessible Tracebox-owned data; and
- rollback/no-Tracebox variant startup.

This is sufficient downstream validation for the personal project. Physical
devices and additional OEM/API cells are optional and must not be implied.
