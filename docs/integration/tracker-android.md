# Tracker-Android Integration

## Status

Tracker is the reference personal-project host for `0.1.0-alpha.3`. The normal
`dev/v10` application is hard-migrated: Tracebox is the only production logging
and crash-diagnostics backend, with no flavor gate or legacy compatibility
writer.

The validation bar is deliberately small: host tests plus one representative
emulator. Physical-device, OEM, battery, and broad API matrices are optional.

## Dependencies

Tracker declares the pieces it actually uses:

```kotlin
implementation("io.github.tracebox:tracebox:0.1.0-alpha.3")
implementation("io.github.tracebox:tracebox-native:0.1.0-alpha.3")
implementation("io.github.tracebox:tracebox-ui-compose:0.1.0-alpha.3")
```

`tracebox` does not transitively depend on `tracebox-native`. Apps that need
only JVM logging/crash capture and storage therefore receive no Crashpad shared
libraries. Tracker explicitly opts into native capture and the reusable Compose
screen.

Tracebox uses desugared Java NIO on API 23+, so consuming Android modules must
enable core-library desugaring:

```kotlin
android.compileOptions.isCoreLibraryDesugaringEnabled = true
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
```

## Installation

Tracker installs Tracebox from `Application.attachBaseContext()` before Hilt
startup and excludes `:tracebox_handler` from Tracker application startup:

```kotlin
Tracebox.install(
    context,
    TraceboxConfiguration.Builder()
        .setInitialPolicy(TraceboxPolicy.standard())
        .setNativeCaptureEnabled(true)
        .setPersistRequestedProfile(true)
        .build(),
)
```

The standard first-install policy records `INFO` and above plus enabled crash,
ANR, OS-exit, native, Rust, and handled-exception sources. User changes are
persisted. A disabled user choice remains disabled after restart.

## Logging and privacy

Call sites use a conventional template plus parameters:

```kotlin
Tracebox.log.debug("Tracking stop requested: {}", reason)
Tracebox.log.warn("OSM import rejected {} records", rejectedCount)
Tracebox.log.error(error, "OSM import failed")
```

Formatting happens only after the runtime level gate. Parameters are classified
before Logcat mirroring or durable storage:

- numbers, booleans, characters, and enums default to `PUBLIC`;
- strings and unknown objects default to `PII` and become `[redacted]`;
- `public` preserves a value, while `sensitive`, `pii`, and `secret` replace it
  with distinct non-value markers;
- private host domain types can be registered once with only a privacy class;
  public domain types additionally provide a bounded renderer through
  `TraceboxConfiguration.Builder.privacy`.

Templates, argument count, rendered UTF-8 bytes, exception frames, and stored
records are bounded. Exception messages are not captured. `Tracebox.log.error`
with a throwable writes redacted context and one handled-exception record.

Performance timings use the same logger and an independent runtime switch:

```kotlin
Tracebox.log.performanceSuspend("Process tracking cycle") {
    processCycle()
}
```

The switch is checked before rendering parameters or starting a useful
measurement, and an optional minimum duration suppresses short samples.

## Runtime controls and UI

`TraceboxPolicy` provides one persisted control surface for:

- master enablement;
- minimum level from `VERBOSE` through `OFF`;
- redacted Logcat mirroring;
- performance timing and its minimum duration; and
- each JVM, handled, ANR, OS-exit, native, and Rust capture source.

Tracker embeds `TraceboxDiagnosticsScreen` from `tracebox-ui-compose`. Its
default presentation is simple-first: one reviewed send/share action and a
plain readiness summary. Technical status, runtime logging/performance policy,
capture-source switches, reset, and deletion stay under a collapsed advanced
section.

The host controls the entire surface in code:

```kotlin
val defaults = TraceboxPolicy.standard()
val ui = TraceboxDiagnosticsUiConfiguration(
    showHeading = false,
    primaryAction = TraceboxPrimaryAction.SHARE,
    packageActions = TraceboxPackageActions(
        upload = false,
        share = true,
        save = true,
        deleteAllData = true,
    ),
    advancedControls = TraceboxAdvancedControls(
        initiallyExpanded = false,
        logcatMirroring = false,
        captureKinds = setOf(CaptureKind.JVM_CRASH, CaptureKind.ANR),
    ),
    defaultPolicy = defaults,
)

TraceboxDiagnosticsScreen(
    handle = checkNotNull(Tracebox.current()),
    configuration = ui,
)
```

`defaultPolicy` is the explicit target for the optional reset control. The
same value should be passed to `TraceboxConfiguration.setInitialPolicy` for
fresh installs. Opening the UI never replaces a persisted user policy.

### Optional native upload

An application can provide its existing authenticated backend without adding a
network client, endpoint, permission, or worker to Tracebox:

```kotlin
val uploader = TraceboxDiagnosticUploader { request ->
    request.useInputStream { approvedZip ->
        appBackend.uploadDiagnostics(
            stream = approvedZip,
            sizeBytes = request.sizeBytes,
            sha256 = request.plaintextDigestSha256,
        )
    } ?: TraceboxUploadResult.Failed
}

TraceboxDiagnosticsScreen(
    handle = checkNotNull(Tracebox.current()),
    configuration = ui.copy(
        primaryAction = TraceboxPrimaryAction.UPLOAD,
        packageActions = ui.packageActions.copy(upload = true),
    ),
    uploader = uploader,
)
```

The uploader is called only after the user reviews and approves the exact
deterministic package. It receives scoped read access to those approved ZIP
bytes, not a database, filesystem path, or raw crash store. The standalone
`TraceboxDiagnosticsActivity` uses the same settings after
`TraceboxDiagnosticsUi.configure(ui, uploader)`.

Tracker currently chooses reviewed Android sharing because it has no configured
diagnostics endpoint. Adding one requires only a Tracker-owned uploader; the
screen and package pipeline do not change.

## Hard-migration rules

- No Tracker logger facade, fixed diagnostic catalog, legacy crash handler,
  logging database, or duplicate diagnostics screen remains.
- Production failures with a throwable call Tracebox's throwable overload so
  stack identity is preserved without exception messages.
- High-frequency verbose/debug calls are added only at useful state boundaries;
  runtime levels make them opt-in without recreating historical log volume.
- Tracker may independently read documented `ApplicationExitInfo` reasons for
  tracking recovery, but Tracebox owns diagnostic tokens, tombstones, and raw
  import state.
- Tracker trip/data exports remain separate from `.tbdiag` packages.

## Verified alpha integration

For `0.1.0-alpha.3` the host compile, hard-migration architecture tests,
Tracebox bootstrap tests, tracking resilience tests, affected OSM/activity/stats
module tests, and debug APK assembly pass. A single x86_64 emulator launch also
confirmed a live Tracker process, dedicated Tracebox handler, durable segment,
policy/identity stores, and native emergency slots.

The settings entry, casual screen, advanced disclosure, and exact-package
review handoff are exercised on the representative emulator. Real user
workloads remain alpha evaluation, not missing implementation.
