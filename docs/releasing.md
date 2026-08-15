# Releasing Tracebox

The alpha release source of truth is an annotated Git tag matching exactly:

```text
vMAJOR.MINOR.PATCH-alpha.N
```

The GitHub workflow rebuilds the annotated tagged commit, derives the publication
version from the tag. The complete implementation publishes ten module AARs; release automation
must verify every POM and AAR with a clean Gradle consumer from the
repository-scoped Maven endpoint, then publishes a GitHub **pre-release** with
checksums and legal notices.

Release automation checks coordinate availability and post-publication POM/AAR reachability for
all ten modules. The clean consumer also hashes each downloaded AAR against the exact locally
verified release build before a draft can be published.

## One-time repository setup

Before the first tag, a repository administrator must:

1. Push this repository to its final public `OWNER/REPOSITORY` GitHub location.
2. Make the GitHub repository public and enable GitHub Actions.
3. Allow GitHub Actions to receive the job-scoped `contents: write` and
   `packages: write` permissions required by the protected release environment.
4. Create the `github-packages-alpha` environment and require an appropriate
   maintainer review; restrict it to protected alpha tags.
5. Protect `main` and require the `CI / required host readiness` status check.
6. Create an active repository ruleset for `v*-alpha.*` that explicitly restricts
   tag creation, updates, and deletion. Keep any bypass list narrowly limited to
   authorized release maintainers.
7. Enable private vulnerability reporting and replace the placeholder reporting
   text in `SECURITY.md` and `CODE_OF_CONDUCT.md`.
8. Review the Apache-2.0 copyright designation with the actual legal owner.
9. Never store a personal access token in the repository. The release workflow
   uses its short-lived `GITHUB_TOKEN`.

GitHub Packages' Gradle registry is repository scoped. Consumers need the
lowercase endpoint `https://maven.pkg.github.com/owner/repository` and an
authenticated classic PAT with `read:packages`.

## Alpha release procedure

1. Ensure the final commit is merged to `main` and CI is green.
2. Confirm `CHANGELOG.md` and dependency notices accurately describe the release.
3. Create and verify an annotated tag:

   ```bash
   git tag -a v0.1.0-alpha.4 -m "Tracebox 0.1.0-alpha.4"
   git push origin v0.1.0-alpha.4
   ```

4. Approve the protected release environment when GitHub prompts for it. The
   workflow validates the tag, test suite, AARs, checksums, and unused package
   coordinates before creating a draft release and publishing immutable packages.
5. Verify that GitHub Packages contains all ten AARs and that the GitHub release is
   marked as a prerelease.
6. Resolve the packages using the consumer snippet in `README.md`.
7. Download the ten AARs and the checksum asset, then run
   `sha256sum -c tracebox-0.1.0-alpha.4-sha256sums.txt` from that directory.
8. Never delete or overwrite a published version. Correct mistakes with a new
   alpha version and a new tag.

## Failure recovery

GitHub Packages publication and GitHub Release publication are not one atomic
operation. The tag workflow creates a protected **draft** only after the build
has passed, and it refuses to publish a coordinate whose Maven POM already
exists.

- If the workflow fails before a draft exists and every module POM URL returns HTTP 404,
  fix the failure and rerun the tag workflow.
- If a draft exists and every module POM/AAR URL is resolvable, run **Finalize alpha release
  draft** from GitHub Actions with that
  exact tag. It rebuilds the tagged source, rechecks the consumer endpoints,
  uploads checksums/legal notices, and publishes only the existing draft; it
  never republishes Maven artifacts.
- If only a subset of module artifacts is visible after a failed publish,
  do not publish the draft and do not reuse the version. Preserve the partial
  state for audit, correct the cause, and roll forward to a new alpha version.

This recovery design prevents an automated retry from accidentally overwriting
or duplicating immutable package coordinates.

## Isolated candidate publication

Never put an unpublished Tracebox build in the user's global Maven Local cache. Publish a uniquely
versioned candidate to an explicit disposable repository instead:

```text
./gradlew.bat \
  -PtraceboxVersion=0.1.0-alpha.4-<commit> \
  -PtraceboxLocalRepository=C:\tmp\tracebox-0.1.0-alpha.4-<commit> \
  publishFoundation
```

The property configures a file-backed `IsolatedCandidate` repository for all ten publications,
rejects the global `~/.m2/repository`, and is forbidden when `CI` is present. Pass the same path and
version to Tracker's candidate-validation seams, then remove the disposable repository after the
consumer checks. This workflow never contacts GitHub and never changes the catalog-pinned release.

To exercise the GitHub publication configuration outside Actions, set the
repository and credentials in an untracked local properties file or the
environment:

```bash
./gradlew \
  -PtraceboxGitHubRepository=OWNER/REPOSITORY \
  -Pgpr.user=YOUR_GITHUB_USERNAME \
  -Pgpr.key=YOUR_CLASSIC_PAT \
  -PtraceboxVersion=0.1.0-alpha.4 \
  verifyReleaseMetadata publishFoundation
```

Do not use that command to overwrite a release version.
