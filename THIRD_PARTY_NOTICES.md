# Third-party notices

This inventory covers the initial 0.1.0-alpha.1 source and release runtime.
It is intentionally small. Crashpad, Rust tooling, and native code are not
bundled or published by this alpha.

| Component | Version | Scope | License | Source |
| --- | --- | --- | --- | --- |
| Kotlinx Coroutines Core | 1.11.0 | Runtime API support | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Kotlin standard library | 2.4.10 | Transitive runtime API support, published in Maven metadata | Apache-2.0 | https://kotlinlang.org/ |
| JUnit | 4.13.2 | Test only, not published in AARs | EPL-1.0 | https://github.com/junit-team/junit4 |
| Hamcrest Core | 1.3 | Test only, transitive from JUnit and not published in AARs | BSD-3-Clause | https://github.com/hamcrest/JavaHamcrest |

Before adding any dependency or vendored source, update this inventory with its
exact version or immutable revision, SPDX expression, source, and any required
notice text. Dependencies with unknown, incompatible, or unreviewed licensing
must not enter a release.
