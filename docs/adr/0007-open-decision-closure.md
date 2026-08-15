# ADR-0007: Section-27 Open Decision Closure

## Status

Accepted by implementation assignment

## Decision map

| Open decision | Frozen in |
|---|---|
| 1. Crashpad revision and patches | ADR-0002 |
| 2. Raw streams and memory ranges | ADR-0002 |
| 3. Handler lifecycle and IPC | ADR-0003 |
| 4. Coexistence default | ADR-0003 |
| 5. Production ABIs | ADR-0003 |
| 6. Pre-AEAD C1/C2 storage | ADR-0004 |
| 7. C0 Direct Boot schema | ADR-0004 |
| 8. Schema evolution | ADR-0004 |
| 9. ANR thresholds and caps | ADR-0005 |
| 10. Deterministic compression | ADR-0006 |
| 11. Raw quota and TTL | ADR-0002 |
| 12. Global policy outcomes | ADR-0004 |

All choices preserve API 30-37, the mandatory handler and live watchdog, fail closed on ambiguity, keep hard bounds at least as restrictive as the design, and do not expand collection, retention, disclosure, or export eligibility.

