# Tracebox Personal-Project Completion Roadmap

## Decision

Tracebox is being completed for one personal Android app, Tracker. The release
bar is confidence that the important code works and remains private/offline,
not enterprise certification.

The implementation is complete when production paths have host tests and no
known stubs or missing wiring. Runtime validation may use one API 36 `x86_64`,
4 KiB emulator. A physical-device matrix, repeated certification campaigns,
and formal release-review rounds are not required.

## What earns its complexity

Keep these capabilities because they directly protect Tracker users or make a
failure actionable:

- JVM and native crash capture, the Rust emergency path, and bounded ANR/exit
  reconciliation;
- bounded storage, quota enforcement, crash-safe deletion, and deletion of
  handler/raw/tombstone data;
- payload-free structural diagnostics with no networking or uploader;
- deterministic disclosure, package, save/share, and receipt workflows;
- build identity and host symbolication; and
- the Android 16 private-storage path fix: a platform-owned alias above the
  package directory is allowed, while the package directory and descendants
  still reject symlinks.

Do not remove these already-tested foundations merely to reduce line count.
Their failure modes involve lost crash data, undeleted private data, or unsafe
filesystem behavior.

## What does not block this project

The following are optional follow-up work:

- more API, ABI, OEM, emulator, or physical-device cells;
- long percentile, battery, wakeup, or performance campaigns;
- repeated review/freeze/provenance rounds;
- live repetitions of Direct Boot, OOM, stack overflow, recursive faults,
  multiprocess barriers, storage pressure, and symbol-catalog cases already
  covered on the host;
- the original eleven separate lab applications; and
- Phase 6 encryption, metrics/traces, desktop UI, and advanced profiling.

The consolidated fixture and its additional scenarios may remain useful for
debugging, but their existence does not make every scenario a release gate.

## Remaining completion sequence

1. Run the complete host-readiness suite once, sequentially, and keep its JSON
   result.
2. Review the final production diff once and freeze an immutable local release
   candidate because the Android runtime source changed.
3. On one rootable API 36 `x86_64`, 4 KiB emulator, run the representative
   smoke set below.
4. Record `PERSONAL_RELEASE_READY` when the standalone Tracebox gates pass.
5. Pin Tracker to that candidate, run Tracker host tests, then perform the
   small downstream Tracker smoke below.
6. Record the integration result and any explicitly accepted limitation. Do not add more
   implementation unless a validation exposes a reproducible defect.

The validation AVD must expose rootable `adbd`, because UID-scoped packet
observation and private artifact checks require root shell access. Use an AOSP
or Google APIs userdebug image, not a Google Play image. The runner verifies
`adb shell id -u` is exactly `0` and waits for user 0 storage to unlock before
the first scenario.

## Representative Tracebox emulator smoke

One successful pass is enough:

1. install, durable readiness, and handler availability;
2. handler death and restart;
3. JVM crash capture and restart;
4. native crash capture and restart;
5. ANR candidate plus `ApplicationExitInfo` reconciliation;
6. disable/delete/restart with no accessible diagnostic data;
7. disclosure, approval, package creation, and save; and
8. blocked-egress observation with a working host-network control.

Startup time and PSS may be recorded as observations. They are not pass/fail
gates unless the app is visibly unusable.

## Tracker integration bar

Tracker integrates Tracebox unconditionally, with no product flavor or trial:

- Tracebox is the sole crash and diagnostic backend;
- legacy crash/log Room data is read/exported/deleted only during migration and
  receives no new writes;
- verbose, debug, info, and generic free-form compatibility logs are discarded;
- warnings, errors, assertions, and a small set of explicit domain codes are
  recorded once without payloads or count amplification;
- the handler process does not initialize Tracker/Hilt application work; and
- diagnostics disclosure, package, save/share, disable, and deletion remain
  available in Tracker settings.

The Tracker emulator smoke is intentionally small: launch to ready, confirm the
dedicated handler, record one structural diagnostic, disable and re-enable,
and exercise either package creation or deletion.

## Done

Tracebox is `PERSONAL_RELEASE_READY` when:

- the final host-readiness run passes;
- the representative Tracebox emulator smoke passes on the one selected AVD;
- no unfinished production implementation remains.

The downstream Tracker integration is complete when its host tests and small
smoke pass against that frozen candidate. Tracker does not establish Tracebox's
ready state, though a reproducible defect discovered there reopens the relevant
Tracebox gate. Anything beyond these lists is an improvement opportunity.
