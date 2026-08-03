# ADR-0011: Runtime Logging, Privacy, Performance, and Optional UI

## Status

Accepted for `0.1.0-alpha.2`. This supersedes ADR-0010's requirement that the
Tracker integration use only a fixed generated diagnostic catalog.

## Context

A fixed catalog made ordinary application logging unnecessarily expensive to
integrate and encouraged a second compatibility layer. Tracker needs one small,
conventional logging API with runtime levels, privacy enforcement, handled
exception capture, and useful performance timings. Hosts that do not need
Crashpad or Compose must not inherit those dependencies.

## Decision

Tracebox provides:

- `Tracebox.log` with `verbose`, `debug`, `info`, `warn`, and `error` template
  methods using `{}` parameters;
- privacy classification before rendering, Logcat, or storage, with strings and
  unknown objects defaulting to PII;
- explicit value wrappers and immutable host-registered domain renderers;
- a throwable error overload and `Tracebox.crashes.record` for one-call handled
  exception capture without messages;
- synchronous, suspending, and manual performance measurements on the same
  logger, independently runtime-gated and optionally duration-filtered;
- one persisted `TraceboxPolicy` for log level, Logcat, performance, and capture
  sources; and
- a separately declared `tracebox-ui-compose` artifact containing reusable
  controls and export/deletion UI.

All variable text fields have schema bounds. Arguments are not rendered when a
call is disabled. Templates are treated as developer-authored format strings;
runtime values belong in parameters. Generated structural records remain
available for internal crash/storage protocols and specialized integrations.

`tracebox-native` remains a separate explicit artifact and is `compileOnly`
from the base runtime. The base publication therefore has no transitive native
dependency. Native capture also requires an explicit installation flag.

## Consequences

Tracker can remove its logger and fixed-catalog facades entirely. Apps can use
base managed diagnostics, opt into native capture, and opt into Compose UI
independently. Privacy is simple at call sites, but templates containing runtime
PII cannot be detected automatically and are prohibited by the API contract.

This performance API records bounded diagnostic timings; it does not authorize
the Phase 6 metrics/traces or uploader scope.
