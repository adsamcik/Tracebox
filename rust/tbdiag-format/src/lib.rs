//! Shared v1 format vectors. Parsing and validation are deliberately Phase 5 work.

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
    use std::collections::BTreeMap;
    use std::fs;
    use std::path::PathBuf;
    use std::process::Command;

    #[test]
    fn canonical_cbor_encoder_matches_actual_kotlin_encoder_output_for_shared_fixture() {
        let repository = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .parent().and_then(|path| path.parent()).expect("workspace root").to_path_buf();
        let fixture_path = repository.join("tooling/fixtures/canonical-cbor-single-map.fixture");
        let fixture: BTreeMap<_, _> = fs::read_to_string(&fixture_path)
            .expect("shared fixture")
            .lines()
            .map(|line| line.split_once('=').expect("key=value fixture"))
            .map(|(key, value)| (key.to_owned(), value.to_owned()))
            .collect();
        let rust_output = encode_canonical_cbor(&CanonicalCborValue::Map(vec![(
            fixture["key"].clone(),
            CanonicalCborValue::Unsigned(fixture["unsigned"].parse().expect("unsigned fixture")),
        )]));
        let kotlin_output = repository.join("rust/tbdiag-format/target/kotlin-canonical-cbor.bin");
        let gradlew = repository.join(if cfg!(windows) { "gradlew.bat" } else { "gradlew" });
        let status = Command::new(gradlew)
            .current_dir(&repository)
            .arg(":android:tracebox-export:testDebugUnitTest")
            .arg("--tests")
            .arg("dev.tracebox.export.PackagePipelineTest.canonical_cbor_encodes_shared_fixture_for_the_rust_cross_language_test")
            .arg("--no-daemon")
            .arg("--rerun-tasks")
            .env("TRACEBOX_SHARED_CBOR_FIXTURE", &fixture_path)
            .env("TRACEBOX_CROSS_LANGUAGE_CBOR_OUTPUT", &kotlin_output)
            .status()
            .expect("launch Kotlin fixture encoder");
        assert!(status.success(), "Kotlin fixture encoder failed: {status}");
        assert_eq!(rust_output, fs::read(kotlin_output).expect("Kotlin encoder output"));
    }
}
