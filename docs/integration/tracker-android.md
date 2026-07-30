# Tracker-Android Integration Requirements

## Purpose

`Tracker-Android` is Tracebox's reference personal-project host. In the current
development environment its checkout is:

```text
G:\Github\Tracker-Android
```

This document defines the downstream integration in Tracker's normal
application. Tracebox replaces Tracker's active crash and logging system; it is
not carried as a diagnostics flavor or paired with a rollback flavor. This is
not a new Tracebox validation matrix and it does not broaden Tracebox's data
collection.

## Observed host baseline

The current Tracker tree:

- has `minSdk 26`, `compileSdk 37`, and `targetSdk 37`;
- currently has `standard` and `traceboxTrial` diagnostics flavors that the
  final integration must collapse into the normal application;
- currently consumes `io.github.tracebox:tracebox:0.1.0-alpha.1` only in the
  trial flavor;
- currently installs the alpha disabled through `TraceboxTrialController`;
- has a user-facing Tracebox settings surface that can be migrated out of the
  trial-only controller;
- installs its own `Thread.UncaughtExceptionHandler`;
- stores legacy crash messages, stacks, device/battery/network context, and
  file/database exports; and
- reads `ApplicationExitInfo` for Tracker's tracking-recovery decisions.

Tracebox foundation targets API 23, so it is compatible with Tracker's API-26
baseline. The alpha and foundation Kotlin package/API migration must be handled
explicitly when the dependency is updated.

## Integration sequencing

Tracker host-side integration may be prepared once Tracebox reaches
`IMPLEMENTATION_COMPLETE`, so source migration and unit tests do not leave
avoidable implementation work for the emulator window. Pin the final immutable
candidate only after Tracebox reaches `PERSONAL_RELEASE_READY`:

- production capture and storage paths are connected;
- production artifacts contain no fault-injection or approval-bypass controls;
- host/static and required-emulator gates pass; and
- the candidate is published or locally resolved by an immutable version.

Tracker evaluation is a downstream practical smoke. It does not block
Tracebox's already-established `PERSONAL_RELEASE_READY` state unless it exposes
a reproducible Tracebox defect.

## Integration requirements

### Dependency and build

- Pin one immutable Tracebox candidate version.
- Keep Tracebox repositories content-filtered to `io.github.tracebox`.
- Preserve Tracker's API-26 normal build.
- Resolve Tracebox from Tracker's normal application variant and remove the
  diagnostics-flavor split.
- Include the packaged Tracebox and Crashpad notice resource
  `dev.tracebox.R.raw.tracebox_third_party_notices` in Tracker's third-party
  license output.

### User control

- Reuse Tracker's existing Tracebox settings surface.
- Use `STANDARD_DIAGNOSTICS` on first install so Tracebox actually replaces the
  retired crash and logging system instead of leaving Tracker without
  diagnostics.
- Persist every explicit profile choice, including `DISABLED`; a persisted user
  choice takes precedence over the first-install default.
- Keep package preparation, disclosure, save, and share explicitly
  user-initiated even while recording is enabled.
- Display Tracebox readiness, degraded state, deletion result, package
  preparation, disclosure, save, and share outcomes without claiming delivery.
- Connect Tracker's collected-data deletion flow to Tracebox deletion and show a
  pending/partial result when Tracebox cannot prove completion.

### Recording boundary

- Define Tracker events in Tracebox's generated schema.
- Replace every active Tracker logging writer with an explicit generated-event
  mapping or remove the call when no bounded structural event is appropriate.
- Record only bounded structural codes and generated fields.
- Never pass Tracker log strings, exception messages, location coordinates,
  paths, URLs, map/search text, activity history, database objects, or the
  legacy crash payload to Tracebox.
- Keep existing legacy crash/log stores read-only except for migration-safe
  retention and deletion; never append new records and never import their
  free-form values into Tracebox.
- Treat Tracker's host-owned networking as outside Tracebox's no-network claim.

### JVM crash-handler migration

Tracker's current handler performs database/file work and captures free-form
payloads. Do not feed that payload into Tracebox and do not leave it installed
beside Tracebox in the finished application.

Use this migration:

1. Add a startup coordinator with a unit-tested deterministic installation
   order.
2. Stop installing Tracker's legacy handler before installing Tracebox as the
   sole application crash owner.
3. Let Tracebox chain only the Android/runtime handler that existed before
   Tracker installed any application handler.
4. Keep legacy crash records readable/exportable/deletable until their existing
   retention path removes them; do not import them into Tracebox.
5. Delete or permanently disconnect legacy crash/log write paths after their
   read/export/delete migration surface is isolated.

The required steady state is one Tracebox JVM/native/Rust capture owner and one
bounded generated-event logging path, with no production double recording.

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

- Standard-profile first startup, explicit disable, and persisted-profile
  restoration;
- generated-event mapping and rejection of free-form Tracker values;
- deterministic crash-handler installation/chaining;
- no duplicate JVM capture;
- no active legacy crash or logging writes;
- bounded generated-event replacements for every retained logging call site;
- Tracker recovery reads without owning Tracebox import state;
- combined deletion result mapping;
- package/disclosure/save/share result mapping;
- dependency version and min-SDK compatibility; and
- read/export/delete behavior for pre-existing legacy records.

Use a fake `TraceboxHandle`; these tests must not depend on native libraries or
an Android runtime.

## Single-emulator acceptance

On the ADR-0010 required emulator, exercise:

- clean launch with Standard Diagnostics ready and the dedicated handler;
- one bounded structural Tracker diagnostic with no legacy writer activity;
- explicit disable and re-enable; and
- either package creation or whole-store deletion.

The standalone Tracebox emulator gate already covers managed/native faults,
ANR/exit, package, deletion, and blocked egress. Repeating those workflows in
Tracker adds little confidence and is optional. The small downstream smoke
above is sufficient; physical devices and additional OEM/API cells are
optional and must not be implied.
