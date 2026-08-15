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
