# Tracebox

Tracebox is an offline Android diagnostics library under active development.
Its intended foundation is defined in the [architecture](docs/architecture/tracebox-design.md):
privacy-classified recording, crash and ANR evidence, deterministic local
exports, and no Tracebox-owned networking.

## Alpha status

`0.1.0-alpha.1` is a **pre-certification API and runtime bootstrap**, not a
production diagnostics release. It publishes two real Android AARs:

- `io.github.tracebox:tracebox-api` — typed, bounded public contracts.
- `io.github.tracebox:tracebox` — a bounded, in-memory structural recorder.

The alpha deliberately does **not** claim durable storage, Crashpad/native or
Rust fault capture, live ANR detection, `ApplicationExitInfo` reconciliation,
`.tbdiag` creation, disclosure/approval UI, local save/share, symbolication, or
a certified no-network result. Package preparation returns an explicit
unavailable result rather than creating an incomplete export.

The only implemented recording path, enabled by `StandardDiagnostics`, retains
bounded structural codes in memory for the lifetime of the handle. It does not
persist or upload data and its manifests declare no `INTERNET` permission. The
complete product boundary and future proof requirements remain in the
architecture documents.

The alpha's public surface is Kotlin-first and intentionally has no Java source
or binary compatibility promise yet. A Java interop policy will be added before
the first supported stable release.

## Install from GitHub Packages

GitHub's Gradle registry is repository scoped and requires authentication for
package resolution, including public repositories. Use this repository's
**lowercase** `owner/repository` path, then put a classic PAT with `read:packages`
in `~/.gradle/gradle.properties` (or equivalent CI secrets):

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_CLASSIC_PAT_WITH_READ_PACKAGES
```

Add the repository and dependency in Kotlin DSL:

```kotlin
repositories {
    google()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/owner/repository")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
            password = providers.gradleProperty("gpr.key").orNull
        }
    }
}

dependencies {
    implementation("io.github.tracebox:tracebox:0.1.0-alpha.1")
}
```

`tracebox` brings in `tracebox-api`. Consumers that only need the contract can
depend on `io.github.tracebox:tracebox-api:0.1.0-alpha.1` directly.

## Minimal alpha usage

```kotlin
val tracebox = Tracebox.install(
    applicationContext,
    TraceboxConfiguration.builder()
        .setInitialProfile(DiagnosticsProfile.StandardDiagnostics)
        .build(),
)

tracebox.diagnostics.breadcrumb(
    GeneratedBreadcrumb(code = DiagnosticCode.of(1001)),
)

// The alpha is volatile only. This clears its locally owned in-memory records.
tracebox.delete(DeleteRequest.All)
```

`MinimalCrash` is the default profile. Since crash capture is not implemented
yet, the alpha accepts generated application events only when callers explicitly
select `StandardDiagnostics`; lowering to `MinimalCrash` or `Disabled` clears
the volatile generated-event buffer.

Do not pass credentials, tokens, raw user content, URLs, paths, or other
sensitive data to this alpha. The final generated schema and privacy controls
have not yet been implemented.

## Development

The build uses Android Gradle Plugin 9.1.1, Gradle 9.3.1, JDK 17, `compileSdk`
37, and `minSdk` 30. Run the verified project build with:

```bash
./gradlew check
```

See [toolchain policy](docs/toolchain.md), [release instructions](docs/releasing.md),
[contributing](CONTRIBUTING.md), and [security reporting](SECURITY.md).

## License

Tracebox is licensed under the [Apache License 2.0](LICENSE). Third-party
dependency licensing is tracked in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
