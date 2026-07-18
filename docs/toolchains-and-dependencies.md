# Phase 0 Toolchains and Dependencies

Normative machine-readable pins are in `gradle/toolchains.lock.toml`, `gradle/verification-metadata.xml`, Gradle lockfiles, `Cargo.lock`, `rust-toolchain.toml`, and `third_party/crashpad/source-lock.json`.

| Component | Pin | Provenance | License | Rationale |
|---|---|---|---|---|
| Gradle | 9.6.1 | services.gradle.org, distribution SHA-256 locked | Apache-2.0 | Current stable wrapper; supports AGP 9.2 |
| AGP | 9.2.0 | Google Maven | Apache-2.0 | Stable API 37.0 support |
| Kotlin | 2.2.10 built into AGP | AGP runtime dependency/Maven Central | Apache-2.0 | AGP 9.2 built-in Kotlin; separate Android plugin is forbidden |
| JDK | Temurin 21.0.8+9 | Adoptium | GPL-2.0-with-classpath-exception | AGP-compatible LTS |
| Android SDK | compile/target 37, min 30, Build Tools 37.0.0 | Google Android SDK | Android SDK License | Required support boundary |
| NDK | 29.0.14206865 | Google Android SDK | Android SDK License | Current installed stable NDK with 16 KiB support |
| CMake | 4.1.2 | Google Android SDK package | BSD-3-Clause | Pinned AGP external-native build |
| Rust | 1.93.1 | static.rust-lang.org manifest hash | Apache-2.0 OR MIT | Stable edition-2024 toolchain and Android targets |
| Crashpad | efdc820b087c20eec9e32cb5e5b1a63dcf73a724 | Chromium Gitiles immutable revision | Apache-2.0 | Current reviewed pin for Android feasibility |

Crashpad transitive source pins are mini_chromium `e5169551...` (BSD-3-Clause), linux-syscall-support `9719c1e...` (BSD-3-Clause), zlib `fef58692...` (Zlib), googletest `3983f67...` (BSD-3-Clause), and Chromium buildtools `efa920ce...` (BSD-3-Clause). The acquisition process verifies normalized extracted source-tree SHA-256 values because Gitiles-generated gzip transport bytes are not stable across requests.

No runtime dependency is permitted to introduce HTTP, DNS, remote configuration, upload, analytics, or transport code. Gradle dependency resolution is repository-restricted, locked, and SHA-256 verified. Rust has no registry dependency in Phase 0. Crashpad source is outside the Gradle runtime graph until its explicit capture-only build is verified.

Updates require a dedicated commit that refreshes provenance, license review, hashes/locks, reproducibility evidence, no-network scans, and the affected platform matrix. Crashpad updates additionally require a clean patch rebase and complete mandatory feasibility rerun.
