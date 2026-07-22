# Immutable Phase 0 Measurement Protocol

Changing any threshold or workload below requires explicit user acceptance and
a complete fresh run. ADR-0009 replaces the original multi-device matrix.

## Engineering matrix

| Lane | Required result |
|---|---|
| Existing API 36 x86_64 emulator, 4 KiB | PASS |

Additional API, ABI, page-size, physical-device, and OEM lanes are advisory and
do not block certification. Debug is required for every spike; minified and
debuggable release buildability are checked before the Phase 0 gate.

## Repetition and statistics

- Use monotonic time for durations.
- Warm up for 2 minutes unless measuring cold start.
- Latency/size samples: 30 independent runs; report p50, p95, p99, maximum.
- Healthy CPU, wakeups, and memory: 10-minute observation after a 30-second settling period, repeated three times.
- False-positive run: 60 healthy minutes per engineering lane.
- A flaky-only pass is FAIL. Every required assertion must pass in one complete run.

## Frozen thresholds

| Metric | Threshold |
|---|---:|
| Install to VolatileCapture p95 | <= 2 ms |
| Cold Durable p95 | <= 500 ms |
| Handler idle PSS maximum | <= 12 MiB |
| Handler healthy CPU | <= 0.05% |
| Handler timer/poll wakeups | 0 |
| Fatal crash to durable artifact p95 | <= 2 s |
| Native compressed size per ABI | <= 4 MiB |
| Package working memory beyond output | <= 8 MiB |
| Heartbeat post work p99 | < 50 us |
| Watchdog healthy CPU | < 0.2% |
| Interactive watchdog wakeups | <= 30/min |
| Ineligible watchdog wakeups | 0 |
| Healthy false candidates | 0 in 60 min/lane |
| Deterministic six-second stall captures | 10/10 |
| Handler-hang timeout/cancellation | 10/10 |
| Target-process pause | p95 and max <= 100 ms |

## Workloads

Handler: idle, two simultaneous clients, handler cold/running/killed/restarted/hung, three-start crash loop, fatal `SIGABRT`, fatal `SIGSEGV`, nonfatal request, constrained storage.

Emergency: crash before Durable, closed/unavailable handler socket, recursive signal, registered-thread stack overflow, forced short write, forced failed write, and verification that default re-raise remains observable.

ANR: healthy interactive/non-interactive/ineligible, deterministic six-second main block, busy loop, deadlock, handler hang, repeated identical stalls, debugger suppression, startup grace, lifecycle suspend/reactivate.

Privacy: place unique seeded secrets in stack, heap, environment-like application buffers, and annotations offered to the client. Raw files may match and remain C2; every structural field and exported Phase 0 summary must have zero seed matches. No internal ID encoding may be deliberately injected.

## Fuzz and corruption budgets

- Presubmit: 60 seconds per native parser/writer target with retained crashing inputs.
- Nightly: 30 minutes per target.
- Certification: 4 hours per target.
- Exhaustively truncate every boundary for objects up to 64 KiB.
- For larger bounded objects, test every boundary in the first/last 4 KiB plus 4096 deterministic interior offsets.
- Flip every fixed header field and run 10,000 deterministic generated length/CRC/count/path corruptions per format.
