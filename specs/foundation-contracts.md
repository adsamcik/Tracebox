# Foundation Contracts

## Terminology and evidence

The terms in architecture section 5 are normative. Evidence grades are:

| Grade | Meaning |
|---|---|
| E0 | Static declaration or source scan only |
| E1 | Host unit/integration execution |
| E2 | Android emulator execution with captured artifacts |
| E3 | Additional physical-device execution (advisory) |
| E4 | Independent external certification evidence |

An E0/E1 result cannot satisfy a required E2 result. E3 is advisory under
ADR-0009. A watchdog result is always a stall observation or candidate; only
`ApplicationExitInfo.REASON_ANR` confirms an ANR on API 30+.

## Privacy

Classes C0, C1, C2, and Prohibited from architecture section 6 are closed categories. Every field has a stable numeric ID, semantic type, encoded maximum, retention rule, Direct Boot eligibility, package visibility, and transformation. Unknown recording fields are rejected. Prohibited concepts have no API.

## Internal identities

The complete identity table in architecture section 10.3 is normative. Random identities are 256 bits except the 128-bit OS correlation token. Randomness failure rejects durable-object creation. Internal identities are stripped or replaced with deterministic package-local numbers. Known binary and documented textual encodings are scanned before raw export. For the Phase 0 256-bit process identity, the required known encodings are the 32 raw bytes, contiguous lowercase and uppercase hexadecimal, and RFC 4648 standard and URL-safe Base64 with and without padding; duplicate byte representations are scanned once.

## Ordinary segment wire format

All integers are little-endian. Segment headers are 256 bytes and frames are bounded to 16 KiB.

```text
header: magic[8], version:u32, header_size:u32, segment_id[32],
process_instance_id[32], schema_fingerprint[32], policy_epoch:u64,
flags:u64, reserved[124], crc32c:u32

frame: encoded_length:u32, record_type:u32, sequence:u64,
policy_epoch:u64, payload[0..16348], crc32c:u32

seal: magic[8], final_sequence:u64, frame_count:u64,
content_sha256[32], crc32c:u32
```

Lengths are validated before allocation. A reader stops at the last complete CRC-valid frame. A seal is immutable.

## Public API policy

Only generated Kotlin value/event types, generated C structs/functions, and generated Rust wrappers may record. There is no arbitrary event name, map, label, `Any`, object serialization, formatting, implicit `toString()`, attachment, or free-form log API. Production approval tokens have no public constructor.

## Native ABI

Every public struct begins with `struct_size:u32` and `abi_version:u32`. Symbols carry `_v1`; structs are append-only; pointer/length pairs have generated maxima; status values are typed. No exception or Rust panic crosses C, JNI, signal, or system boundaries.

## Crashpad and handler

ADRs 0002 and 0003 are normative. Every Crashpad source archive is authenticated against a locked byte size and SHA-256 and all entries are proven relative, non-link, non-device regular files or directories before any destination write; extracted and post-patch trees remain independently hash-verified. Raw minidumps are C2 quarantine objects. Structural summaries are canonical and ID-free in their body. The handler is one private blocked process with no uploader, polling, watchdog, network dependency, or ordinary writer.

## Policy and deletion

ADR-0004 and architecture sections 8.5 and 11.5 are normative. Tightening is restrictive before success is reported. Disabled and selective deletion never report success while accessible in-scope Tracebox-owned data remains.

## Package and build identity

`.tbdiag` v1 limits and rejection rules in architecture section 15.2 are normative, with ADR-0006 selecting `STORED`. Build identity is the tuple of schema fingerprint, application ID/version, variant, R8 mapping hash/ID, ABI, ELF build IDs, Crashpad pin/patch-set hash, Rust lock hash, and dependency-verification hash. Symbolication requires an exact tuple match.
