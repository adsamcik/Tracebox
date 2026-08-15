# ADR-0006: Deterministic Package Compression

## Status

Accepted by implementation assignment

## Decision

This ADR freezes section-27 decision 10.

`.tbdiag` v1 uses ZIP method `STORED` for every entry. Entry content is already bounded, and avoiding DEFLATE removes compressor-version variance, expansion work, and crash/recovery complexity. CRC-32, sizes, canonical order, normalized paths, and deterministic DOS timestamps are written before approval. ZIP64, data descriptors, comments, unknown extras, nesting, and encryption are prohibited.

The default plaintext limit remains 64 MiB, the hard limit 128 MiB, and the entry limit 128.

## Rationale

`STORED` is a valid deterministic compression choice and is the most reproducible, bounded, and reviewable option. It does not weaken privacy or expand package eligibility.

