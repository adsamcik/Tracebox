# Alpha toolchain policy

The first alpha intentionally pins only the toolchain and dependencies needed
for the two published Android AARs.

| Component | Pin | Reason |
| --- | --- | --- |
| Android Gradle Plugin | 9.1.1 | Android's minimum stable AGP supporting API 37 |
| Gradle wrapper | 9.3.1 | Required by AGP 9.1.1 |
| JDK | 17 | Required by AGP 9.1.1 |
| compileSdk | 37 | Product baseline |
| minSdk | 30 | Product baseline |
| Kotlin standard library | 2.4.10 | Transitive runtime API support published with the AARs |
| Kotlinx Coroutines Core | 1.11.0 | StateFlow in the published API |
| JUnit | 4.13.2 | Test-only unit testing |

The wrapper distribution is checksum-pinned. Generated dependency lockfiles and
Gradle verification metadata are committed with this alpha. CI runs strict
verification and fails on drift.

NDK, CMake, Rust, Android Rust targets, and Crashpad are deliberately not
pinned yet: they are not present in this alpha's source or published artifacts.
They must be introduced with an immutable revision, license/provenance review,
and the Phase 0 feasibility evidence required by the architecture.
