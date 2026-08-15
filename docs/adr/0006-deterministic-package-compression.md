# ADR-0006: Deterministic Package Compression

## Status

Accepted by implementation assignment

## Decision

This ADR freezes section-27 decision 10.

`.tbdiag` v1 uses ZIP method `STORED` for every entry. Entry content is already bounded, and avoiding DEFLATE removes compressor-version variance, expansion work, and crash/recovery complexity. CRC-32, sizes, canonical order, normalized paths, and deterministic DOS timestamps are written before approval. ZIP64, data descriptors, comments, unknown extras, nesting, and encryption are prohibited.

The canonical CBOR manifest identifies package format v1, package-record ABI v1,
and the exact generated schema fingerprint. Readers reject unsupported versions,
schema fingerprints, non-canonical CBOR, and non-canonical ZIP metadata before
decoding records. A checked-in package produced by Kotlin is compared byte for
byte in Kotlin and parsed, validated, and decoded by Rust so the writer and
offline reader cannot drift independently.

The default plaintext limit remains 64 MiB, the hard limit 128 MiB, and the entry limit 128.

## Rationale

`STORED` is a valid deterministic compression choice and is the most reproducible, bounded, and reviewable option. It does not weaken privacy or expand package eligibility.
