# Contributing to Tracebox

Thanks for helping improve Tracebox. This repository is intended to be a
privacy-first, offline Android diagnostics library, so changes need unusually
strong review and evidence.

## Contribution terms

By submitting a contribution, you certify it under the
[Developer Certificate of Origin 1.1](https://developercertificate.org/) and
license it under the repository's Apache-2.0 license. Add a DCO sign-off:

```bash
git commit -s -m "Describe the change"
```

No contributor license agreement is required for this alpha.

## Before opening a pull request

- Keep changes scoped and document user-visible behavior.
- Add or update tests and run `./gradlew check`.
- Do not add networking, upload, remote configuration, generic logging APIs, or
  unbounded diagnostic fields.
- Pin each new dependency, update `THIRD_PARTY_NOTICES.md`, regenerate dependency
  locks and verification metadata, and explain its license/provenance.
- Do not claim a feature is certified or production ready without the evidence
  required by the architecture and implementation plan.
- Preserve generated-code boundaries; generated APIs must be changed through the
  schema compiler once it exists.

Maintainers should require review for release, privacy, native, and build-system
changes. See the architecture documents before proposing a product-boundary
change.

