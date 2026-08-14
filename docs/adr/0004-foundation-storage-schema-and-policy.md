# ADR-0004: Foundation Storage, Schema, Direct Boot, and Policy

## Status

Accepted by implementation assignment

## Decisions

This ADR freezes section-27 decisions 6, 7, 8, and 12.

Foundation C1/C2 storage uses Android credential-encrypted app-private `noBackupFilesDir` without application-layer AEAD. No plaintext fallback exists because no application-layer encrypted mode is exposed. C2 remains profile-gated, quota-bound, short-lived, and ineligible for Standard packages.

The Direct Boot schema is C0-only and fixed to:

- schema/build fingerprint;
- process role and package-localizable process-instance reference;
- elapsed timestamp and wall-clock discontinuity flag;
- readiness transition code;
- signal/exit reason and bounded status code;
- emergency completion/checksum state;
- bounded drop, corruption, and evidence-loss counters;
- active/pending deny epoch and deny reason.

It contains no text, path, URL, symbol, throwable message, raw artifact, stack, arbitrary integer payload, or C1/C2 field.

Schema evolution is append-only:

- numeric IDs are never reused;
- field meaning and privacy class never broaden under an existing ID;
- bounds may only tighten compatibly;
- incompatible changes require a new record/ABI version;
- release recording rejects unknown fields;
- compatibility decoders quarantine bounded unknown fields as non-exportable C2;
- generated Kotlin/C/Rust surfaces are the only recording APIs.

Policy updates use the architecture's handler-owned epoch/control-page, restrictive token staging, fenced registration, CE commit, queue/handler barriers, and delayed permissive tokens. Results are:

- `SUCCESS`: every required live or unverified participant crossed the target barrier;
- `LOCAL_ONLY_RESTRICTED`: caller applied a tightening locally but package-wide proof failed;
- `PARTIAL`: named participants failed or remained unverified;
- `FAILED`: no safe requested transition was committed.

Ambiguity is restrictive. Loosening never returns local-only success. Deletion success requires the same global proof and no accessible in-scope Tracebox-owned data.

## Rationale

These choices implement the accepted baseline without introducing the separately gated encryption milestone. Direct Boot cannot structurally admit C1/C2. Policy outcomes avoid a false global-success claim.

