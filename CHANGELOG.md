# Changelog

All notable changes are documented here. Tracebox follows Semantic Versioning
once its public API stabilizes.

## Unreleased

## [0.1.0-alpha.6] - 2026-08-16

### Added

- Added independently gated, low-volume `performanceEvent` observations for bounded host-owned
  battery, wakeup, startup, and memory facts without enabling a metrics/traces subsystem.

### Changed

- Closed the downstream Tracker integration checklist with strict consumer-build and representative
  API 36 process, policy, native-runtime, review/share, and staging-cleanup evidence for the
  immutable alpha.5 package.
- Made host readiness fail when any personal-release checklist item returns to an unfinished or
  failed state.

## [0.1.0-alpha.5] - 2026-08-16

### Changed

- Rolled the fully qualified alpha.4 implementation forward without runtime, API, schema, or
  privacy-contract changes after the alpha.4 publication tag failed before creating a release,
  draft, or package.
- Made Linux release readiness a required pre-tag CI job using the exact release task set and the
  same Ubuntu runner family as publication.
- Completed strict cross-platform dependency verification for cold Windows and Linux runners.
- Added a fail-closed manual release path for an unchanged annotated tag when a workflow-only
  failure occurs before any immutable publication state exists.
- Resolved Android SDK command-line tools from the hosted runner's declared SDK root in both alpha
  publication workflows.

## [0.1.0-alpha.4] - 2026-08-16

### Changed

- Aligned the build with Tracker's Gradle 9.6.1, AGP 9.3.1, Kotlin 2.4.10,
  coroutines 1.11.0, JDK 21, Java 17, and Android SDK 37 toolchain.
- Joined the complete diagnostics implementation history with the release-engineering history so
  the default branch, release automation, tags, and consumer source now describe one product.
- Extended release verification from the original two-module bootstrap to all ten published
  Android modules while preserving the no-network boundary.
- Replaced raw logging strings and values with bounded static `LogTemplate` and privacy-classified
  `LogArgument` contracts, backed by a consumer-facing fatal lint check.
- Made idempotent installation compare privacy adapters by ordered type/classification mappings and
  exact renderer identity instead of requiring the same `PrivacyConfiguration` container instance.
- Bounded the runtime to one explicitly disposable approved package, wipe-on-transfer byte owners,
  and cleanup on replacement, policy change, deletion, runtime close, and diagnostics-screen exit.
- Enforced the released schema as an immutable append-only event prefix with a checksum-protected
  compatibility baseline; released event/field changes, removal, reordering, and ID unreservation
  now fail generation.
- Pinned `.tbdiag` package v1 to an explicit record ABI and schema fingerprint, generated the Rust
  decoder from the schema, and added strict canonical ZIP/CBOR validation plus an exact Kotlin/Rust
  package golden.
- Moved every diagnostics, disclosure, approval, delivery, failure, and deletion message into
  host-overridable Android string resources, including quantity-aware save results.
- Preserved pending review and deletion choices across recreation, exposed complete switch,
  heading, progress, and live-status semantics, and made advanced controls resilient to RTL and
  large-font layouts.
- Added host-run Compose tests for runtime policy and capture controls, review/approval,
  recreation, upload-disabled and retry behavior, save/share, deletion, RTL, large fonts,
  accessibility semantics, and staging cleanup.
- Kept Crashpad database metadata outside the raw-artifact quota and reserved its bounded settings
  bytes before native startup, so a full-size native handoff is ingested on the first restart.
- Removed the message-bearing JVM capture option and field, making throwable-message persistence
  structurally unavailable rather than merely disabled by default.
- Made the required host-readiness workflow cover every published AAR while keeping native/emulator
  qualification in bounded manual or scheduled lanes.

## [0.1.0-alpha.3] - 2026-08-03

### Added

- Added the casual-user-first diagnostics flow, configurable advanced controls, and host-owned
  approved-package uploader contract without adding Tracebox networking.
- Kept technical disclosure and exact package approval mandatory for save, share, and upload.

## [0.1.0-alpha.2] - 2026-08-03

### Added

- Added the first Tracker-oriented durable runtime with privacy-aware logging, persisted policy,
  managed/native crash capture, ANR and process-exit diagnostics, deterministic export, and the
  optional Compose diagnostics UI.
- Published separately selectable managed, native, and Compose artifacts.

## [0.1.0-alpha.1] - 2026-07-20

### Added

- Publishable Android artifacts: tracebox and tracebox-api.
- Typed, bounded alpha recording contracts with no generic event-name or map API.
- A bounded in-memory structural recorder with explicit deletion and closed states.
- Gradle dependency locking/verification hooks, GitHub Packages publication metadata,
  CI, tag-driven alpha release automation, and open-source project files.

### Not included

This pre-certification alpha does not provide durable segments, Crashpad, native
or Rust capture, ANR capture, deterministic tbdiag export, disclosure UI,
symbolication, or foundation certification.
