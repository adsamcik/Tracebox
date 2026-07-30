# Generated Tracebox schema reference

## StructuralSummary (`1`)

Category: `crash_summary`; retention: `structural_summary`; package: `standard`; Direct Boot: `False`.

| Field ID | Field | Privacy | Type | Maximum encoded size | Transformation |
|---:|---|---|---|---:|---|
| 1 | stream_count | C0 | u32 | 5 | none |
| 2 | thread_count | C0 | u32 | 5 | none |
| 3 | module_count | C0 | u32 | 5 | none |
| 4 | exception_code | C0 | u32 | 5 | none |
| 5 | processor_architecture | C0 | u16 | 3 | none |

## EmergencyRecord (`2`)

Category: `emergency`; retention: `emergency_reserve`; package: `minimal_crash`; Direct Boot: `True`.

| Field ID | Field | Privacy | Type | Maximum encoded size | Transformation |
|---:|---|---|---|---:|---|
| 1 | slot_sequence | C0 | u64 | 10 | none |
| 2 | policy_epoch | C0 | u64 | 10 | none |
| 3 | signal_number | C0 | i32 | 5 | none |
| 4 | signal_code | C0 | i32 | 5 | none |
| 5 | process_role | C0 | enum | 5 | none |
| 6 | thread_role | C0 | enum | 5 | none |
| 7 | flags | C0 | u64 | 10 | none |

## Breadcrumb (`3`)

Category: `breadcrumb`; retention: `ordinary`; package: `standard`; Direct Boot: `False`.

| Field ID | Field | Privacy | Type | Maximum encoded size | Transformation |
|---:|---|---|---|---:|---|
| 1 | code | C1 | enum | 5 | none |
| 2 | monotonic_time_ns | C1 | u64 | 10 | none |

## HandledError (`4`)

Category: `handled_error`; retention: `ordinary`; package: `standard`; Direct Boot: `False`.

| Field ID | Field | Privacy | Type | Maximum encoded size | Transformation |
|---:|---|---|---|---:|---|
| 1 | kind | C1 | enum | 5 | none |
| 2 | frame_count | C1 | u16 | 3 | none |

## ManagedCrash (`5`)

Category: `managed_crash`; retention: `structural_summary`; package: `standard`; Direct Boot: `False`.

| Field ID | Field | Privacy | Type | Maximum encoded size | Transformation |
|---:|---|---|---|---:|---|
| 1 | primary_exception_code | C1 | fixed32 | 5 | none |
| 2 | cause_count | C0 | u16 | 3 | none |
| 3 | frame_count | C0 | u16 | 3 | none |
| 4 | flags | C0 | u32 | 5 | none |

## RustPanic (`6`)

Category: `rust_panic`; retention: `structural_summary`; package: `standard`; Direct Boot: `False`.

| Field ID | Field | Privacy | Type | Maximum encoded size | Transformation |
|---:|---|---|---|---:|---|
| 1 | payload_class | C0 | enum | 5 | none |
| 2 | thread_role | C0 | enum | 5 | none |
| 3 | location_code | C1 | fixed32 | 5 | none |
| 4 | flags | C0 | u32 | 5 | none |

## AnrCandidate (`7`)

Category: `anr_candidate`; retention: `structural_summary`; package: `standard`; Direct Boot: `False`.

| Field ID | Field | Privacy | Type | Maximum encoded size | Transformation |
|---:|---|---|---|---:|---|
| 1 | elapsed_millis | C0 | u32 | 5 | none |
| 2 | sample_count | C0 | u16 | 3 | none |
| 3 | frame_count | C0 | u16 | 3 | none |
| 4 | nonfatal_result | C0 | enum | 5 | none |
| 5 | flags | C0 | u32 | 5 | none |

## OsExit (`8`)

Category: `os_exit`; retention: `structural_summary`; package: `standard`; Direct Boot: `False`.

| Field ID | Field | Privacy | Type | Maximum encoded size | Transformation |
|---:|---|---|---|---:|---|
| 1 | reason | C0 | i32 | 5 | none |
| 2 | status | C0 | i32 | 5 | none |
| 3 | importance | C0 | i32 | 5 | none |
| 4 | link_confidence | C0 | enum | 5 | none |
| 5 | artifact_state | C0 | enum | 5 | none |

