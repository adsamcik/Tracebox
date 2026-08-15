# ADR-0012: Simple-first Diagnostics UI and Host-owned Upload

## Status

Accepted for `0.1.0-alpha.3`. This supersedes ADR-0011 only where it excluded an
uploader integration contract.

## Context

The original reusable screen exposed every runtime and capture control before
the package action. That is useful for library development but makes the common
support flow harder: a user who only wants to help diagnose a problem should
not need to understand log levels, handler health, or capture sources.

Applications may also have an authenticated support backend. Tracebox must let
the reviewed package reach that backend without choosing an HTTP stack,
declaring Internet permission, embedding an endpoint, or creating an automatic
background exfiltration path.

## Decision

- The reusable Compose UI leads with one reviewed send/share action and plain
  readiness copy.
- The mandatory Tracebox-owned approval activity leads with a plain item/size
  summary and keeps exact digest, transformations, warnings, and source facts
  under an explicit technical-details disclosure.
- Technical status, policy, capture-source, reset, and deletion controls are
  collapsed under an advanced disclosure by default.
- `TraceboxDiagnosticsUiConfiguration` controls visible actions, editable
  controls, allowed values, initial expansion, copy, primary behavior, and the
  app-defined reset policy.
- `TraceboxDiagnosticUploader` is a host-supplied suspending transport. It is
  invoked only after exact disclosure approval and receives a bounded
  `TraceboxUploadRequest` with size, defensive digest, media type, filename,
  and scoped read access to approved ZIP bytes.
- Tracebox provides no uploader implementation, retry scheduler, endpoint,
  authentication, HTTP dependency, or network permission.
- Embedded screens accept configuration and uploader directly. The ready-made
  activity reads an explicitly configured process registry.
- UI defaults never overwrite persisted runtime policy. Fresh-install defaults
  remain part of `TraceboxConfiguration`; UI `defaultPolicy` is only the target
  of an app-enabled reset action.

## Consequences

Casual users get a short, recognizable support flow while advanced users retain
all controls the host elects to expose. Applications can use their existing
native networking stack, and managed-only Tracebox consumers still receive no
native or network dependency. Review and approval remain mandatory for share,
save, and direct upload.
