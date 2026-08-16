# Tracker-Android Integration

## Status

Tracker is the reference personal-project host for `0.1.0-alpha.5`. The normal
`dev/v10` application is hard-migrated: Tracebox is the only production logging
and crash-diagnostics backend, with no flavor gate or legacy compatibility
writer.

The validation bar is deliberately small: host tests plus one representative
emulator. Physical-device, OEM, battery, and broad API matrices are optional.

## Dependencies

Tracker declares the pieces it actually uses:

```kotlin
implementation("io.github.tracebox:tracebox:0.1.0-alpha.5")
implementation("io.github.tracebox:tracebox-native:0.1.0-alpha.5")
implementation("io.github.tracebox:tracebox-ui-compose:0.1.0-alpha.5")
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

Declare templates once and pass only privacy-classified arguments:

```kotlin
private object DiagnosticLogs {
    val TRACKING_STOP_REQUESTED = LogTemplate.of("Tracking stop requested: {}")
    val OSM_IMPORT_REJECTED = LogTemplate.of("OSM import rejected {} records")
    val OSM_IMPORT_FAILED = LogTemplate.of("OSM import failed")
    val PROCESS_TRACKING_CYCLE = LogTemplate.of("Process tracking cycle")
}

Tracebox.log.debug(DiagnosticLogs.TRACKING_STOP_REQUESTED, sensitive(reason))
Tracebox.log.warn(DiagnosticLogs.OSM_IMPORT_REJECTED, public(rejectedCount))
Tracebox.log.error(error, DiagnosticLogs.OSM_IMPORT_FAILED)
```

The `tracebox-api` AAR embeds a fatal lint rule for `LogTemplate.of`: its input
must be a string literal or `const val`, never runtime text. The Kotlin API does
not accept raw argument values.

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
Tracebox.log.performanceSuspend(DiagnosticLogs.PROCESS_TRACKING_CYCLE) {
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

Every visible label and outcome is resource-backed. An application can either override the
library's `tracebox_ui_*` resources by name or supply its own localized resource IDs through
`TraceboxDiagnosticsUiStrings`. `savedBytes` and `partialCopyBytes` must reference `plurals`
resources; the remaining properties reference `string` resources.

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

One approved package is bounded to 64 MiB, and only one created package remains
active per installed runtime. Hosts that retain a `DiagnosticPackage` outside
the provided screen must call `close()` as soon as the share, save, or upload
operation finishes. Creating a replacement retires the earlier capability;
policy changes, all-data deletion, runtime shutdown, and Compose screen disposal
also wipe owned package bytes and remove Tracebox-owned staging. A stream scope
therefore returns `null` after retirement and must not be retained by the host.

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

## Tracker integration baseline

Tracker `dev/v10` commit `63c0be268e0b1cbfb140c66cc079e56a39eabff6`
consumes the immutable `0.1.0-alpha.5` coordinates above with strict dependency
verification. Its host compile, hard-migration architecture tests, bootstrap and
handler-isolation tests, tracking resilience tests, complete repository quality
gates, and release validation pass against that package.

The post-release Tracker consumer smoke also passes on the representative API 36
`x86_64`/4 KiB emulator. It confirms cold application startup, a separately live
private Tracebox handler, the packaged native runtime, persisted policy across a
force-stop/restart, durable owned storage, the localized settings entry and
diagnostics screen, exact-byte review, upload-disabled Android sharing, and
staging cleanup on screen disposal. The structured result is
`evidence/personal-release/tracker-alpha5-integration.json`.

Real user workloads remain alpha evaluation. They are not unfinished
implementation and do not replace the bounded deterministic host and emulator
contracts.
