//! Offline, bounded `.tbdiag` archive validation and exact-identity symbol resolution.
//!
//! v1 accepts only the deterministic STORED ZIP shape emitted by Tracebox.  Rejecting
//! unsupported compression is intentional: it keeps every declared size checked before
//! allocating or copying attacker-controlled bytes.
use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::fs::File;
use std::io::Read;
use std::path::Path;

pub const MAX_ENTRIES: usize = 128;
pub const MAX_TOTAL_UNCOMPRESSED: u64 = 128 * 1024 * 1024;
pub const MAX_ARCHIVE_BYTES: u64 = 128 * 1024 * 1024;
pub const MAX_ENTRY_NAME_BYTES: usize = 4096;
const END_OF_CENTRAL_DIRECTORY: u32 = 0x0605_4b50;
const CENTRAL_DIRECTORY_HEADER: u32 = 0x0201_4b50;
const LOCAL_FILE_HEADER: u32 = 0x0403_4b50;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ArchiveEntry { pub name: String, pub bytes: Vec<u8> }

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Archive { pub entries: Vec<ArchiveEntry> }

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ArchiveError {
    Io(String), ArchiveTooLarge(u64), Truncated, InvalidZip(&'static str), TooManyEntries(u16),
    EntryTooLarge(u64), TotalTooLarge(u64), UnsupportedCompression(u16), InvalidEntryName,
    DuplicateEntry, InvalidUtf8Name, InvalidLocalHeader, EncryptedEntry, DataDescriptorEntry,
    LocalHeaderMismatch(&'static str), CrcMismatch { declared: u32, actual: u32 },
}

impl fmt::Display for ArchiveError { fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { write!(f, "{self:?}") } }
impl std::error::Error for ArchiveError {}

/// Opens only bounded inputs. Metadata is checked before allocating the file buffer.
pub fn read_archive(path: &Path) -> Result<Archive, ArchiveError> {
    let length = std::fs::metadata(path).map_err(|error| ArchiveError::Io(error.to_string()))?.len();
    if length > MAX_ARCHIVE_BYTES { return Err(ArchiveError::ArchiveTooLarge(length)); }
    let capacity = usize::try_from(length).map_err(|_| ArchiveError::ArchiveTooLarge(length))?;
    let mut bytes = Vec::with_capacity(capacity);
    File::open(path).map_err(|error| ArchiveError::Io(error.to_string()))?
        .take(MAX_ARCHIVE_BYTES + 1).read_to_end(&mut bytes).map_err(|error| ArchiveError::Io(error.to_string()))?;
    if bytes.len() > capacity { return Err(ArchiveError::ArchiveTooLarge(bytes.len() as u64)); }
    parse_archive(&bytes)
}

/// A generated Tracebox record decoded from a finalized package entry.
///
/// The fields are numeric, schema-defined values rendered as decimal strings so this type never
/// treats package bytes as arbitrary text.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DecodedPackageRecord {
    /// Package-local deterministic entry path.
    pub path: String,
    /// Stable generated event ID.
    pub event_id: u32,
    /// Generated event name selected by the stable event ID.
    pub event_type: String,
    /// Package-local process ID.
    pub process_local_id: u32,
    /// Package-local segment ID.
    pub segment_local_id: u32,
    /// Package-local raw-artifact ID, or zero when absent.
    pub artifact_local_id: u32,
    /// Package-local record ID.
    pub record_local_id: u32,
    /// Source time carried by the package record.
    pub timestamp_millis: u64,
    /// Schema-generated bounded field names and decimal values.
    pub fields: Vec<(String, String)>,
}

/// A package record body that is not the exact v1 generated-record wire shape.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RecordDecodeError {
    /// A record entry was shorter than the fixed package-local header.
    TruncatedHeader(String),
    /// The package-local record format version is not supported.
    UnsupportedVersion(u32),
    /// The stable generated event ID is unknown.
    UnknownEventType(u32),
    /// A generated payload did not match its fixed bounded schema size.
    InvalidPayloadSize {
        event_id: u32,
        actual: usize,
        expected: usize,
    },
    /// A package-local record ID appeared more than once.
    DuplicateRecordId(u32),
    /// A command asked for an event type that the generated schema does not define.
    UnknownFilter(String),
}

impl fmt::Display for RecordDecodeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::TruncatedHeader(path) => write!(formatter, "record entry is truncated: {path}"),
            Self::UnsupportedVersion(version) => {
                write!(formatter, "unsupported package record version: {version}")
            }
            Self::UnknownEventType(event_id) => {
                write!(formatter, "unknown generated event type: {event_id}")
            }
            Self::InvalidPayloadSize {
                event_id,
                actual,
                expected,
            } => {
                write!(
                    formatter,
                    "invalid payload size for event {event_id}: {actual}, expected {expected}"
                )
            }
            Self::DuplicateRecordId(record_id) => {
                write!(formatter, "duplicate package-local record ID: {record_id}")
            }
            Self::UnknownFilter(selector) => {
                write!(formatter, "unknown generated event filter: {selector}")
            }
        }
    }
}

impl std::error::Error for RecordDecodeError {}

const PACKAGE_RECORD_HEADER_SIZE: usize = 32;

/// Decodes every generated `records/*.tbr` entry from a validated package.
///
/// Archive validation happens before this function is called. This decoder still validates each
/// bounded record shape and never interprets unknown bytes as text or a guessed schema.
///
/// # Errors
///
/// Returns an error when a generated package record is truncated, unknown, malformed, or duplicates
/// a package-local record ID.
#[allow(clippy::case_sensitive_file_extension_comparisons)]
pub fn decode_package_records(
    archive: &Archive,
) -> Result<Vec<DecodedPackageRecord>, RecordDecodeError> {
    let mut records = Vec::new();
    let mut record_ids = BTreeSet::new();
    let mut entries: Vec<_> = archive
        .entries
        .iter()
        .filter(|entry| entry.name.starts_with("records/") && entry.name.ends_with(".tbr"))
        .collect();
    entries.sort_by(|left, right| left.name.cmp(&right.name));
    for entry in entries {
        let record = decode_package_record(&entry.name, &entry.bytes)?;
        if !record_ids.insert(record.record_local_id) {
            return Err(RecordDecodeError::DuplicateRecordId(record.record_local_id));
        }
        records.push(record);
    }
    Ok(records)
}

/// Filters decoded records by one exact generated event name or stable numeric ID.
///
/// Matching is deliberately schema-defined rather than a substring search over user-controlled
/// package paths or payload values.
///
/// # Errors
///
/// Returns an error when `selector` is not a known generated event name or stable event ID.
pub fn filter_package_records(
    records: &[DecodedPackageRecord],
    selector: &str,
) -> Result<Vec<DecodedPackageRecord>, RecordDecodeError> {
    let event_id = event_id_for_selector(selector)?;
    Ok(records
        .iter()
        .filter(|record| record.event_id == event_id)
        .cloned()
        .collect())
}

/// Produces a deterministic, line-oriented representation of one decoded generated record.
#[must_use]
pub fn format_decoded_record(record: &DecodedPackageRecord) -> String {
    let fields = record
        .fields
        .iter()
        .map(|(name, value)| format!("\"{name}\":{value}"))
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "{{\"path\":\"{}\",\"event_id\":{},\"event_type\":\"{}\",\"process\":{},\"segment\":{},\"artifact\":{},\"record\":{},\"time_ms\":{},\"fields\":{{{fields}}}}}",
        record.path,
        record.event_id,
        record.event_type,
        record.process_local_id,
        record.segment_local_id,
        record.artifact_local_id,
        record.record_local_id,
        record.timestamp_millis,
    )
}

fn decode_package_record(
    path: &str,
    bytes: &[u8],
) -> Result<DecodedPackageRecord, RecordDecodeError> {
    if bytes.len() < PACKAGE_RECORD_HEADER_SIZE {
        return Err(RecordDecodeError::TruncatedHeader(path.to_owned()));
    }
    let version = be_u32(bytes, 0).expect("fixed header was checked");
    if version != 1 {
        return Err(RecordDecodeError::UnsupportedVersion(version));
    }
    let event_id = be_u32(bytes, 4).expect("fixed header was checked");
    let event_type =
        event_type_name(event_id).ok_or(RecordDecodeError::UnknownEventType(event_id))?;
    let fields = decode_generated_payload(event_id, &bytes[PACKAGE_RECORD_HEADER_SIZE..])?;
    Ok(DecodedPackageRecord {
        path: path.to_owned(),
        event_id,
        event_type: event_type.to_owned(),
        process_local_id: be_u32(bytes, 8).expect("fixed header was checked"),
        segment_local_id: be_u32(bytes, 12).expect("fixed header was checked"),
        artifact_local_id: be_u32(bytes, 16).expect("fixed header was checked"),
        record_local_id: be_u32(bytes, 20).expect("fixed header was checked"),
        timestamp_millis: be_u64(bytes, 24).expect("fixed header was checked"),
        fields,
    })
}

fn event_id_for_selector(selector: &str) -> Result<u32, RecordDecodeError> {
    let parsed = selector.parse::<u32>().ok();
    let event_id = parsed.or_else(|| {
        [1_u32, 2, 3, 4].into_iter().find(|event_id| {
            event_type_name(*event_id).is_some_and(|name| name.eq_ignore_ascii_case(selector))
        })
    });
    event_id.ok_or_else(|| RecordDecodeError::UnknownFilter(selector.to_owned()))
}

fn event_type_name(event_id: u32) -> Option<&'static str> {
    match event_id {
        1 => Some("StructuralSummary"),
        2 => Some("EmergencyRecord"),
        3 => Some("Breadcrumb"),
        4 => Some("HandledError"),
        _ => None,
    }
}

fn decode_generated_payload(
    event_id: u32,
    payload: &[u8],
) -> Result<Vec<(String, String)>, RecordDecodeError> {
    let expected = match event_id {
        1 => 18,
        2 => 40,
        3 => 12,
        4 => 6,
        _ => return Err(RecordDecodeError::UnknownEventType(event_id)),
    };
    if payload.len() != expected {
        return Err(RecordDecodeError::InvalidPayloadSize {
            event_id,
            actual: payload.len(),
            expected,
        });
    }
    let fields = match event_id {
        1 => vec![
            (
                "stream_count".into(),
                le_u32(payload, 0).expect("checked").to_string(),
            ),
            (
                "thread_count".into(),
                le_u32(payload, 4).expect("checked").to_string(),
            ),
            (
                "module_count".into(),
                le_u32(payload, 8).expect("checked").to_string(),
            ),
            (
                "exception_code".into(),
                le_u32(payload, 12).expect("checked").to_string(),
            ),
            (
                "processor_architecture".into(),
                le_u16(payload, 16).expect("checked").to_string(),
            ),
        ],
        2 => vec![
            (
                "slot_sequence".into(),
                le_u64(payload, 0).expect("checked").to_string(),
            ),
            (
                "policy_epoch".into(),
                le_u64(payload, 8).expect("checked").to_string(),
            ),
            (
                "signal_number".into(),
                le_i32(payload, 16).expect("checked").to_string(),
            ),
            (
                "signal_code".into(),
                le_i32(payload, 20).expect("checked").to_string(),
            ),
            (
                "process_role".into(),
                le_u32(payload, 24).expect("checked").to_string(),
            ),
            (
                "thread_role".into(),
                le_u32(payload, 28).expect("checked").to_string(),
            ),
            (
                "flags".into(),
                le_u64(payload, 32).expect("checked").to_string(),
            ),
        ],
        3 => vec![
            (
                "code".into(),
                le_u32(payload, 0).expect("checked").to_string(),
            ),
            (
                "monotonic_time_ns".into(),
                le_u64(payload, 4).expect("checked").to_string(),
            ),
        ],
        4 => vec![
            (
                "kind".into(),
                le_u32(payload, 0).expect("checked").to_string(),
            ),
            (
                "frame_count".into(),
                le_u16(payload, 4).expect("checked").to_string(),
            ),
        ],
        _ => unreachable!("event ID was validated"),
    };
    Ok(fields)
}

fn be_u32(bytes: &[u8], offset: usize) -> Option<u32> {
    bytes
        .get(offset..offset.checked_add(4)?)
        .and_then(|value| value.try_into().ok())
        .map(u32::from_be_bytes)
}

fn be_u64(bytes: &[u8], offset: usize) -> Option<u64> {
    bytes
        .get(offset..offset.checked_add(8)?)
        .and_then(|value| value.try_into().ok())
        .map(u64::from_be_bytes)
}

fn le_u16(bytes: &[u8], offset: usize) -> Option<u16> {
    bytes
        .get(offset..offset.checked_add(2)?)
        .and_then(|value| value.try_into().ok())
        .map(u16::from_le_bytes)
}

fn le_u32(bytes: &[u8], offset: usize) -> Option<u32> {
    bytes
        .get(offset..offset.checked_add(4)?)
        .and_then(|value| value.try_into().ok())
        .map(u32::from_le_bytes)
}

fn le_i32(bytes: &[u8], offset: usize) -> Option<i32> {
    bytes
        .get(offset..offset.checked_add(4)?)
        .and_then(|value| value.try_into().ok())
        .map(i32::from_le_bytes)
}

fn le_u64(bytes: &[u8], offset: usize) -> Option<u64> {
    bytes
        .get(offset..offset.checked_add(8)?)
        .and_then(|value| value.try_into().ok())
        .map(u64::from_le_bytes)
}

/// Parses a deterministic v1 archive without extracting names to the file system.
pub fn parse_archive(bytes: &[u8]) -> Result<Archive, ArchiveError> {
    if bytes.len() as u64 > MAX_ARCHIVE_BYTES { return Err(ArchiveError::ArchiveTooLarge(bytes.len() as u64)); }
    let eocd = find_eocd(bytes)?;
    let entries = u16_at(bytes, eocd + 10)?;
    let central_size = u32_at(bytes, eocd + 12)? as usize;
    let central_offset = u32_at(bytes, eocd + 16)? as usize;
    let comment_length = u16_at(bytes, eocd + 20)? as usize;
    if comment_length != 0 || eocd.checked_add(22) != Some(bytes.len()) { return Err(ArchiveError::InvalidZip("comments and trailing bytes are forbidden")); }
    if entries as usize > MAX_ENTRIES { return Err(ArchiveError::TooManyEntries(entries)); }
    let central_end = central_offset.checked_add(central_size).ok_or(ArchiveError::Truncated)?;
    if central_end != eocd || central_end > bytes.len() { return Err(ArchiveError::Truncated); }
    let mut cursor = central_offset;
    let mut total = 0_u64;
    let mut names = BTreeSet::new();
    let mut output = Vec::with_capacity(entries as usize);
    for _ in 0..entries {
        if u32_at(bytes, cursor)? != CENTRAL_DIRECTORY_HEADER { return Err(ArchiveError::InvalidZip("central directory signature")); }
        let flags = u16_at(bytes, cursor + 8)?;
        validate_flags(flags)?;
        let method = u16_at(bytes, cursor + 10)?;
        if method != 0 { return Err(ArchiveError::UnsupportedCompression(method)); }
        let crc = u32_at(bytes, cursor + 16)?;
        let compressed = u32_at(bytes, cursor + 20)? as u64;
        let uncompressed = u32_at(bytes, cursor + 24)? as u64;
        if compressed != uncompressed { return Err(ArchiveError::InvalidZip("STORED size mismatch")); }
        if uncompressed > MAX_TOTAL_UNCOMPRESSED { return Err(ArchiveError::EntryTooLarge(uncompressed)); }
        total = total.checked_add(uncompressed).ok_or(ArchiveError::TotalTooLarge(u64::MAX))?;
        if total > MAX_TOTAL_UNCOMPRESSED { return Err(ArchiveError::TotalTooLarge(total)); }
        let name_len = u16_at(bytes, cursor + 28)? as usize;
        let extra_len = u16_at(bytes, cursor + 30)? as usize;
        let file_comment_len = u16_at(bytes, cursor + 32)? as usize;
        if name_len == 0 || name_len > MAX_ENTRY_NAME_BYTES || extra_len != 0 || file_comment_len != 0 { return Err(ArchiveError::InvalidEntryName); }
        let local_offset = u32_at(bytes, cursor + 42)? as usize;
        let name_start = cursor.checked_add(46).ok_or(ArchiveError::Truncated)?;
        let next = name_start.checked_add(name_len).ok_or(ArchiveError::Truncated)?;
        if next > central_end { return Err(ArchiveError::Truncated); }
        let name = std::str::from_utf8(&bytes[name_start..next]).map_err(|_| ArchiveError::InvalidUtf8Name)?;
        validate_entry_name(name)?;
        if !names.insert(name.to_owned()) { return Err(ArchiveError::DuplicateEntry); }
        let payload = payload(bytes, local_offset, name.as_bytes(), flags, method, crc, compressed, uncompressed)?;
        let actual_crc = crc32(payload);
        if actual_crc != crc { return Err(ArchiveError::CrcMismatch { declared: crc, actual: actual_crc }); }
        output.push(ArchiveEntry { name: name.to_owned(), bytes: payload.to_vec() });
        cursor = next;
    }
    if cursor != central_end { return Err(ArchiveError::InvalidZip("central directory length")); }
    Ok(Archive { entries: output })
}

fn find_eocd(bytes: &[u8]) -> Result<usize, ArchiveError> {
    if bytes.len() < 22 { return Err(ArchiveError::Truncated); }
    let start = bytes.len().saturating_sub(22 + 65_535);
    for index in (start..=bytes.len() - 22).rev() {
        if u32_at(bytes, index)? == END_OF_CENTRAL_DIRECTORY { return Ok(index); }
    }
    Err(ArchiveError::InvalidZip("missing end of central directory"))
}
fn payload<'a>(
    bytes: &'a [u8], offset: usize, name: &[u8], central_flags: u16, central_method: u16,
    central_crc: u32, central_compressed: u64, central_uncompressed: u64,
) -> Result<&'a [u8], ArchiveError> {
    if u32_at(bytes, offset)? != LOCAL_FILE_HEADER { return Err(ArchiveError::InvalidLocalHeader); }
    let local_flags = u16_at(bytes, offset + 6)?;
    validate_flags(local_flags)?;
    let local_method = u16_at(bytes, offset + 8)?;
    let local_crc = u32_at(bytes, offset + 14)?;
    let local_compressed = u32_at(bytes, offset + 18)? as u64;
    let local_uncompressed = u32_at(bytes, offset + 22)? as u64;
    if local_flags != central_flags { return Err(ArchiveError::LocalHeaderMismatch("flags")); }
    if local_method != central_method { return Err(ArchiveError::LocalHeaderMismatch("compression method")); }
    if local_crc != central_crc { return Err(ArchiveError::LocalHeaderMismatch("CRC-32")); }
    if local_compressed != central_compressed { return Err(ArchiveError::LocalHeaderMismatch("compressed size")); }
    if local_uncompressed != central_uncompressed { return Err(ArchiveError::LocalHeaderMismatch("uncompressed size")); }
    let local_name_len = u16_at(bytes, offset + 26)? as usize;
    let extra_len = u16_at(bytes, offset + 28)? as usize;
    if local_name_len != name.len() { return Err(ArchiveError::LocalHeaderMismatch("filename")); }
    if extra_len != 0 { return Err(ArchiveError::InvalidLocalHeader); }
    let name_start = offset.checked_add(30).ok_or(ArchiveError::Truncated)?;
    let data_start = name_start.checked_add(local_name_len).ok_or(ArchiveError::Truncated)?;
    let data_end = data_start.checked_add(usize::try_from(central_uncompressed).map_err(|_| ArchiveError::EntryTooLarge(central_uncompressed))?).ok_or(ArchiveError::Truncated)?;
    if data_end > bytes.len() { return Err(ArchiveError::Truncated); }
    if &bytes[name_start..data_start] != name { return Err(ArchiveError::LocalHeaderMismatch("filename")); }
    Ok(&bytes[data_start..data_end])
}
fn validate_flags(flags: u16) -> Result<(), ArchiveError> {
    if flags & 1 != 0 { return Err(ArchiveError::EncryptedEntry); }
    if flags & 8 != 0 { return Err(ArchiveError::DataDescriptorEntry); }
    Ok(())
}
fn crc32(bytes: &[u8]) -> u32 {
    let mut crc = !0_u32;
    for byte in bytes {
        crc ^= u32::from(*byte);
        for _ in 0..8 { crc = if crc & 1 != 0 { (crc >> 1) ^ 0xedb8_8320 } else { crc >> 1 }; }
    }
    !crc
}
fn validate_entry_name(name: &str) -> Result<(), ArchiveError> {
    if name.starts_with('/') || name.starts_with('\\') || name.contains('\\') || name.contains('\0') || name.split('/').any(|part| part.is_empty() || part == "." || part == "..") || name.as_bytes().get(1) == Some(&b':') { return Err(ArchiveError::InvalidEntryName); }
    Ok(())
}
fn u16_at(bytes: &[u8], offset: usize) -> Result<u16, ArchiveError> { Ok(u16::from_le_bytes(bytes.get(offset..offset + 2).ok_or(ArchiveError::Truncated)?.try_into().map_err(|_| ArchiveError::Truncated)?)) }
fn u32_at(bytes: &[u8], offset: usize) -> Result<u32, ArchiveError> { Ok(u32::from_le_bytes(bytes.get(offset..offset + 4).ok_or(ArchiveError::Truncated)?.try_into().map_err(|_| ArchiveError::Truncated)?)) }

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SymbolCatalogEntry { pub module: String, pub identity: String, pub offset: u64, pub symbol: String }
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RawFrame { pub module: String, pub identity: String, pub offset: u64 }
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Symbolication { Resolved { raw: RawFrame, symbol: String }, Unresolved { raw: RawFrame }, IdentityMismatch { raw: RawFrame, available: Vec<String> } }

/// Resolves only identical module identity plus offset; callers must treat mismatch as hard failure.
pub fn symbolize(catalog: &[SymbolCatalogEntry], raw: RawFrame) -> Symbolication {
    let available: Vec<String> = catalog.iter().filter(|entry| entry.module == raw.module).map(|entry| entry.identity.clone()).collect();
    if !available.is_empty() && !available.iter().any(|identity| identity == &raw.identity) { return Symbolication::IdentityMismatch { raw, available }; }
    match catalog.iter().find(|entry| entry.module == raw.module && entry.identity == raw.identity && entry.offset == raw.offset) {
        Some(entry) => Symbolication::Resolved { raw, symbol: entry.symbol.clone() },
        None => Symbolication::Unresolved { raw },
    }
}

/// Native and R8 entries emitted by the Gradle `symbol-catalog.tsv` artifact.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GeneratedSymbolCatalog {
    native_entries: Vec<SymbolCatalogEntry>,
    native_identities: BTreeMap<String, BTreeSet<String>>,
    r8_entries: Vec<R8CatalogEntry>,
    r8_identities: BTreeSet<String>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct R8CatalogEntry {
    mapping_identity: String,
    obfuscated: String,
    original: String,
}

/// Catalog parsing errors are hard failures: a partial symbol catalog can mislead diagnosis.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CatalogError {
    /// A non-comment line did not match a supported v1 or legacy catalog shape.
    MalformedLine { line: usize, detail: String },
}

impl fmt::Display for CatalogError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::MalformedLine { line, detail } => {
                write!(formatter, "malformed catalog line {line}: {detail}")
            }
        }
    }
}

impl std::error::Error for CatalogError {}

/// Parses the exact, generated tab-separated catalog without accepting unknown record kinds.
///
/// # Errors
///
/// Returns an error when a non-comment line does not match a strict legacy or generated catalog
/// record shape.
pub fn parse_generated_symbol_catalog(
    contents: &str,
) -> Result<GeneratedSymbolCatalog, CatalogError> {
    let mut native_entries = Vec::new();
    let mut native_identities = BTreeMap::<String, BTreeSet<String>>::new();
    let mut r8_entries = Vec::new();
    let mut r8_identities = BTreeSet::new();
    for (index, raw_line) in contents.lines().enumerate() {
        let line_number = index + 1;
        if raw_line.is_empty() || raw_line.starts_with('#') {
            continue;
        }
        let fields: Vec<_> = raw_line.split('\t').collect();
        match fields.first().copied() {
            Some("native") => {
                if fields.len() != 6 || fields[1..].iter().any(|field| field.is_empty()) {
                    return Err(malformed_catalog_line(
                        line_number,
                        "expected native<TAB>MODULE<TAB>IDENTITY<TAB>ABI<TAB>OFFSET<TAB>SYMBOL",
                    ));
                }
                let offset = fields[4].parse::<u64>().map_err(|_| {
                    malformed_catalog_line(line_number, "native offset must be u64")
                })?;
                native_identities
                    .entry(fields[1].to_owned())
                    .or_default()
                    .insert(fields[2].to_owned());
                if fields[5] != "identity-only" {
                    native_entries.push(SymbolCatalogEntry {
                        module: fields[1].to_owned(),
                        identity: fields[2].to_owned(),
                        offset,
                        symbol: fields[5].to_owned(),
                    });
                }
            }
            Some("r8") => {
                if fields.len() != 4 || fields[1..].iter().any(|field| field.is_empty()) {
                    return Err(malformed_catalog_line(
                        line_number,
                        "expected r8<TAB>MAPPING_ID<TAB>OBFUSCATED<TAB>ORIGINAL",
                    ));
                }
                r8_identities.insert(fields[1].to_owned());
                if fields[2] != "<identity>" || fields[3] != "<identity>" {
                    r8_entries.push(R8CatalogEntry {
                        mapping_identity: fields[1].to_owned(),
                        obfuscated: fields[2].to_owned(),
                        original: fields[3].to_owned(),
                    });
                }
            }
            _ => {
                if fields.len() != 4 || fields.iter().any(|field| field.is_empty()) {
                    return Err(malformed_catalog_line(
                        line_number,
                        "expected MODULE<TAB>IDENTITY<TAB>OFFSET<TAB>SYMBOL",
                    ));
                }
                let offset = fields[2]
                    .parse::<u64>()
                    .map_err(|_| malformed_catalog_line(line_number, "offset must be u64"))?;
                native_identities
                    .entry(fields[0].to_owned())
                    .or_default()
                    .insert(fields[1].to_owned());
                native_entries.push(SymbolCatalogEntry {
                    module: fields[0].to_owned(),
                    identity: fields[1].to_owned(),
                    offset,
                    symbol: fields[3].to_owned(),
                });
            }
        }
    }
    Ok(GeneratedSymbolCatalog {
        native_entries,
        native_identities,
        r8_entries,
        r8_identities,
    })
}

/// Resolves only an exact generated native module identity and offset.
#[must_use]
pub fn symbolize_generated_catalog(
    catalog: &GeneratedSymbolCatalog,
    raw: RawFrame,
) -> Symbolication {
    let available = catalog
        .native_identities
        .get(&raw.module)
        .map(|identities| identities.iter().cloned().collect::<Vec<_>>())
        .unwrap_or_default();
    if !available.is_empty() && !available.iter().any(|identity| identity == &raw.identity) {
        return Symbolication::IdentityMismatch { raw, available };
    }
    match catalog.native_entries.iter().find(|entry| {
        entry.module == raw.module && entry.identity == raw.identity && entry.offset == raw.offset
    }) {
        Some(entry) => Symbolication::Resolved {
            raw,
            symbol: entry.symbol.clone(),
        },
        None => Symbolication::Unresolved { raw },
    }
}

/// Result of exact R8 retracing against one mapping identity.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RetraceResult {
    /// One exact residual frame was mapped to its source name.
    Resolved {
        obfuscated: String,
        original: String,
    },
    /// The mapping identity is present but contains no exact residual frame.
    Unresolved { obfuscated: String },
    /// Multiple source frames map to the same residual spelling without a line-range discriminator.
    Ambiguous {
        obfuscated: String,
        candidates: Vec<String>,
    },
    /// The requested mapping identity is absent; no other mapping is substituted.
    IdentityMismatch {
        requested: String,
        available: Vec<String>,
    },
}

/// Retraces only an exact R8 mapping identity and residual frame spelling.
#[must_use]
pub fn retrace_generated_catalog(
    catalog: &GeneratedSymbolCatalog,
    mapping_identity: String,
    obfuscated: String,
) -> RetraceResult {
    let available = catalog.r8_identities.iter().cloned().collect::<Vec<_>>();
    if !catalog.r8_identities.contains(&mapping_identity) {
        return RetraceResult::IdentityMismatch {
            requested: mapping_identity,
            available,
        };
    }
    let candidates = catalog
        .r8_entries
        .iter()
        .filter(|entry| {
            entry.mapping_identity == mapping_identity && entry.obfuscated == obfuscated
        })
        .map(|entry| entry.original.clone())
        .collect::<BTreeSet<_>>();
    match candidates.len() {
        0 => RetraceResult::Unresolved { obfuscated },
        1 => match candidates.into_iter().next() {
            Some(original) => RetraceResult::Resolved {
                obfuscated,
                original,
            },
            None => RetraceResult::Unresolved { obfuscated },
        },
        _ => RetraceResult::Ambiguous {
            obfuscated,
            candidates: candidates.into_iter().collect(),
        },
    }
}

fn malformed_catalog_line(line: usize, detail: &str) -> CatalogError {
    CatalogError::MalformedLine {
        line,
        detail: detail.to_owned(),
    }
}

#[cfg(test)]
mod tests {
 use super::*;
 fn zip(name: &str, payload: &[u8]) -> Vec<u8> {
  let mut out=Vec::new(); let n=name.as_bytes(); let crc=crc32(payload); let flags=0x0800_u16;
  out.extend_from_slice(&LOCAL_FILE_HEADER.to_le_bytes()); out.extend_from_slice(&20_u16.to_le_bytes()); out.extend_from_slice(&flags.to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&crc.to_le_bytes()); out.extend_from_slice(&(payload.len() as u32).to_le_bytes()); out.extend_from_slice(&(payload.len() as u32).to_le_bytes()); out.extend_from_slice(&(n.len() as u16).to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(n); out.extend_from_slice(payload);
  let cd=out.len(); out.extend_from_slice(&CENTRAL_DIRECTORY_HEADER.to_le_bytes()); out.extend_from_slice(&20_u16.to_le_bytes()); out.extend_from_slice(&20_u16.to_le_bytes()); out.extend_from_slice(&flags.to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&crc.to_le_bytes()); out.extend_from_slice(&(payload.len() as u32).to_le_bytes()); out.extend_from_slice(&(payload.len() as u32).to_le_bytes()); out.extend_from_slice(&(n.len() as u16).to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&0_u32.to_le_bytes()); out.extend_from_slice(&0_u32.to_le_bytes()); out.extend_from_slice(n);
  let size=out.len()-cd; out.extend_from_slice(&END_OF_CENTRAL_DIRECTORY.to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out.extend_from_slice(&1_u16.to_le_bytes()); out.extend_from_slice(&1_u16.to_le_bytes()); out.extend_from_slice(&(size as u32).to_le_bytes()); out.extend_from_slice(&(cd as u32).to_le_bytes()); out.extend_from_slice(&0_u16.to_le_bytes()); out
 }
 fn central_directory_offset(bytes: &[u8]) -> usize { u32_at(bytes, bytes.len()-6).unwrap() as usize }
 fn set_u16(bytes: &mut [u8], offset: usize, value: u16) { bytes[offset..offset+2].copy_from_slice(&value.to_le_bytes()); }
 fn set_u32(bytes: &mut [u8], offset: usize, value: u32) { bytes[offset..offset+4].copy_from_slice(&value.to_le_bytes()); }
 #[test] fn validates_stored_archive_without_extraction() { let parsed=parse_archive(&zip("manifest.cbor", b"ok")).unwrap(); assert_eq!(parsed.entries[0].name,"manifest.cbor"); }
 #[test] fn rejects_path_traversal() { assert_eq!(parse_archive(&zip("../escape",b"x")),Err(ArchiveError::InvalidEntryName)); }
 #[test] fn rejects_encryption_flag_in_local_or_central_header() {
  let mut local=zip("manifest.cbor",b"ok"); set_u16(&mut local,6,0x0801); assert_eq!(parse_archive(&local),Err(ArchiveError::EncryptedEntry));
  let mut central=zip("manifest.cbor",b"ok"); let offset=central_directory_offset(&central); set_u16(&mut central,offset+8,0x0801); assert_eq!(parse_archive(&central),Err(ArchiveError::EncryptedEntry));
 }
 #[test] fn rejects_data_descriptor_flag_in_local_or_central_header() {
  let mut local=zip("manifest.cbor",b"ok"); set_u16(&mut local,6,0x0808); assert_eq!(parse_archive(&local),Err(ArchiveError::DataDescriptorEntry));
  let mut central=zip("manifest.cbor",b"ok"); let offset=central_directory_offset(&central); set_u16(&mut central,offset+8,0x0808); assert_eq!(parse_archive(&central),Err(ArchiveError::DataDescriptorEntry));
 }
 #[test] fn rejects_mismatched_local_and_central_crc_or_size() {
  let mut crc_mismatch=zip("manifest.cbor",b"ok"); set_u32(&mut crc_mismatch,14,0); assert_eq!(parse_archive(&crc_mismatch),Err(ArchiveError::LocalHeaderMismatch("CRC-32")));
  let mut size_mismatch=zip("manifest.cbor",b"ok"); let central=central_directory_offset(&size_mismatch); set_u32(&mut size_mismatch,central+20,1); set_u32(&mut size_mismatch,central+24,1); assert_eq!(parse_archive(&size_mismatch),Err(ArchiveError::LocalHeaderMismatch("compressed size")));
 }
 #[test] fn rejects_mismatched_local_compression_method() {
  // The central method is forced to STORED (0) at the outer entry-loop check before payload()
  // ever runs, so only the LOCAL method field can diverge and reach the local/central compare.
  let mut method_mismatch=zip("manifest.cbor",b"ok"); set_u16(&mut method_mismatch,8,5); assert_eq!(parse_archive(&method_mismatch),Err(ArchiveError::LocalHeaderMismatch("compression method")));
 }
 #[test] fn rejects_mismatched_local_filename_content() {
  // Same declared length as the central name, but different bytes: length check at
  // `local_name_len != name.len()` passes, so this exercises the byte-content compare.
  let mut name_mismatch=zip("manifest.cbor",b"ok"); name_mismatch[30]^=0xff; assert_eq!(parse_archive(&name_mismatch),Err(ArchiveError::LocalHeaderMismatch("filename")));
 }
 #[test] fn rejects_payload_with_wrong_crc() { let mut corrupted=zip("manifest.cbor",b"ok"); corrupted[30+"manifest.cbor".len()]^=0xff; assert!(matches!(parse_archive(&corrupted),Err(ArchiveError::CrcMismatch { .. }))); }
 #[test] fn mutation_corpus_never_panics() { let good=zip("manifest.cbor",b"ok"); for index in 0..good.len() { let mut value=good.clone(); value[index]^=0xff; assert!(std::panic::catch_unwind(|| parse_archive(&value)).is_ok()); } for length in 0..good.len() { assert!(std::panic::catch_unwind(|| parse_archive(&good[..length])).is_ok()); } }
 #[test] fn mismatch_preserves_raw_frame() { let raw=RawFrame { module:"libx.so".into(), identity:"bad".into(), offset:42 }; let outcome=symbolize(&[SymbolCatalogEntry{module:"libx.so".into(),identity:"good".into(),offset:42,symbol:"rust_or_cpp".into()}],raw.clone()); assert_eq!(outcome,Symbolication::IdentityMismatch{raw,available:vec!["good".into()]}); }
 #[test] fn missing_entry_is_unresolved() { let raw=RawFrame { module:"libx.so".into(), identity:"good".into(), offset:99 }; assert_eq!(symbolize(&[],raw.clone()),Symbolication::Unresolved{raw}); }    #[test]
    fn decodes_actual_tracebox_record_bodies_and_filters_by_generated_type() {
        let mut body = Vec::new();
        for value in [1_u32, 3, 1, 1, 0, 1] {
            body.extend_from_slice(&value.to_be_bytes());
        }
        body.extend_from_slice(&42_u64.to_be_bytes());
        body.extend_from_slice(&7_u32.to_le_bytes());
        body.extend_from_slice(&9_u64.to_le_bytes());
        let archive = parse_archive(&zip("records/000001.tbr", &body)).unwrap();
        let records = decode_package_records(&archive).unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].event_type, "Breadcrumb");
        assert_eq!(records[0].timestamp_millis, 42);
        assert_eq!(
            records[0].fields,
            vec![
                ("code".into(), "7".into()),
                ("monotonic_time_ns".into(), "9".into())
            ]
        );
        assert_eq!(
            filter_package_records(&records, "Breadcrumb").unwrap(),
            records
        );
    }
    #[test]
    fn retrace_refuses_ambiguous_r8_residual_frames() {
        let catalog = parse_generated_symbol_catalog(
            "r8\tsha256:mapping\ta.b.c\tdev.tracebox.First.method\n\
    r8\tsha256:mapping\ta.b.c\tdev.tracebox.Second.method\n",
        )
        .unwrap();
        assert!(matches!(
            retrace_generated_catalog(&catalog, "sha256:mapping".into(), "a.b.c".into()),
            RetraceResult::Ambiguous { .. }
        ));
    }
}