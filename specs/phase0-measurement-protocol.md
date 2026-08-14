# Historical Phase 0 Diagnostic Measurement Protocol

This protocol preserves the original diagnostic campaign so it can be reused
when investigating regressions. It is not a personal-release gate. ADR-0010
requires host enforcement of hard bounds plus only the representative
functional/privacy/no-network emulator IDs in
`tooling/fixtures/personal-release-scenarios.json`.

## Engineering matrix

| Lane | Required result |
|---|---|
| Existing API 36 x86_64 emulator, 4 KiB | Required only for the representative smoke |

Additional API, ABI, page-size, physical-device, and OEM lanes are advisory and
do not block `PERSONAL_RELEASE_READY`. Debug is covered by host build/unit
checks; the emulator smoke runs only the minified qualification fixture.

## Optional diagnostic repetition

- Use monotonic time for durations.
- Warm up for 30 seconds unless measuring cold start.
- Run latency-sensitive scenarios five times and record median and maximum.
- Observe healthy CPU, wakeups, and memory for two minutes after settling.
- Run one ten-minute healthy watchdog false-candidate observation.
- Treat results as observations unless a manifest-required functional scenario
  or host-enforced hard invariant fails.

## Targets and release invariants

| Metric | Personal-project treatment |
|---|---|
| Install/readiness, handler PSS/CPU, fatal latency, native size, heartbeat work, interactive wakeups, and target pause | Optional observations; investigate visible regressions |
| Handler polling and ineligible heartbeat | Host-enforced architectural invariants; live observation optional |
| Healthy false confirmations | Host state-machine invariant; long live observation optional |
| Deterministic stall | Covered once by manifest-required `ANR.CANDIDATE` |
| Handler-hang timeout/cancellation | Host-tested; live repetition is an opt-in diagnostic |
| Configured storage/archive/parser limits | Mandatory host-tested hard bounds; live pressure is optional |

## Optional extended workloads

Handler: idle, two simultaneous clients, handler cold/running/killed/restarted/hung, three-start crash loop, fatal `SIGABRT`, fatal `SIGSEGV`, nonfatal request, constrained storage.

Emergency: crash before Durable, closed/unavailable handler socket, recursive signal, registered-thread stack overflow, forced short write, forced failed write, and verification that default re-raise remains observable.

ANR: healthy interactive/non-interactive/ineligible, deterministic six-second main block, busy loop, deadlock, handler hang, repeated identical stalls, debugger suppression, startup grace, lifecycle suspend/reactivate.

Privacy: place unique seeded secrets in stack, heap, environment-like application buffers, and annotations offered to the client. Raw files may match and remain C2; every structural field and exported Phase 0 summary must have zero seed matches. No internal ID encoding may be deliberately injected.

## Optional extended fuzz/corruption budgets

- Presubmit may spend 60 seconds per native parser/writer target with retained
  crashing inputs.
- An extended diagnostic run may spend 5 minutes per parser/writer target.
- Exhaustively truncate every boundary for objects up to 64 KiB.
- For larger bounded objects, test every boundary in the first/last 4 KiB plus 4096 deterministic interior offsets.
- Flip every fixed header field and run 10,000 deterministic generated length/CRC/count/path corruptions per format.
