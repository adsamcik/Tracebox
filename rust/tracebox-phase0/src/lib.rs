pub const EMERGENCY_RECORD_SIZE: usize = 256;
pub const EMERGENCY_MAGIC: &[u8; 8] = b"TBEMERG1";
pub const MAX_MINIDUMP_STREAMS: usize = 128;
pub const PROCESS_INSTANCE_ID_SIZE: usize = 32;

const EMERGENCY_RECORD_SIZE_U32: u32 = 256;
const REQUIRED_MINIDUMP_STREAMS: [u32; 5] = [3, 4, 6, 7, 15];
const THREAD_ENTRY_SIZE: usize = 48;
const MODULE_ENTRY_SIZE: usize = 108;
const MEMORY_DESCRIPTOR_SIZE: usize = 16;
const EXCEPTION_STREAM_SIZE: usize = 168;
const SYSTEM_INFO_STREAM_SIZE: usize = 56;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EmergencyRecordError {
    InvalidSize,
    InvalidMagic,
    InvalidVersion,
    InvalidDeclaredSize,
    NonZeroReserved,
    Incomplete,
    InvalidChecksum,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MinidumpError {
    Truncated,
    InvalidSignature,
    TooManyStreams,
    InvalidDirectory,
    InvalidStream,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PrivacyScanError {
    MissingSeed,
    InvalidIdentitySize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MinidumpStream {
    pub stream_type: u32,
    pub name: &'static str,
    pub size: u32,
    pub rva: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MinidumpSummary {
    pub streams: Vec<MinidumpStream>,
    pub thread_count: Option<u32>,
    pub module_count: Option<u32>,
    pub memory_range_count: Option<u32>,
    pub exception_code: Option<u32>,
    pub processor_architecture: Option<u16>,
    pub unexpected_stream_types: Vec<u32>,
    pub duplicate_stream_types: Vec<u32>,
    pub missing_required_stream_types: Vec<u32>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PrivacyScanResult {
    pub raw_seed_matches: usize,
    pub summary_seed_matches: usize,
    pub raw_identity_matches: usize,
    pub summary_identity_matches: usize,
    pub identity_encodings_scanned: usize,
}

/// Validates the fixed structural fields of one emergency record.
///
/// # Errors
///
/// Returns the first structural error found. It does not accept partial records.
pub fn validate_emergency_record(record: &[u8]) -> Result<(), EmergencyRecordError> {
    if record.len() != EMERGENCY_RECORD_SIZE {
        return Err(EmergencyRecordError::InvalidSize);
    }

    if &record[0..8] != EMERGENCY_MAGIC {
        return Err(EmergencyRecordError::InvalidMagic);
    }
    if u32::from_le_bytes([record[8], record[9], record[10], record[11]]) != 1 {
        return Err(EmergencyRecordError::InvalidVersion);
    }
    if u32::from_le_bytes([record[12], record[13], record[14], record[15]])
        != EMERGENCY_RECORD_SIZE_U32
    {
        return Err(EmergencyRecordError::InvalidDeclaredSize);
    }
    if record[128..244].iter().any(|byte| *byte != 0) {
        return Err(EmergencyRecordError::NonZeroReserved);
    }
    let marker = u64::from_le_bytes([
        record[248],
        record[249],
        record[250],
        record[251],
        record[252],
        record[253],
        record[254],
        record[255],
    ]);
    if marker != 0x5442_454d_434f_4d50 {
        return Err(EmergencyRecordError::Incomplete);
    }
    let expected = u32::from_le_bytes([record[244], record[245], record[246], record[247]]);
    if crc32c(&record[0..244]) != expected {
        return Err(EmergencyRecordError::InvalidChecksum);
    }
    Ok(())
}

/// Parses a bounded structural summary from a Crashpad minidump.
///
/// The returned values contain no minidump strings, memory bytes, registers,
/// pointers, paths, annotations, or Tracebox internal identities.
///
/// # Errors
///
/// Returns an error for invalid signatures, bounds, directories, or stream
/// counts above [`MAX_MINIDUMP_STREAMS`].
pub fn summarize_minidump(bytes: &[u8]) -> Result<MinidumpSummary, MinidumpError> {
    if bytes.len() < 32 {
        return Err(MinidumpError::Truncated);
    }
    if &bytes[0..4] != b"MDMP" {
        return Err(MinidumpError::InvalidSignature);
    }
    let stream_count =
        usize::try_from(read_u32(bytes, 8)?).map_err(|_| MinidumpError::TooManyStreams)?;
    if stream_count > MAX_MINIDUMP_STREAMS {
        return Err(MinidumpError::TooManyStreams);
    }
    let directory =
        usize::try_from(read_u32(bytes, 12)?).map_err(|_| MinidumpError::InvalidDirectory)?;
    let directory_bytes = stream_count
        .checked_mul(12)
        .ok_or(MinidumpError::InvalidDirectory)?;
    checked_range(bytes, directory, directory_bytes)
        .map_err(|_| MinidumpError::InvalidDirectory)?;

    let mut summary = MinidumpSummary {
        streams: Vec::with_capacity(stream_count),
        thread_count: None,
        module_count: None,
        memory_range_count: None,
        exception_code: None,
        processor_architecture: None,
        unexpected_stream_types: Vec::new(),
        duplicate_stream_types: Vec::new(),
        missing_required_stream_types: Vec::new(),
    };

    for index in 0..stream_count {
        let entry = directory + index * 12;
        let stream_type = read_u32(bytes, entry)?;
        let size = read_u32(bytes, entry + 4)?;
        let rva = read_u32(bytes, entry + 8)?;
        let stream_start = usize::try_from(rva).map_err(|_| MinidumpError::InvalidStream)?;
        let stream_size = usize::try_from(size).map_err(|_| MinidumpError::InvalidStream)?;
        let stream = checked_range(bytes, stream_start, stream_size)?;
        validate_interpreted_stream(stream_type, stream)?;
        if !is_allowed_stream(stream_type) {
            summary.unexpected_stream_types.push(stream_type);
        }
        if summary
            .streams
            .iter()
            .any(|stream| stream.stream_type == stream_type)
        {
            summary.duplicate_stream_types.push(stream_type);
        }

        summary.streams.push(MinidumpStream {
            stream_type,
            name: stream_name(stream_type),
            size,
            rva,
        });
        match stream_type {
            3 => summary.thread_count = Some(read_u32(stream, 0)?),
            4 => summary.module_count = Some(read_u32(stream, 0)?),
            5 => summary.memory_range_count = Some(read_u32(stream, 0)?),
            6 => {
                summary.exception_code = Some(read_u32(stream, 8)?);
            }
            7 => {
                summary.processor_architecture = Some(u16::from_le_bytes([stream[0], stream[1]]));
            }
            _ => {}
        }
    }
    summary.missing_required_stream_types = REQUIRED_MINIDUMP_STREAMS
        .into_iter()
        .filter(|required| {
            !summary
                .streams
                .iter()
                .any(|stream| stream.stream_type == *required)
        })
        .collect();
    Ok(summary)
}

impl MinidumpSummary {
    #[must_use]
    pub fn stream_profile_valid(&self) -> bool {
        self.unexpected_stream_types.is_empty()
            && self.duplicate_stream_types.is_empty()
            && self.missing_required_stream_types.is_empty()
    }
}

/// Serializes the exact allowlisted structural summary inspected by Phase 0.
///
/// The output contains no raw minidump bytes, strings, paths, annotations, or
/// internal identities. Privacy scans must run against these exact bytes.
#[must_use]
pub fn serialize_structural_summary(summary: &MinidumpSummary) -> String {
    use std::fmt::Write as _;

    let mut output = String::new();
    writeln!(&mut output, "{{").expect("writing to String cannot fail");
    writeln!(
        &mut output,
        "  \"stream_profile_valid\": {},",
        summary.stream_profile_valid()
    )
    .expect("writing to String cannot fail");
    writeln!(
        &mut output,
        "  \"stream_count\": {},",
        summary.streams.len()
    )
    .expect("writing to String cannot fail");
    writeln!(&mut output, "  \"streams\": [").expect("writing to String cannot fail");
    for (index, stream) in summary.streams.iter().enumerate() {
        let comma = if index + 1 == summary.streams.len() {
            ""
        } else {
            ","
        };
        writeln!(
            &mut output,
            "    {{\"type\": {}, \"name\": \"{}\", \"size\": {}}}{}",
            stream.stream_type, stream.name, stream.size, comma
        )
        .expect("writing to String cannot fail");
    }
    writeln!(&mut output, "  ],").expect("writing to String cannot fail");
    write_u32_array(
        &mut output,
        "unexpected_stream_types",
        &summary.unexpected_stream_types,
    );
    write_u32_array(
        &mut output,
        "duplicate_stream_types",
        &summary.duplicate_stream_types,
    );
    write_u32_array(
        &mut output,
        "missing_required_stream_types",
        &summary.missing_required_stream_types,
    );
    write_optional(
        &mut output,
        "thread_count",
        summary.thread_count.map(u64::from),
    );
    write_optional(
        &mut output,
        "module_count",
        summary.module_count.map(u64::from),
    );
    write_optional(
        &mut output,
        "memory_range_count",
        summary.memory_range_count.map(u64::from),
    );
    write_optional(
        &mut output,
        "exception_code",
        summary.exception_code.map(u64::from),
    );
    write_optional(
        &mut output,
        "processor_architecture",
        summary.processor_architecture.map(u64::from),
    );
    output.push_str("  \"structural_summary_format\": 1\n");
    output.push('}');
    output
}

/// Scans raw bytes and the exact serialized structural summary for the seeded
/// value and every known encoding of one established live process identity.
///
/// # Errors
///
/// Returns an error instead of producing vacuous zeroes when the seed or live
/// 256-bit identity is absent.
pub fn scan_privacy(
    raw: &[u8],
    serialized_summary: &[u8],
    seed: &[u8],
    identity: &[u8],
) -> Result<PrivacyScanResult, PrivacyScanError> {
    if seed.is_empty() {
        return Err(PrivacyScanError::MissingSeed);
    }
    if identity.len() != PROCESS_INSTANCE_ID_SIZE {
        return Err(PrivacyScanError::InvalidIdentitySize);
    }
    let identity_encodings = identity_encodings(identity);
    Ok(PrivacyScanResult {
        raw_seed_matches: count_occurrences(raw, seed),
        summary_seed_matches: count_occurrences(serialized_summary, seed),
        raw_identity_matches: count_encoding_occurrences(raw, &identity_encodings),
        summary_identity_matches: count_encoding_occurrences(
            serialized_summary,
            &identity_encodings,
        ),
        identity_encodings_scanned: identity_encodings.len(),
    })
}

#[must_use]
pub fn count_occurrences(bytes: &[u8], needle: &[u8]) -> usize {
    if needle.is_empty() || needle.len() > bytes.len() {
        return 0;
    }
    bytes
        .windows(needle.len())
        .filter(|window| *window == needle)
        .count()
}

fn write_u32_array(output: &mut String, name: &str, values: &[u32]) {
    use std::fmt::Write as _;

    write!(output, "  \"{name}\": [").expect("writing to String cannot fail");
    for (index, value) in values.iter().enumerate() {
        if index != 0 {
            output.push_str(", ");
        }
        write!(output, "{value}").expect("writing to String cannot fail");
    }
    output.push_str("],\n");
}

fn write_optional(output: &mut String, name: &str, value: Option<u64>) {
    use std::fmt::Write as _;

    match value {
        Some(number) => {
            writeln!(output, "  \"{name}\": {number},").expect("writing to String cannot fail");
        }
        None => {
            writeln!(output, "  \"{name}\": null,").expect("writing to String cannot fail");
        }
    }
}

fn validate_interpreted_stream(stream_type: u32, stream: &[u8]) -> Result<(), MinidumpError> {
    match stream_type {
        3 => validate_counted_list(stream, THREAD_ENTRY_SIZE),
        4 => validate_counted_list(stream, MODULE_ENTRY_SIZE),
        5 => validate_counted_list(stream, MEMORY_DESCRIPTOR_SIZE),
        6 if stream.len() < EXCEPTION_STREAM_SIZE => Err(MinidumpError::InvalidStream),
        7 if stream.len() < SYSTEM_INFO_STREAM_SIZE => Err(MinidumpError::InvalidStream),
        _ => Ok(()),
    }
}

fn validate_counted_list(stream: &[u8], entry_size: usize) -> Result<(), MinidumpError> {
    let declared_count = read_u32(stream, 0).map_err(|_| MinidumpError::InvalidStream)?;
    let count = usize::try_from(declared_count).map_err(|_| MinidumpError::InvalidStream)?;
    let entries_size = count
        .checked_mul(entry_size)
        .ok_or(MinidumpError::InvalidStream)?;
    let required_size = 4_usize
        .checked_add(entries_size)
        .ok_or(MinidumpError::InvalidStream)?;
    if stream.len() < required_size {
        return Err(MinidumpError::InvalidStream);
    }
    Ok(())
}

const fn is_allowed_stream(stream_type: u32) -> bool {
    matches!(
        stream_type,
        3 | 4 | 6 | 7 | 14 | 15 | 0x4767_0003 | 0x4767_0004 | 0x4767_0005 | 0x4767_0009
    )
}

fn count_encoding_occurrences(bytes: &[u8], encodings: &[Vec<u8>]) -> usize {
    encodings
        .iter()
        .map(|encoding| count_occurrences(bytes, encoding))
        .sum()
}

fn identity_encodings(identity: &[u8]) -> Vec<Vec<u8>> {
    let candidates = [
        identity.to_vec(),
        hex_encode(identity, b"0123456789abcdef"),
        hex_encode(identity, b"0123456789ABCDEF"),
        base64_encode(
            identity,
            b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/",
            true,
        ),
        base64_encode(
            identity,
            b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/",
            false,
        ),
        base64_encode(
            identity,
            b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_",
            true,
        ),
        base64_encode(
            identity,
            b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_",
            false,
        ),
    ];
    let mut encodings = Vec::with_capacity(candidates.len());
    for candidate in candidates {
        if !encodings.contains(&candidate) {
            encodings.push(candidate);
        }
    }
    encodings
}

fn hex_encode(bytes: &[u8], alphabet: &[u8; 16]) -> Vec<u8> {
    let mut encoded = Vec::with_capacity(bytes.len() * 2);
    for byte in bytes {
        encoded.push(alphabet[usize::from(byte >> 4)]);
        encoded.push(alphabet[usize::from(byte & 0x0f)]);
    }
    encoded
}

fn base64_encode(bytes: &[u8], alphabet: &[u8; 64], padded: bool) -> Vec<u8> {
    let mut encoded = Vec::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let first = chunk[0];
        let second = chunk.get(1).copied().unwrap_or(0);
        let third = chunk.get(2).copied().unwrap_or(0);
        encoded.push(alphabet[usize::from(first >> 2)]);
        encoded.push(alphabet[usize::from(((first & 0x03) << 4) | (second >> 4))]);
        if chunk.len() >= 2 {
            encoded.push(alphabet[usize::from(((second & 0x0f) << 2) | (third >> 6))]);
        } else if padded {
            encoded.push(b'=');
        }
        if chunk.len() == 3 {
            encoded.push(alphabet[usize::from(third & 0x3f)]);
        } else if padded {
            encoded.push(b'=');
        }
    }
    encoded
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, MinidumpError> {
    let range = checked_range(bytes, offset, 4)?;
    Ok(u32::from_le_bytes([range[0], range[1], range[2], range[3]]))
}

fn checked_range(bytes: &[u8], offset: usize, length: usize) -> Result<&[u8], MinidumpError> {
    let end = offset
        .checked_add(length)
        .ok_or(MinidumpError::InvalidStream)?;
    bytes.get(offset..end).ok_or(MinidumpError::Truncated)
}

fn crc32c(bytes: &[u8]) -> u32 {
    let mut crc = u32::MAX;
    for byte in bytes {
        crc ^= u32::from(*byte);
        for _ in 0..8 {
            let mask = 0_u32.wrapping_sub(crc & 1);
            crc = (crc >> 1) ^ (0x82f6_3b78 & mask);
        }
    }
    !crc
}

const fn stream_name(stream_type: u32) -> &'static str {
    match stream_type {
        3 => "ThreadListStream",
        4 => "ModuleListStream",
        5 => "MemoryListStream",
        6 => "ExceptionStream",
        7 => "SystemInfoStream",
        12 => "HandleDataStream",
        14 => "UnloadedModuleListStream",
        15 => "MiscInfoStream",
        16 => "MemoryInfoListStream",
        24 => "ThreadNamesStream",
        0x4350_0001 => "CrashpadInfoStream",
        0x4767_0003 => "LinuxCpuInfoStream",
        0x4767_0004 => "LinuxProcStatusStream",
        0x4767_0005 => "LinuxLsbReleaseStream",
        0x4767_0009 => "LinuxMappingsStream",
        _ => "UnknownStream",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_complete_record() {
        let mut record = [0_u8; EMERGENCY_RECORD_SIZE];
        record[0..8].copy_from_slice(EMERGENCY_MAGIC);
        record[8..12].copy_from_slice(&1_u32.to_le_bytes());
        record[12..16].copy_from_slice(
            &u32::try_from(EMERGENCY_RECORD_SIZE)
                .expect("record size fits u32")
                .to_le_bytes(),
        );
        record[248..256].copy_from_slice(&0x5442_454d_434f_4d50_u64.to_le_bytes());
        let checksum = crc32c(&record[0..244]);
        record[244..248].copy_from_slice(&checksum.to_le_bytes());
        assert_eq!(validate_emergency_record(&record), Ok(()));
    }

    #[test]
    fn rejects_truncated_record() {
        assert_eq!(
            validate_emergency_record(&[0_u8; 255]),
            Err(EmergencyRecordError::InvalidSize)
        );
    }

    #[test]
    fn inventories_bounded_minidump() {
        let mut threads = vec![0_u8; 4 + 2 * THREAD_ENTRY_SIZE];
        threads[0..4].copy_from_slice(&2_u32.to_le_bytes());
        let mut modules = vec![0_u8; 4 + MODULE_ENTRY_SIZE];
        modules[0..4].copy_from_slice(&1_u32.to_le_bytes());
        let mut exception = vec![0_u8; EXCEPTION_STREAM_SIZE];
        exception[8..12].copy_from_slice(&0xdead_beef_u32.to_le_bytes());
        let mut system_info = vec![0_u8; SYSTEM_INFO_STREAM_SIZE];
        system_info[0..2].copy_from_slice(&9_u16.to_le_bytes());
        let bytes = minidump_with_streams(&[
            (3, threads),
            (4, modules),
            (6, exception),
            (7, system_info),
            (15, Vec::new()),
        ]);

        let summary = summarize_minidump(&bytes).expect("valid minidump");
        assert_eq!(summary.streams[0].name, "ThreadListStream");
        assert_eq!(summary.thread_count, Some(2));
        assert_eq!(summary.module_count, Some(1));
        assert_eq!(summary.exception_code, Some(0xdead_beef));
        assert_eq!(summary.processor_architecture, Some(9));
        assert!(summary.stream_profile_valid());
    }

    #[test]
    fn rejects_excessive_stream_count() {
        let mut bytes = [0_u8; 32];
        bytes[0..4].copy_from_slice(b"MDMP");
        bytes[8..12].copy_from_slice(&129_u32.to_le_bytes());
        assert_eq!(
            summarize_minidump(&bytes),
            Err(MinidumpError::TooManyStreams)
        );
    }

    #[test]
    fn rejects_every_truncated_emergency_boundary() {
        let mut record = [0_u8; EMERGENCY_RECORD_SIZE];
        record[0..8].copy_from_slice(EMERGENCY_MAGIC);
        record[8..12].copy_from_slice(&1_u32.to_le_bytes());
        record[12..16].copy_from_slice(
            &u32::try_from(EMERGENCY_RECORD_SIZE)
                .expect("record size fits u32")
                .to_le_bytes(),
        );
        record[248..256].copy_from_slice(&0x5442_454d_434f_4d50_u64.to_le_bytes());
        let checksum = crc32c(&record[0..244]);
        record[244..248].copy_from_slice(&checksum.to_le_bytes());
        for length in 0..EMERGENCY_RECORD_SIZE {
            assert!(validate_emergency_record(&record[..length]).is_err());
        }
    }

    #[test]
    fn minidump_corruption_smoke_is_bounded() {
        let mut threads = vec![0_u8; 4 + THREAD_ENTRY_SIZE];
        threads[0..4].copy_from_slice(&1_u32.to_le_bytes());
        let mut bytes = minidump_with_streams(&[(3, threads)]);

        let mut state = 0x9e37_79b9_u32;
        for _ in 0..10_000 {
            state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            let index = usize::try_from(state).expect("u32 fits usize") % bytes.len();
            let original = bytes[index];
            bytes[index] ^= (state >> 24) as u8 | 1;
            let _ = summarize_minidump(&bytes);
            bytes[index] = original;
        }
    }

    #[test]
    fn rejects_each_fixed_emergency_field_flip() {
        let valid = valid_emergency_record();
        for range in [0..8, 8..12, 12..16, 128..244, 244..248, 248..256] {
            let mut record = valid;
            record[range.start] ^= 1;
            if range.start < 244 {
                let checksum = crc32c(&record[0..244]);
                record[244..248].copy_from_slice(&checksum.to_le_bytes());
            }
            assert!(validate_emergency_record(&record).is_err(), "{range:?}");
        }
    }

    #[test]
    fn rejects_every_incomplete_interpreted_stream_extent() {
        for (stream_type, complete_size) in [
            (3_u32, 4),
            (4, 4),
            (5, 4),
            (6, EXCEPTION_STREAM_SIZE),
            (7, SYSTEM_INFO_STREAM_SIZE),
        ] {
            for declared_size in 0..complete_size {
                let bytes = single_stream_minidump(stream_type, declared_size, complete_size, None);
                assert_eq!(
                    summarize_minidump(&bytes),
                    Err(MinidumpError::InvalidStream),
                    "type={stream_type} declared={declared_size}"
                );
            }
            let bytes = single_stream_minidump(stream_type, complete_size, complete_size, None);
            summarize_minidump(&bytes)
                .unwrap_or_else(|error| panic!("type={stream_type} exact boundary: {error:?}"));
        }
    }

    #[test]
    fn validates_counted_list_extents_against_declared_length() {
        for (stream_type, entry_size) in [
            (3_u32, THREAD_ENTRY_SIZE),
            (4, MODULE_ENTRY_SIZE),
            (5, MEMORY_DESCRIPTOR_SIZE),
        ] {
            let required_size = 4 + 2 * entry_size;
            for declared_size in 4..required_size {
                let bytes =
                    single_stream_minidump(stream_type, declared_size, required_size, Some(2));
                assert_eq!(
                    summarize_minidump(&bytes),
                    Err(MinidumpError::InvalidStream),
                    "type={stream_type} declared={declared_size}"
                );
            }
            let bytes = single_stream_minidump(stream_type, required_size, required_size, Some(2));
            summarize_minidump(&bytes)
                .unwrap_or_else(|error| panic!("type={stream_type} exact list extent: {error:?}"));

            let undersized =
                single_stream_minidump(stream_type, required_size, required_size, Some(3));
            assert_eq!(
                summarize_minidump(&undersized),
                Err(MinidumpError::InvalidStream)
            );
            let excessive = single_stream_minidump(stream_type, 4, 4, Some(u32::MAX));
            assert_eq!(
                summarize_minidump(&excessive),
                Err(MinidumpError::InvalidStream)
            );
        }
    }

    #[test]
    fn rejects_every_file_truncation_inside_declared_stream() {
        for (stream_type, complete_size, count) in [
            (3_u32, 4 + THREAD_ENTRY_SIZE, Some(1)),
            (4, 4 + MODULE_ENTRY_SIZE, Some(1)),
            (5, 4 + MEMORY_DESCRIPTOR_SIZE, Some(1)),
            (6, EXCEPTION_STREAM_SIZE, None),
            (7, SYSTEM_INFO_STREAM_SIZE, None),
        ] {
            let complete = single_stream_minidump(stream_type, complete_size, complete_size, count);
            for length in 44..complete.len() {
                assert!(
                    summarize_minidump(&complete[..length]).is_err(),
                    "type={stream_type} length={length}"
                );
            }
        }
    }

    #[test]
    fn accepts_stream_ending_at_file_boundary() {
        let bytes = single_stream_minidump(3, 4, 4, Some(0));
        let summary = summarize_minidump(&bytes).expect("boundary stream is valid");
        assert_eq!(summary.thread_count, Some(0));
    }

    #[test]
    fn rejects_unexpected_and_duplicate_stream_profile_entries() {
        let mut bytes = vec![0_u8; 64];
        bytes[0..4].copy_from_slice(b"MDMP");
        bytes[8..12].copy_from_slice(&2_u32.to_le_bytes());
        bytes[12..16].copy_from_slice(&32_u32.to_le_bytes());
        for entry in [32_usize, 44] {
            bytes[entry..entry + 4].copy_from_slice(&24_u32.to_le_bytes());
            bytes[entry + 4..entry + 8].copy_from_slice(&0_u32.to_le_bytes());
            bytes[entry + 8..entry + 12].copy_from_slice(&56_u32.to_le_bytes());
        }
        let summary = summarize_minidump(&bytes).expect("inventory remains parseable");
        assert!(!summary.stream_profile_valid());
        assert_eq!(summary.unexpected_stream_types, vec![24, 24]);
        assert_eq!(summary.duplicate_stream_types, vec![24]);
        assert_eq!(summary.missing_required_stream_types, vec![3, 4, 6, 7, 15]);
    }

    #[test]
    fn scans_serialized_summary_and_known_identity_encodings() {
        let identity = [0x5a_u8; PROCESS_INSTANCE_ID_SIZE];
        let summary = MinidumpSummary {
            streams: Vec::new(),
            thread_count: None,
            module_count: None,
            memory_range_count: None,
            exception_code: None,
            processor_architecture: None,
            unexpected_stream_types: Vec::new(),
            duplicate_stream_types: Vec::new(),
            missing_required_stream_types: REQUIRED_MINIDUMP_STREAMS.to_vec(),
        };
        let mut serialized = serialize_structural_summary(&summary).into_bytes();
        serialized.extend_from_slice(b" TRACEBOX_SEED ");
        serialized.extend_from_slice(&hex_encode(&identity, b"0123456789abcdef"));
        let mut raw = b"TRACEBOX_SEED ".to_vec();
        raw.extend_from_slice(&identity);
        let scan = scan_privacy(&raw, &serialized, b"TRACEBOX_SEED", &identity)
            .expect("live identity permits scan");
        assert_eq!(scan.raw_seed_matches, 1);
        assert_eq!(scan.summary_seed_matches, 1);
        assert_eq!(scan.raw_identity_matches, 1);
        assert_eq!(scan.summary_identity_matches, 1);
        assert!(scan.identity_encodings_scanned >= 5);
    }

    #[test]
    fn privacy_scan_rejects_missing_live_inputs() {
        assert_eq!(
            scan_privacy(&[], &[], &[], &[0_u8; PROCESS_INSTANCE_ID_SIZE]),
            Err(PrivacyScanError::MissingSeed)
        );
        assert_eq!(
            scan_privacy(&[], &[], b"seed", &[]),
            Err(PrivacyScanError::InvalidIdentitySize)
        );
    }

    fn valid_emergency_record() -> [u8; EMERGENCY_RECORD_SIZE] {
        let mut record = [0_u8; EMERGENCY_RECORD_SIZE];
        record[0..8].copy_from_slice(EMERGENCY_MAGIC);
        record[8..12].copy_from_slice(&1_u32.to_le_bytes());
        record[12..16].copy_from_slice(
            &u32::try_from(EMERGENCY_RECORD_SIZE)
                .expect("record size fits u32")
                .to_le_bytes(),
        );
        record[248..256].copy_from_slice(&0x5442_454d_434f_4d50_u64.to_le_bytes());
        let checksum = crc32c(&record[0..244]);
        record[244..248].copy_from_slice(&checksum.to_le_bytes());
        record
    }

    fn single_stream_minidump(
        stream_type: u32,
        declared_size: usize,
        actual_size: usize,
        count: Option<u32>,
    ) -> Vec<u8> {
        let mut bytes = vec![0_u8; 44 + actual_size];
        bytes[0..4].copy_from_slice(b"MDMP");
        bytes[8..12].copy_from_slice(&1_u32.to_le_bytes());
        bytes[12..16].copy_from_slice(&32_u32.to_le_bytes());
        bytes[32..36].copy_from_slice(&stream_type.to_le_bytes());
        bytes[36..40].copy_from_slice(
            &u32::try_from(declared_size)
                .expect("test stream size fits u32")
                .to_le_bytes(),
        );
        bytes[40..44].copy_from_slice(&44_u32.to_le_bytes());
        if let Some(count) = count {
            bytes[44..48].copy_from_slice(&count.to_le_bytes());
        }
        bytes
    }

    fn minidump_with_streams(streams: &[(u32, Vec<u8>)]) -> Vec<u8> {
        let directory_size = streams.len() * 12;
        let mut bytes = vec![0_u8; 32 + directory_size];
        bytes[0..4].copy_from_slice(b"MDMP");
        bytes[8..12].copy_from_slice(
            &u32::try_from(streams.len())
                .expect("test stream count fits u32")
                .to_le_bytes(),
        );
        bytes[12..16].copy_from_slice(&32_u32.to_le_bytes());
        for (index, (stream_type, stream)) in streams.iter().enumerate() {
            let entry = 32 + index * 12;
            let rva = bytes.len();
            bytes[entry..entry + 4].copy_from_slice(&stream_type.to_le_bytes());
            bytes[entry + 4..entry + 8].copy_from_slice(
                &u32::try_from(stream.len())
                    .expect("test stream size fits u32")
                    .to_le_bytes(),
            );
            bytes[entry + 8..entry + 12].copy_from_slice(
                &u32::try_from(rva)
                    .expect("test stream RVA fits u32")
                    .to_le_bytes(),
            );
            bytes.extend_from_slice(stream);
        }
        bytes
    }
}
