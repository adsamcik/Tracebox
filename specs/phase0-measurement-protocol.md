# Personal-Project Phase 0 Measurement Protocol

ADR-0009 replaces the original multi-device matrix. ADR-0010, accepted by the
user, replaces the enterprise statistical release gate below with one-emulator
functional and resource smoke evidence. Historical runs retain their original
criteria and results.

## Engineering matrix

| Lane | Required result |
|---|---|
| Existing API 36 x86_64 emulator, 4 KiB | PASS |

Additional API, ABI, page-size, physical-device, and OEM lanes are advisory and
do not block `PERSONAL_RELEASE_READY`. Debug and one minified release-like
variant are required.

## Personal-project repetition

- Use monotonic time for durations.
- Warm up for 30 seconds unless measuring cold start.
- Run latency-sensitive scenarios five times and record median and maximum.
- Observe healthy CPU, wakeups, and memory for two minutes after settling.
- Run one ten-minute healthy watchdog false-candidate observation.
- A flaky-only pass is FAIL. Every required assertion must pass in one complete run.

## Targets and release invariants

| Metric | Personal-project treatment |
|---|---|
| Install/readiness, handler PSS/CPU, fatal latency, native size, heartbeat work, interactive wakeups, and target pause | Record and compare with the original targets; investigate large regressions, but numerical values are advisory |
| Handler timer/poll wakeups | Mandatory zero |
| Ineligible watchdog wakeups after settling | Mandatory zero |
| Healthy false confirmations | Mandatory zero; candidates are recorded and investigated |
| Deterministic six-second stall | Mandatory capture in the complete smoke run |
| Handler-hang timeout/cancellation | Mandatory bounded completion without deadlock |
| Package working memory and all configured storage/archive/parser limits | Mandatory hard bounds |

## Workloads

Handler: idle, two simultaneous clients, handler cold/running/killed/restarted/hung, three-start crash loop, fatal `SIGABRT`, fatal `SIGSEGV`, nonfatal request, constrained storage.

Emergency: crash before Durable, closed/unavailable handler socket, recursive signal, registered-thread stack overflow, forced short write, forced failed write, and verification that default re-raise remains observable.

ANR: healthy interactive/non-interactive/ineligible, deterministic six-second main block, busy loop, deadlock, handler hang, repeated identical stalls, debugger suppression, startup grace, lifecycle suspend/reactivate.

Privacy: place unique seeded secrets in stack, heap, environment-like application buffers, and annotations offered to the client. Raw files may match and remain C2; every structural field and exported Phase 0 summary must have zero seed matches. No internal ID encoding may be deliberately injected.

## Fuzz and corruption budgets

- Presubmit: 60 seconds per native parser/writer target with retained crashing inputs.
- Personal release: 5 minutes per parser/writer target.
- Exhaustively truncate every boundary for objects up to 64 KiB.
- For larger bounded objects, test every boundary in the first/last 4 KiB plus 4096 deterministic interior offsets.
- Flip every fixed header field and run 10,000 deterministic generated length/CRC/count/path corruptions per format.
