# Changelog

All notable changes are documented here. Tracebox follows Semantic Versioning
once its public API stabilizes.

## Unreleased

### Changed

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
