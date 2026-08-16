# Tracebox

Tracebox is an offline-first Android crash and diagnostics library. It records bounded,
privacy-classified evidence on the device, supports explicit local save/share exports, and owns no
networking or upload path.

The current alpha implementation includes:

- generated typed logging contracts with explicit privacy classes;
- durable bounded segment storage and direct-boot recovery;
- JVM uncaught-exception, ANR, `ApplicationExitInfo`, native Crashpad, and Rust capture paths;
- deterministic `.tbdiag` package generation with disclosure, approval, save, and share flows;
- an optional Compose diagnostics UI; and
- host, emulator, fault-injection, package, privacy, and native qualification tooling.

The design and proof obligations are documented in
[the architecture](docs/architecture/tracebox-design.md) and
[implementation plan](docs/implementation-plan.md). Tracebox is still an alpha: use the published
tag matching the consumer integration and review the recorded evidence boundary before shipping.

## Modules

Applications normally depend on `io.github.tracebox:tracebox` and add
`io.github.tracebox:tracebox-native` only when native capture is enabled. The repository also
publishes the API, core, storage, direct-boot, ANR/exit, export, export UI, and Compose UI modules as
separate AARs so consumers can keep optional surfaces out of their package.

Tracker's normal integration uses:

```kotlin
implementation("io.github.tracebox:tracebox:0.1.0-alpha.6")
implementation("io.github.tracebox:tracebox-native:0.1.0-alpha.6")
implementation("io.github.tracebox:tracebox-ui-compose:0.1.0-alpha.6")
```

Configure the authenticated GitHub Packages repository as described in the
[release instructions](docs/releasing.md); Maven Local is not part of the release path.

## Privacy boundary

Tracebox must not receive credentials, precise location, free-form tracked content, URLs, or paths.
Log templates are bounded, compile-time-constant `LogTemplate` values; runtime values are accepted
only as privacy-classified `LogArgument`s. The API types and embedded lint check enforce that
boundary before Logcat or storage. Tracebox does not request `INTERNET`, create an HTTP client, or
automatically upload an export. A person must explicitly approve the exact package before invoking
Android save/share or an application-owned uploader. Each approved package is capped at 64 MiB and
has an explicit disposable lifetime; replacement, policy change, deletion, runtime shutdown, or UI
disposal wipes its owned bytes and removes Tracebox-owned staging.

## Development

The build uses JDK 21, targets Java 17 bytecode, compiles against Android SDK 37, and supports API
23 and newer. The native build is pinned through the repository toolchain manifests. On Windows,
run the same bounded host-readiness contract required by CI with:

```powershell
tools\verify\Invoke-Phase5HostReadiness.ps1
```

Cross-ABI Crashpad/Rust builds and packaged artifact qualification are intentionally separate from
the required pull-request path. Run them explicitly when changing native inputs:

```powershell
tools\ci\presubmit.ps1
tools\verify\Verify-Phase5NoNetworkStatic.ps1 -SkipBuild
```

The representative rootable emulator suite is a bounded manual qualification job rather than a
pull-request prerequisite.

See [toolchain policy](docs/toolchains-and-dependencies.md), [release instructions](docs/releasing.md),
[contributing](CONTRIBUTING.md), and [security reporting](SECURITY.md).

## License

Tracebox is licensed under the [Apache License 2.0](LICENSE). Third-party dependency licensing is
tracked in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
