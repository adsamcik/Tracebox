//! Shared v1 format vectors. Parsing and validation are deliberately Phase 5 work.

/// RFC 8949 deterministic CBOR for `{"a": 1}`.
pub const CANONICAL_CBOR_SINGLE_MAP: [u8; 4] = [0xa1, 0x61, 0x61, 0x01];

/// The bounded CBOR values needed by v1 manifest vectors.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CanonicalCborValue {
    /// A non-negative integer.
    Unsigned(u64),
    /// A UTF-8 text string.
    Text(String),
    /// A finite map. Keys are encoded and sorted by RFC 8949 deterministic ordering.
    Map(Vec<(String, CanonicalCborValue)>),
}

/// Encodes finite v1 CBOR using RFC 8949 deterministic map ordering and shortest-form integers.
#[must_use]
pub fn encode_canonical_cbor(value: &CanonicalCborValue) -> Vec<u8> {
    let mut output = Vec::new();
    write_value(value, &mut output);
    output
}

#[must_use]
pub fn canonical_single_map_vector() -> [u8; 4] {
    CANONICAL_CBOR_SINGLE_MAP
}

fn write_value(value: &CanonicalCborValue, output: &mut Vec<u8>) {
    match value {
        CanonicalCborValue::Unsigned(value) => write_head(0, *value, output),
        CanonicalCborValue::Text(value) => {
            let bytes = value.as_bytes();
            write_head(3, bytes.len() as u64, output);
            output.extend_from_slice(bytes);
        }
        CanonicalCborValue::Map(entries) => {
            let mut encoded_keys: Vec<_> = entries
                .iter()
                .map(|(key, value)| (encode_canonical_cbor(&CanonicalCborValue::Text(key.clone())), value))
                .collect();
            encoded_keys.sort_by(|left, right| {
                left.0.len().cmp(&right.0.len()).then_with(|| left.0.cmp(&right.0))
            });
            write_head(5, encoded_keys.len() as u64, output);
            for (key, value) in encoded_keys {
                output.extend_from_slice(&key);
                write_value(value, output);
            }
        }
    }
}

fn write_head(major: u8, value: u64, output: &mut Vec<u8>) {
    match value {
        0..=23 => output.push((major << 5) | value as u8),
        24..=0xff => output.extend_from_slice(&[(major << 5) | 24, value as u8]),
        0x100..=0xffff => output.extend_from_slice(&[
            (major << 5) | 25,
            (value >> 8) as u8,
            value as u8,
        ]),
        0x1_0000..=0xffff_ffff => output.extend_from_slice(&[
            (major << 5) | 26,
            (value >> 24) as u8,
            (value >> 16) as u8,
            (value >> 8) as u8,
            value as u8,
        ]),
        _ => output.extend_from_slice(&[
            (major << 5) | 27,
            (value >> 56) as u8,
            (value >> 48) as u8,
            (value >> 40) as u8,
            (value >> 32) as u8,
            (value >> 24) as u8,
            (value >> 16) as u8,
            (value >> 8) as u8,
            value as u8,
        ]),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_cbor_encoder_matches_kotlin_shared_golden_vector() {
        // Same fixture and literal as PackagePipelineTest.canonical_cbor_has_shared_golden_vector.
        let fixture = CanonicalCborValue::Map(vec![(
            "a".to_owned(),
            CanonicalCborValue::Unsigned(1),
        )]);
        assert_eq!(encode_canonical_cbor(&fixture), CANONICAL_CBOR_SINGLE_MAP);
    }
}
