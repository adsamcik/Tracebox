# ADR-0008: Engineering Feasibility Prerequisite Supersession

## Status

Accepted by explicit user supersession on 2026-07-18

## Context

Phase 0 is `INCOMPLETE`, and `ENGINEERING_FEASIBILITY_PASS` was not reached. The recorded API 30 x86_64 lane fails frozen thresholds, the locally provisioned API 37 x86_64 16 KiB lane is unusable and `FAIL`, and representative arm64 physical lanes are `UNAVAILABLE_EXTERNAL`.

After receiving those exact blockers and the assignment rule prohibiting Phase 1 without explicit supersession, the user selected verbatim:

> Explicitly supersede the ENGINEERING_FEASIBILITY_PASS prerequisite and implement Phases 1–5 despite these failures

## Decision

- The `ENGINEERING_FEASIBILITY_PASS` prerequisite is superseded only for permission to implement Phases 1–5.
- Phases 1–5 may proceed at implementation risk despite the recorded Phase 0 failures.

## Non-effects

- Phase 0 remains `INCOMPLETE`; every recorded `FAIL` and `UNAVAILABLE_EXTERNAL` result retains that state.
- No threshold, workload, required API/ABI/page-size lane, privacy rule, Crashpad requirement, live-ANR requirement, or evidence standard is relaxed.
- The decision does not create `ENGINEERING_FEASIBILITY_PASS`, `CERTIFICATION_FEASIBILITY_PASS`, or `FOUNDATION_CERTIFIED`.
- Certification remains impossible until all mandatory engineering and certification gates pass on their required matrix.
- Downstream implementation must not cite this decision as evidence that a failed dependency works or that unavailable hardware passed.

## Consequences

Later agents may begin and continue Phase 1–5 implementation without repeating the product-decision stop. They must preserve the Phase 0 evidence, carry the failed assumptions as explicit implementation risk, and rerun the required gates before making any certification claim.
