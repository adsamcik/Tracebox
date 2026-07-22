# ADR-0009: API 23 Baseline and Single-Emulator Qualification

## Status

Accepted by explicit user decision on 2026-07-22

## Context

The original foundation baseline required API 30-37 emulator and physical-device
qualification. The user explicitly changed both requirements:

> Let's retarget to API 23

The clarification selected `minSdk 23` with `compileSdk` and `targetSdk` 37, and
selected the one existing emulator as the sole required certification matrix.
That emulator is currently API 36, `x86_64`, with a 4 KiB page size.

Tracebox uses `java.nio.file` and other modern Java library APIs. API 23 support
therefore requires core-library desugaring with the NIO desugaring library.

## Decision

- Tracebox declares `minSdk 23`, `compileSdk 37`, and `targetSdk 37`.
- Android libraries and applications enable core-library desugaring with
  `com.android.tools:desugar_jdk_libs_nio`.
- Native Crashpad artifacts are compiled with Android API level 23.
- The sole required runtime qualification lane is the existing API 36
  `x86_64`, 4 KiB emulator.
- Additional API levels, ABIs, page sizes, physical devices, and OEM families
  are advisory and do not block foundation certification.
- API-specific integrations remain capability-gated. In particular,
  `ApplicationExitInfo` is unavailable on API 23-29, while the local JVM,
  native, emergency, and live-watchdog paths remain foundation capabilities.

## Superseded requirements

This decision supersedes only the minimum Android version and mandatory
platform qualification matrix in ADR-0001, ADR-0003, ADR-0007, ADR-0008, the
detailed architecture, implementation plan, and Phase 0 measurement protocol.

It does not relax the offline boundary, generated privacy classifications,
capture-only Crashpad topology, emergency fallback, live ANR watchdog, exact
approval, deterministic package, storage bounds, or no-network requirements.

## Consequences

- Historical API 30/API 37 and physical-device evidence remains preserved but
  is no longer a release gate.
- API 23 compatibility is enforced by build configuration, desugaring, lint,
  and static verification; runtime qualification is performed only on the
  required existing emulator.
- Physical-device and OEM behavior must not be claimed as independently tested.
- Foundation certification still requires all mandatory implementation and
  privacy gates to pass on the required emulator.
