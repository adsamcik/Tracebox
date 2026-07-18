pub const EMERGENCY_RECORD_SIZE: usize = 256;
pub const EMERGENCY_MAGIC: &[u8; 8] = b"TBEMERG1";
pub const MAX_MINIDUMP_STREAMS: usize = 128;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EmergencyRecordError {
    InvalidSize,
    InvalidMagic,
    InvalidVersion,
    Incomplete,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MinidumpError {
    Truncated,
    InvalidSignature,
    TooManyStreams,
    InvalidDirectory,
    InvalidStream,
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
    };

    for index in 0..stream_count {
        let entry = directory + index * 12;
        let stream_type = read_u32(bytes, entry)?;
        let size = read_u32(bytes, entry + 4)?;
        let rva = read_u32(bytes, entry + 8)?;
        let stream_start = usize::try_from(rva).map_err(|_| MinidumpError::InvalidStream)?;
        let stream_size = usize::try_from(size).map_err(|_| MinidumpError::InvalidStream)?;
        checked_range(bytes, stream_start, stream_size)?;

        summary.streams.push(MinidumpStream {
            stream_type,
            name: stream_name(stream_type),
            size,
            rva,
        });
        match stream_type {
            3 => summary.thread_count = Some(read_u32(bytes, stream_start)?),
            4 => summary.module_count = Some(read_u32(bytes, stream_start)?),
            5 => summary.memory_range_count = Some(read_u32(bytes, stream_start)?),
            6 if stream_size >= 12 => {
                summary.exception_code = Some(read_u32(bytes, stream_start + 8)?);
            }
            7 if stream_size >= 2 => {
                summary.processor_architecture = Some(u16::from_le_bytes([
                    bytes[stream_start],
                    bytes[stream_start + 1],
                ]));
            }
            _ => {}
        }
    }
    Ok(summary)
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
        record[248..256].copy_from_slice(&0x5442_454d_434f_4d50_u64.to_le_bytes());
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
        let mut bytes = vec![0_u8; 48];
        bytes[0..4].copy_from_slice(b"MDMP");
        bytes[8..12].copy_from_slice(&1_u32.to_le_bytes());
        bytes[12..16].copy_from_slice(&32_u32.to_le_bytes());
        bytes[32..36].copy_from_slice(&3_u32.to_le_bytes());
        bytes[36..40].copy_from_slice(&4_u32.to_le_bytes());
        bytes[40..44].copy_from_slice(&44_u32.to_le_bytes());
        bytes[44..48].copy_from_slice(&2_u32.to_le_bytes());

        let summary = summarize_minidump(&bytes).expect("valid minidump");
        assert_eq!(summary.streams[0].name, "ThreadListStream");
        assert_eq!(summary.thread_count, Some(2));
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
}
