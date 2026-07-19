//! Shared v1 format vectors. Parsing and validation are deliberately Phase 5 work.

/// RFC 8949 deterministic CBOR for `{"a": 1}`.
pub const CANONICAL_CBOR_SINGLE_MAP: [u8; 4] = [0xa1, 0x61, 0x61, 0x01];

#[must_use]
pub fn canonical_single_map_vector() -> [u8; 4] {
    CANONICAL_CBOR_SINGLE_MAP
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_cbor_golden_vector_is_stable() {
        assert_eq!(canonical_single_map_vector(), [0xa1, 0x61, 0x61, 0x01]);
    }
}
