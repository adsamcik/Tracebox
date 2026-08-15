# ADR-0005: Live ANR Thresholds and Sampling

## Status

Accepted by implementation assignment

## Decisions

This ADR freezes section-27 decision 9 and the immutable Phase 0 ANR gate.

- Foreground-interactive heartbeat interval: 2 seconds.
- Foreground non-interactive interval: 5 seconds.
- No eligible observable component: heartbeat and watchdog waits are suspended.
- Suspected stall threshold: 3 seconds without acknowledgement.
- Credible stall threshold: two missed acknowledgements and at least 5 seconds elapsed.
- Startup grace: 10 seconds after `Application.onCreate`.
- Resume/suspend grace: 10 seconds after a detected monotonic gap or lifecycle reactivation.
- Debugger: candidate capture suppressed and counted separately.
- Main-thread samples: at most 3, 250 ms apart, at most 64 frames and 8 KiB encoded per sample.
- Nonfatal handler request: at most one per ten minutes per process.
- Request deadline: 2 seconds; cancellation closes the request channel.
- Maximum target-process pause: 100 ms.
- Candidate body: at most 64 KiB; raw nonfatal artifact: at most 2 MiB.

Gate thresholds and protocol:

- warmed heartbeat post work p99 below 50 microseconds;
- watchdog CPU below 0.2% over a ten-minute eligible run;
- at most 30 scheduling wakeups/minute while interactive;
- zero watchdog/heartbeat wakeups during a ten-minute ineligible run after a 30-second settling window;
- zero false candidates in sixty healthy minutes per engineering lane;
- 10/10 capture of deterministic six-second main-looper stalls per lane;
- 10/10 timeout/cancellation completion without deadlock when the handler is hung;
- target pause p95 and maximum both at or below 100 ms;
- exactly one nonfatal request in a ten-minute repeated-stall rate window.

Measurements use monotonic clocks, a two-minute warm-up, at least 30 latency samples, and report p50/p95/p99 plus maximum. Any threshold change requires explicit user acceptance and a fresh complete run.

## Rationale

The values preserve the design's provisional overhead limits, candidate semantics, finite capture bounds, lifecycle suspension, and conservative rate limiting.

## ADR-0010 disposition

The heartbeat, eligibility, stall, grace, debugger, sample-count/size,
rate-limit, request-deadline, and artifact-size decisions remain production
configuration and hard bounds.

ADR-0010 supersedes the requirement that all percentile, CPU, wakeup,
false-positive-duration, repetition-count, and observed target-pause thresholds
pass before a personal release. Those values remain tuning and regression
targets. The required emulator suite must still prove lifecycle suppression, no
idle polling, no false confirmation, bounded work, bounded cancellation, rate
limiting, and successful deterministic candidate capture while recording the
observed values.
