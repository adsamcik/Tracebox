# Phase 0 Toolchains and Dependencies

Normative machine-readable pins are in `gradle/toolchains.lock.toml`, `gradle/verification-metadata.xml`, Gradle lockfiles, `Cargo.lock`, `rust-toolchain.toml`, and `third_party/crashpad/source-lock.json`.

| Component | Pin | Provenance | License | Rationale |
|---|---|---|---|---|
| Gradle | 9.7.1 | services.gradle.org, distribution SHA-256 locked | Apache-2.0 | Current wrapper for AGP 9.4.0 |
| AGP | 9.4.0 | Google Maven | Apache-2.0 | Current API 37 Android build toolchain |
| Kotlin | 2.4.10 | Maven Central | Apache-2.0 | Tracker-aligned Kotlin and Compose compiler plugin |
| Coroutines | 1.11.0 | Maven Central | Apache-2.0 | Tracker-aligned public `StateFlow` and coroutine handler ABI |
| Robolectric | 4.16.1 | Maven Central | MIT | Bounded host-side Compose UI and Android lifecycle verification |
| JDK | Temurin 21 | Adoptium | GPL-2.0-with-classpath-exception | Pinned AGP LTS; Java bytecode remains 17 |
| Android SDK | compile/target 37, min 23, Build Tools 37.0.0 | Google Android SDK | Android SDK License | Required support boundary |
| NDK | 28.2.13676358 | Google Android SDK | Android SDK License | Complete LLVM archive toolset and 16 KiB support; NDK 29.0.14206865 omitted `llvm-ar.exe` on Windows |
| CMake | 4.1.2 | Google Android SDK package | BSD-3-Clause | Pinned AGP external-native build |
| Rust | 1.93.1 | static.rust-lang.org manifest hash | Apache-2.0 OR MIT | Stable edition-2024 toolchain and Android targets |
| Crashpad | efdc820b087c20eec9e32cb5e5b1a63dcf73a724 | Chromium Gitiles immutable revision | Apache-2.0 | Current reviewed pin for Android feasibility |

Crashpad transitive source pins are mini_chromium `e5169551...` (BSD-3-Clause), linux-syscall-support `9719c1e...` (BSD-3-Clause), zlib `fef58692...` (Zlib), googletest `3983f67...` (BSD-3-Clause), and Chromium buildtools `efa920ce...` (BSD-3-Clause). `source-lock.json` pins each downloaded archive's exact byte size and SHA-256. Acquisition authenticates those bytes and preflights every archive entry before any destination write, then independently verifies each normalized extracted source tree and the complete post-patch checkout. Any upstream transport-byte change therefore requires an explicit reviewed lock refresh even when the immutable source revision is unchanged.

No runtime dependency is permitted to introduce HTTP, DNS, remote configuration, upload, analytics, or transport code. Gradle dependency resolution is repository-restricted, locked, and SHA-256 verified. Rust has no registry dependency in Phase 0. Crashpad source is outside the Gradle runtime graph until its explicit capture-only build is verified.

Gradle's build cache and configuration cache are enabled. Kotlin compilation runs in the Gradle
process so nested cross-language contract tests do not depend on a separately managed compiler
daemon transport.

Updates refresh provenance, licenses, hashes/locks, the affected host gates, and
no-network scans. Runtime-affecting updates rerun the representative emulator
smoke. Crashpad updates additionally require a clean patch rebase; the
historical full feasibility/matrix campaign is optional diagnostics.

Every push and pull request reports one required Linux release-readiness job. Build changes run
source/toolchain integrity, schema, Rust, native host, Gradle tooling, and Android release checks.
Ordinary documentation-only changes skip SDK/toolchain setup and compilation. Unknown paths and
integrity inputs always run the full check; the required status is never omitted by a workflow
path filter. Gradle, Cargo, and native builds use at most two workers, obsolete CI runs cancel,
and jobs time out after 40 minutes. Gradle caching remains enabled.

No Windows, scheduled native rebuild, or self-hosted emulator CI jobs remain. Cross-native/Crashpad
rebuilds and rootable-emulator qualification remain explicit local procedures. Publication rebuilds
the immutable tag and verifies all ten package endpoints and checksums before exposing the release.
