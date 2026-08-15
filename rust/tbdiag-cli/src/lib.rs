//! Offline, bounded `.tbdiag` archive validation and exact-identity symbol resolution.
//!
//! v1 accepts only the deterministic STORED ZIP shape emitted by Tracebox.  Rejecting
//! unsupported compression is intentional: it keeps every declared size checked before
//! allocating or copying attacker-controlled bytes.
mod generated_schema;

use generated_schema::{
    SCHEMA_FINGERPRINT, decode_generated_payload, event_id_for_selector, event_type_name,
};
use std::cmp::Ordering;
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::{self, Write as _};
use std::fs::File;
use std::io::Read;
use std::path::Path;

pub const MAX_ENTRIES: usize = 128;
pub const MAX_TOTAL_UNCOMPRESSED: u64 = 128 * 1024 * 1024;
pub const MAX_ARCHIVE_BYTES: u64 = 128 * 1024 * 1024;
pub const MAX_ENTRY_NAME_BYTES: usize = 255;
pub const MAX_SYMBOL_CATALOG_BYTES: u64 = 64 * 1024 * 1024;
pub const MAX_SYMBOL_CATALOG_ROWS: usize = 262_144;
pub const MAX_SYMBOL_CATALOG_ROW_BYTES: usize = 16 * 1024;
pub const MAX_SYMBOL_CATALOG_FIELD_BYTES: usize = 4096;
pub const CURRENT_SCHEMA_FINGERPRINT: [u8; 32] = SCHEMA_FINGERPRINT;
const SYMBOL_CATALOG_V2_HEADER: &str = "# tracebox-symbol-catalog-v2";
const END_OF_CENTRAL_DIRECTORY: u32 = 0x0605_4b50;
const CENTRAL_DIRECTORY_HEADER: u32 = 0x0201_4b50;
const LOCAL_FILE_HEADER: u32 = 0x0403_4b50;
const ZIP_VERSION: u16 = 20;
const UTF8_FLAG: u16 = 1 << 11;
const DOS_TIME: u16 = 0;
const DOS_DATE: u16 = 0x0021;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ArchiveEntry {
    pub name: String,
    pub bytes: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Archive {
    pub entries: Vec<ArchiveEntry>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ArchiveError {
    Io(String),
    ArchiveTooLarge(u64),
    Truncated,
    InvalidZip(&'static str),
    TooManyEntries(u16),
    EntryTooLarge(u64),
    TotalTooLarge(u64),
    UnsupportedCompression(u16),
    InvalidEntryName,
    DuplicateEntry,
    InvalidUtf8Name,
    InvalidLocalHeader,
    EncryptedEntry,
    DataDescriptorEntry,
    LocalHeaderMismatch(&'static str),
    CrcMismatch { declared: u32, actual: u32 },
}

impl fmt::Display for ArchiveError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{self:?}")
    }
}
impl std::error::Error for ArchiveError {}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PackageValidationError {
    ManifestMissing,
    ManifestMalformed(&'static str),
    UnsupportedPackageVersion(u64),
    UnsupportedRecordVersion(u64),
    SchemaMismatch,
}

impl fmt::Display for PackageValidationError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::ManifestMissing => write!(formatter, "manifest.cbor is missing"),
            Self::ManifestMalformed(detail) => write!(formatter, "manifest is malformed: {detail}"),
            Self::UnsupportedPackageVersion(version) => {
                write!(formatter, "unsupported package version: {version}")
            }
            Self::UnsupportedRecordVersion(version) => {
                write!(formatter, "unsupported package record version: {version}")
            }
            Self::SchemaMismatch => write!(formatter, "unsupported schema fingerprint"),
        }
    }
}

impl std::error::Error for PackageValidationError {}

/// Validates the canonical v1 manifest and its exact generated schema fingerprint.
///
/// # Errors
///
/// Returns a typed compatibility error before records are decoded under the wrong schema.
pub fn validate_package(archive: &Archive) -> Result<(), PackageValidationError> {
    let manifest = archive
        .entries
        .iter()
        .find(|entry| entry.name == "manifest.cbor")
        .ok_or(PackageValidationError::ManifestMissing)?;
    ManifestReader::new(&manifest.bytes).validate()
}

struct ManifestReader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> ManifestReader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn validate(mut self) -> Result<(), PackageValidationError> {
        let fields = self.read_collection_head(5)?;
        if fields != MANIFEST_FIELDS.len() {
            return Err(self.malformed("unexpected root field count"));
        }
        let mut previous_key = None;
        let mut seen = BTreeSet::new();
        let mut package_version = None;
        let mut record_version = None;
        let mut schema_matches = false;
        for _ in 0..fields {
            let key_start = self.offset;
            let key = self.read_text()?.to_owned();
            let key_end = self.offset;
            self.require_canonical_key(previous_key, (key_start, key_end))?;
            previous_key = Some((key_start, key_end));
            if !seen.insert(key.clone()) || !MANIFEST_FIELDS.contains(&key.as_str()) {
                return Err(self.malformed("unknown or duplicate root field"));
            }
            match key.as_str() {
                "v" => package_version = Some(self.read_unsigned()?),
                "record" => record_version = Some(self.read_unsigned()?),
                "schema" => schema_matches = self.read_bytes()? == SCHEMA_FINGERPRINT,
                _ => self.skip_value(1)?,
            }
        }
        if self.offset != self.bytes.len() || seen.len() != MANIFEST_FIELDS.len() {
            return Err(self.malformed("trailing bytes or missing root field"));
        }
        let package_version =
            package_version.ok_or_else(|| self.malformed("package version missing"))?;
        if package_version != PACKAGE_FORMAT_VERSION {
            return Err(PackageValidationError::UnsupportedPackageVersion(
                package_version,
            ));
        }
        let record_version =
            record_version.ok_or_else(|| self.malformed("record version missing"))?;
        if record_version != PACKAGE_RECORD_VERSION {
            return Err(PackageValidationError::UnsupportedRecordVersion(
                record_version,
            ));
        }
        if !schema_matches {
            return Err(PackageValidationError::SchemaMismatch);
        }
        Ok(())
    }

    fn skip_value(&mut self, depth: usize) -> Result<(), PackageValidationError> {
        if depth > MAX_CBOR_DEPTH {
            return Err(self.malformed("nesting exceeds limit"));
        }
        let (major, count) = self.read_head()?;
        match major {
            0 => Ok(()),
            2 | 3 => {
                let size =
                    usize::try_from(count).map_err(|_| self.malformed("value is too large"))?;
                let value = self.take(size)?;
                if major == 3 {
                    std::str::from_utf8(value).map_err(|_| self.malformed("text is not UTF-8"))?;
                }
                Ok(())
            }
            4 => {
                let items = self.collection_size(count)?;
                for _ in 0..items {
                    self.skip_value(depth + 1)?;
                }
                Ok(())
            }
            5 => {
                let items = self.collection_size(count)?;
                let mut previous_key = None;
                for _ in 0..items {
                    let key_start = self.offset;
                    self.read_text()?;
                    let key_end = self.offset;
                    self.require_canonical_key(previous_key, (key_start, key_end))?;
                    previous_key = Some((key_start, key_end));
                    self.skip_value(depth + 1)?;
                }
                Ok(())
            }
            _ => Err(self.malformed("unsupported CBOR type")),
        }
    }

    fn read_unsigned(&mut self) -> Result<u64, PackageValidationError> {
        let (major, value) = self.read_head()?;
        if major == 0 {
            Ok(value)
        } else {
            Err(self.malformed("expected unsigned integer"))
        }
    }

    fn read_bytes(&mut self) -> Result<&'a [u8], PackageValidationError> {
        let (major, size) = self.read_head()?;
        if major != 2 {
            return Err(self.malformed("expected byte string"));
        }
        let size = usize::try_from(size).map_err(|_| self.malformed("byte string is too large"))?;
        self.take(size)
    }

    fn read_text(&mut self) -> Result<&'a str, PackageValidationError> {
        let (major, size) = self.read_head()?;
        if major != 3 {
            return Err(self.malformed("map key is not text"));
        }
        let size = usize::try_from(size).map_err(|_| self.malformed("text is too large"))?;
        let value = self.take(size)?;
        std::str::from_utf8(value).map_err(|_| self.malformed("text is not UTF-8"))
    }

    fn read_collection_head(
        &mut self,
        expected_major: u8,
    ) -> Result<usize, PackageValidationError> {
        let (major, count) = self.read_head()?;
        if major != expected_major {
            return Err(self.malformed("unexpected CBOR root type"));
        }
        self.collection_size(count)
    }

    fn collection_size(&self, count: u64) -> Result<usize, PackageValidationError> {
        if count > MAX_CBOR_ITEMS {
            return Err(self.malformed("collection exceeds limit"));
        }
        usize::try_from(count).map_err(|_| self.malformed("collection is too large"))
    }

    fn read_head(&mut self) -> Result<(u8, u64), PackageValidationError> {
        let initial = self.take(1)?[0];
        let major = initial >> 5;
        let additional = initial & 0x1f;
        let value = match additional {
            0..=23 => u64::from(additional),
            24 => {
                let value = u64::from(self.take(1)?[0]);
                if value < 24 {
                    return Err(self.malformed("non-shortest CBOR integer"));
                }
                value
            }
            25 => {
                let value = u64::from(u16::from_be_bytes(self.take_array()?));
                if u8::try_from(value).is_ok() {
                    return Err(self.malformed("non-shortest CBOR integer"));
                }
                value
            }
            26 => {
                let value = u64::from(u32::from_be_bytes(self.take_array()?));
                if u16::try_from(value).is_ok() {
                    return Err(self.malformed("non-shortest CBOR integer"));
                }
                value
            }
            27 => {
                let value = u64::from_be_bytes(self.take_array()?);
                if u32::try_from(value).is_ok() {
                    return Err(self.malformed("non-shortest CBOR integer"));
                }
                value
            }
            _ => return Err(self.malformed("indefinite CBOR is unsupported")),
        };
        Ok((major, value))
    }

    fn require_canonical_key(
        &self,
        previous: Option<(usize, usize)>,
        current: (usize, usize),
    ) -> Result<(), PackageValidationError> {
        if let Some(previous) = previous {
            let left = &self.bytes[previous.0..previous.1];
            let right = &self.bytes[current.0..current.1];
            if left.len().cmp(&right.len()).then_with(|| left.cmp(right)) != Ordering::Less {
                return Err(self.malformed("map keys are not in canonical order"));
            }
        }
        Ok(())
    }

    fn take_array<const SIZE: usize>(&mut self) -> Result<[u8; SIZE], PackageValidationError> {
        self.take(SIZE)?
            .try_into()
            .map_err(|_| self.malformed("CBOR is truncated"))
    }

    fn take(&mut self, size: usize) -> Result<&'a [u8], PackageValidationError> {
        let end = self
            .offset
            .checked_add(size)
            .ok_or_else(|| self.malformed("CBOR size overflows"))?;
        let value = self
            .bytes
            .get(self.offset..end)
            .ok_or_else(|| self.malformed("CBOR is truncated"))?;
        self.offset = end;
        Ok(value)
    }

    // Keeping this method receiver makes every parser failure local to the reader expression.
    #[allow(clippy::unused_self)]
    fn malformed(&self, detail: &'static str) -> PackageValidationError {
        PackageValidationError::ManifestMalformed(detail)
    }
}

const PACKAGE_FORMAT_VERSION: u64 = 1;
const PACKAGE_RECORD_VERSION: u64 = 1;
const MAX_CBOR_DEPTH: usize = 32;
const MAX_CBOR_ITEMS: u64 = 4_096;
const MANIFEST_FIELDS: [&str; 8] = [
    "v",
    "record",
    "schema",
    "epoch",
    "privacy",
    "range",
    "entries",
    "omissions",
];

/// Opens only bounded inputs. Metadata is checked before allocating the file buffer.
///
/// # Errors
///
/// Returns a typed I/O or archive-validation error without exposing partial archive contents.
pub fn read_archive(path: &Path) -> Result<Archive, ArchiveError> {
    let length = std::fs::metadata(path)
        .map_err(|error| ArchiveError::Io(error.to_string()))?
        .len();
    if length > MAX_ARCHIVE_BYTES {
        return Err(ArchiveError::ArchiveTooLarge(length));
    }
    let capacity = usize::try_from(length).map_err(|_| ArchiveError::ArchiveTooLarge(length))?;
    let mut bytes = Vec::with_capacity(capacity);
    File::open(path)
        .map_err(|error| ArchiveError::Io(error.to_string()))?
        .take(MAX_ARCHIVE_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| ArchiveError::Io(error.to_string()))?;
    if bytes.len() > capacity {
        return Err(ArchiveError::ArchiveTooLarge(bytes.len() as u64));
    }
    parse_archive(&bytes)
}

/// A generated Tracebox record decoded from a finalized package entry.
///
/// Fields are schema-defined JSON scalars. Numeric values are decimal and bounded UTF-8 values are
/// escaped before formatting, so package text cannot alter the line-oriented output structure.
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
    /// A variable-size generated payload was truncated, oversized, malformed, or non-UTF-8.
    InvalidPayload { event_id: u32, detail: &'static str },
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
            Self::InvalidPayload { event_id, detail } => {
                write!(formatter, "invalid payload for event {event_id}: {detail}")
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
    let event_id = event_id_for_selector(selector)
        .ok_or_else(|| RecordDecodeError::UnknownFilter(selector.to_owned()))?;
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

struct PayloadReader<'a> {
    event_id: u32,
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> PayloadReader<'a> {
    fn new(event_id: u32, bytes: &'a [u8]) -> Self {
        Self {
            event_id,
            bytes,
            offset: 0,
        }
    }

    fn read_u16(&mut self) -> Result<u16, RecordDecodeError> {
        Ok(u16::from_le_bytes(self.take()?))
    }

    fn read_u32(&mut self) -> Result<u32, RecordDecodeError> {
        Ok(u32::from_le_bytes(self.take()?))
    }

    fn read_i32(&mut self) -> Result<i32, RecordDecodeError> {
        Ok(i32::from_le_bytes(self.take()?))
    }

    fn read_u64(&mut self) -> Result<u64, RecordDecodeError> {
        Ok(u64::from_le_bytes(self.take()?))
    }

    fn read_utf8(&mut self, maximum_bytes: usize) -> Result<&'a str, RecordDecodeError> {
        let size = usize::from(self.read_u16()?);
        if size > maximum_bytes {
            return Err(self.invalid("bounded UTF-8 value exceeds its schema maximum"));
        }
        let value = self.take_bytes(size)?;
        std::str::from_utf8(value).map_err(|_| self.invalid("bounded UTF-8 value is malformed"))
    }

    fn finish(self) -> Result<(), RecordDecodeError> {
        if self.offset == self.bytes.len() {
            Ok(())
        } else {
            Err(self.invalid("payload has trailing bytes"))
        }
    }

    fn take<const SIZE: usize>(&mut self) -> Result<[u8; SIZE], RecordDecodeError> {
        self.take_bytes(SIZE)?
            .try_into()
            .map_err(|_| self.invalid("payload is truncated"))
    }

    fn take_bytes(&mut self, size: usize) -> Result<&'a [u8], RecordDecodeError> {
        let end = self
            .offset
            .checked_add(size)
            .ok_or_else(|| self.invalid("payload size overflows"))?;
        let value = self
            .bytes
            .get(self.offset..end)
            .ok_or_else(|| self.invalid("payload is truncated"))?;
        self.offset = end;
        Ok(value)
    }

    fn invalid(&self, detail: &'static str) -> RecordDecodeError {
        RecordDecodeError::InvalidPayload {
            event_id: self.event_id,
            detail,
        }
    }
}

fn json_string(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len() + 2);
    escaped.push('"');
    for character in value.chars() {
        match character {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\u{08}' => escaped.push_str("\\b"),
            '\u{0c}' => escaped.push_str("\\f"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            control if control <= '\u{1f}' => {
                write!(escaped, "\\u{:04x}", u32::from(control))
                    .expect("writing to a String cannot fail");
            }
            other => escaped.push(other),
        }
    }
    escaped.push('"');
    escaped
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

/// Parses a deterministic v1 archive without extracting names to the file system.
///
/// # Errors
///
/// Returns a typed validation error when the input is not the exact bounded STORED-v1 shape.
// This is intentionally linear: each central-directory field is checked adjacent to the bounded
// read that consumes it, which makes local/central equivalence auditable.
#[allow(clippy::too_many_lines)]
pub fn parse_archive(bytes: &[u8]) -> Result<Archive, ArchiveError> {
    if bytes.len() as u64 > MAX_ARCHIVE_BYTES {
        return Err(ArchiveError::ArchiveTooLarge(bytes.len() as u64));
    }
    let eocd = find_eocd(bytes)?;
    if u16_at(bytes, eocd + 4)? != 0 || u16_at(bytes, eocd + 6)? != 0 {
        return Err(ArchiveError::InvalidZip(
            "multi-disk archives are forbidden",
        ));
    }
    if u16_at(bytes, eocd + 8)? != u16_at(bytes, eocd + 10)? {
        return Err(ArchiveError::InvalidZip(
            "split central directory is forbidden",
        ));
    }
    let entries = u16_at(bytes, eocd + 10)?;
    let central_size = u32_at(bytes, eocd + 12)? as usize;
    let central_offset = u32_at(bytes, eocd + 16)? as usize;
    let comment_length = u16_at(bytes, eocd + 20)? as usize;
    if comment_length != 0 || eocd.checked_add(22) != Some(bytes.len()) {
        return Err(ArchiveError::InvalidZip(
            "comments and trailing bytes are forbidden",
        ));
    }
    if entries as usize > MAX_ENTRIES {
        return Err(ArchiveError::TooManyEntries(entries));
    }
    let central_end = central_offset
        .checked_add(central_size)
        .ok_or(ArchiveError::Truncated)?;
    if central_end != eocd || central_end > bytes.len() {
        return Err(ArchiveError::Truncated);
    }
    let mut cursor = central_offset;
    let mut total = 0_u64;
    let mut names = BTreeSet::new();
    let mut output = Vec::with_capacity(entries as usize);
    let mut previous_name: Option<String> = None;
    let mut expected_local_offset = 0_usize;
    for _ in 0..entries {
        if u32_at(bytes, cursor)? != CENTRAL_DIRECTORY_HEADER {
            return Err(ArchiveError::InvalidZip("central directory signature"));
        }
        if u16_at(bytes, cursor + 4)? != ZIP_VERSION || u16_at(bytes, cursor + 6)? != ZIP_VERSION {
            return Err(ArchiveError::InvalidZip("unsupported ZIP version"));
        }
        let flags = u16_at(bytes, cursor + 8)?;
        validate_flags(flags)?;
        if flags != UTF8_FLAG {
            return Err(ArchiveError::InvalidZip("non-canonical ZIP flags"));
        }
        let method = u16_at(bytes, cursor + 10)?;
        if method != 0 {
            return Err(ArchiveError::UnsupportedCompression(method));
        }
        if u16_at(bytes, cursor + 12)? != DOS_TIME || u16_at(bytes, cursor + 14)? != DOS_DATE {
            return Err(ArchiveError::InvalidZip("non-canonical ZIP timestamp"));
        }
        let crc = u32_at(bytes, cursor + 16)?;
        let compressed = u64::from(u32_at(bytes, cursor + 20)?);
        let uncompressed = u64::from(u32_at(bytes, cursor + 24)?);
        if compressed != uncompressed {
            return Err(ArchiveError::InvalidZip("STORED size mismatch"));
        }
        if uncompressed > MAX_TOTAL_UNCOMPRESSED {
            return Err(ArchiveError::EntryTooLarge(uncompressed));
        }
        total = total
            .checked_add(uncompressed)
            .ok_or(ArchiveError::TotalTooLarge(u64::MAX))?;
        if total > MAX_TOTAL_UNCOMPRESSED {
            return Err(ArchiveError::TotalTooLarge(total));
        }
        let name_len = u16_at(bytes, cursor + 28)? as usize;
        let extra_len = u16_at(bytes, cursor + 30)? as usize;
        let file_comment_len = u16_at(bytes, cursor + 32)? as usize;
        if name_len == 0
            || name_len > MAX_ENTRY_NAME_BYTES
            || extra_len != 0
            || file_comment_len != 0
        {
            return Err(ArchiveError::InvalidEntryName);
        }
        if u16_at(bytes, cursor + 34)? != 0
            || u16_at(bytes, cursor + 36)? != 0
            || u32_at(bytes, cursor + 38)? != 0
        {
            return Err(ArchiveError::InvalidZip("non-canonical central attributes"));
        }
        let local_offset = u32_at(bytes, cursor + 42)? as usize;
        if local_offset != expected_local_offset {
            return Err(ArchiveError::InvalidZip("non-canonical local entry order"));
        }
        let name_start = cursor.checked_add(46).ok_or(ArchiveError::Truncated)?;
        let next = name_start
            .checked_add(name_len)
            .ok_or(ArchiveError::Truncated)?;
        if next > central_end {
            return Err(ArchiveError::Truncated);
        }
        let name = std::str::from_utf8(&bytes[name_start..next])
            .map_err(|_| ArchiveError::InvalidUtf8Name)?;
        validate_entry_name(name)?;
        if previous_name
            .as_deref()
            .is_some_and(|previous| previous >= name)
        {
            return Err(ArchiveError::InvalidZip(
                "entry names are not canonically sorted",
            ));
        }
        previous_name = Some(name.to_owned());
        if !names.insert(name.to_owned()) {
            return Err(ArchiveError::DuplicateEntry);
        }
        let expected = StoredEntryHeader {
            flags,
            method,
            crc,
            compressed,
            uncompressed,
        };
        let payload = payload(bytes, local_offset, name.as_bytes(), &expected)?;
        let actual_crc = crc32(payload);
        if actual_crc != crc {
            return Err(ArchiveError::CrcMismatch {
                declared: crc,
                actual: actual_crc,
            });
        }
        output.push(ArchiveEntry {
            name: name.to_owned(),
            bytes: payload.to_vec(),
        });
        let uncompressed_size =
            usize::try_from(uncompressed).map_err(|_| ArchiveError::EntryTooLarge(uncompressed))?;
        expected_local_offset = local_offset
            .checked_add(30)
            .and_then(|value| value.checked_add(name_len))
            .and_then(|value| value.checked_add(uncompressed_size))
            .ok_or(ArchiveError::Truncated)?;
        cursor = next;
    }
    if cursor != central_end || expected_local_offset != central_offset {
        return Err(ArchiveError::InvalidZip("central directory length"));
    }
    Ok(Archive { entries: output })
}

fn find_eocd(bytes: &[u8]) -> Result<usize, ArchiveError> {
    if bytes.len() < 22 {
        return Err(ArchiveError::Truncated);
    }
    let start = bytes.len().saturating_sub(22 + 65_535);
    for index in (start..=bytes.len() - 22).rev() {
        if u32_at(bytes, index)? == END_OF_CENTRAL_DIRECTORY {
            return Ok(index);
        }
    }
    Err(ArchiveError::InvalidZip("missing end of central directory"))
}
struct StoredEntryHeader {
    flags: u16,
    method: u16,
    crc: u32,
    compressed: u64,
    uncompressed: u64,
}

fn payload<'a>(
    bytes: &'a [u8],
    offset: usize,
    name: &[u8],
    expected: &StoredEntryHeader,
) -> Result<&'a [u8], ArchiveError> {
    if u32_at(bytes, offset)? != LOCAL_FILE_HEADER {
        return Err(ArchiveError::InvalidLocalHeader);
    }
    if u16_at(bytes, offset + 4)? != ZIP_VERSION {
        return Err(ArchiveError::InvalidZip("unsupported local ZIP version"));
    }
    let local_flags = u16_at(bytes, offset + 6)?;
    validate_flags(local_flags)?;
    if local_flags != UTF8_FLAG
        || u16_at(bytes, offset + 10)? != DOS_TIME
        || u16_at(bytes, offset + 12)? != DOS_DATE
    {
        return Err(ArchiveError::InvalidZip("non-canonical local ZIP metadata"));
    }
    let local_method = u16_at(bytes, offset + 8)?;
    let local_crc = u32_at(bytes, offset + 14)?;
    let local_compressed = u64::from(u32_at(bytes, offset + 18)?);
    let local_uncompressed = u64::from(u32_at(bytes, offset + 22)?);
    if local_flags != expected.flags {
        return Err(ArchiveError::LocalHeaderMismatch("flags"));
    }
    if local_method != expected.method {
        return Err(ArchiveError::LocalHeaderMismatch("compression method"));
    }
    if local_crc != expected.crc {
        return Err(ArchiveError::LocalHeaderMismatch("CRC-32"));
    }
    if local_compressed != expected.compressed {
        return Err(ArchiveError::LocalHeaderMismatch("compressed size"));
    }
    if local_uncompressed != expected.uncompressed {
        return Err(ArchiveError::LocalHeaderMismatch("uncompressed size"));
    }
    let local_name_len = u16_at(bytes, offset + 26)? as usize;
    let extra_len = u16_at(bytes, offset + 28)? as usize;
    if local_name_len != name.len() {
        return Err(ArchiveError::LocalHeaderMismatch("filename"));
    }
    if extra_len != 0 {
        return Err(ArchiveError::InvalidLocalHeader);
    }
    let name_start = offset.checked_add(30).ok_or(ArchiveError::Truncated)?;
    let data_start = name_start
        .checked_add(local_name_len)
        .ok_or(ArchiveError::Truncated)?;
    let data_end = data_start
        .checked_add(
            usize::try_from(expected.uncompressed)
                .map_err(|_| ArchiveError::EntryTooLarge(expected.uncompressed))?,
        )
        .ok_or(ArchiveError::Truncated)?;
    if data_end > bytes.len() {
        return Err(ArchiveError::Truncated);
    }
    if &bytes[name_start..data_start] != name {
        return Err(ArchiveError::LocalHeaderMismatch("filename"));
    }
    Ok(&bytes[data_start..data_end])
}
fn validate_flags(flags: u16) -> Result<(), ArchiveError> {
    if flags & 1 != 0 {
        return Err(ArchiveError::EncryptedEntry);
    }
    if flags & 8 != 0 {
        return Err(ArchiveError::DataDescriptorEntry);
    }
    Ok(())
}
fn crc32(bytes: &[u8]) -> u32 {
    let mut crc = !0_u32;
    for byte in bytes {
        crc ^= u32::from(*byte);
        for _ in 0..8 {
            crc = if crc & 1 != 0 {
                (crc >> 1) ^ 0xedb8_8320
            } else {
                crc >> 1
            };
        }
    }
    !crc
}
fn validate_entry_name(name: &str) -> Result<(), ArchiveError> {
    if name.starts_with('/')
        || name.starts_with('\\')
        || name.contains('\\')
        || name.contains('\0')
        || name
            .split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
        || name.as_bytes().get(1) == Some(&b':')
    {
        return Err(ArchiveError::InvalidEntryName);
    }
    Ok(())
}
fn u16_at(bytes: &[u8], offset: usize) -> Result<u16, ArchiveError> {
    Ok(u16::from_le_bytes(
        bytes
            .get(offset..offset + 2)
            .ok_or(ArchiveError::Truncated)?
            .try_into()
            .map_err(|_| ArchiveError::Truncated)?,
    ))
}
fn u32_at(bytes: &[u8], offset: usize) -> Result<u32, ArchiveError> {
    Ok(u32::from_le_bytes(
        bytes
            .get(offset..offset + 4)
            .ok_or(ArchiveError::Truncated)?
            .try_into()
            .map_err(|_| ArchiveError::Truncated)?,
    ))
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SymbolCatalogEntry {
    pub build_id: String,
    pub module: String,
    pub identity: String,
    pub abi: String,
    pub offset: u64,
    pub symbol: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RawFrame {
    pub build_id: String,
    pub module: String,
    pub identity: String,
    pub abi: String,
    pub offset: u64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Symbolication {
    Resolved {
        raw: RawFrame,
        symbol: String,
    },
    Ambiguous {
        raw: RawFrame,
        candidates: Vec<String>,
    },
    Unresolved {
        raw: RawFrame,
    },
    BuildIdentityMismatch {
        raw: RawFrame,
        available: Vec<String>,
    },
    AbiMismatch {
        raw: RawFrame,
        available: Vec<String>,
    },
    IdentityMismatch {
        raw: RawFrame,
        available: Vec<String>,
    },
}

/// Resolves only an identical full build, ABI, module identity, and offset.
#[must_use]
pub fn symbolize(catalog: &[SymbolCatalogEntry], raw: RawFrame) -> Symbolication {
    let module = module_basename(&raw.module);
    let build_ids = catalog
        .iter()
        .map(|entry| entry.build_id.clone())
        .filter(|value| !value.is_empty())
        .collect::<BTreeSet<_>>();
    if !build_ids.is_empty() && !build_ids.contains(&raw.build_id) {
        return Symbolication::BuildIdentityMismatch {
            raw,
            available: build_ids.into_iter().collect(),
        };
    }
    let abis = catalog
        .iter()
        .filter(|entry| entry.build_id == raw.build_id && entry.module == module)
        .map(|entry| entry.abi.clone())
        .collect::<BTreeSet<_>>();
    if !abis.is_empty() && !abis.contains(&raw.abi) {
        return Symbolication::AbiMismatch {
            raw,
            available: abis.into_iter().collect(),
        };
    }
    let identities = catalog
        .iter()
        .filter(|entry| {
            entry.build_id == raw.build_id && entry.module == module && entry.abi == raw.abi
        })
        .map(|entry| entry.identity.clone())
        .collect::<BTreeSet<_>>();
    if !identities.is_empty() && !identities.contains(&raw.identity) {
        return Symbolication::IdentityMismatch {
            raw,
            available: identities.into_iter().collect(),
        };
    }
    let candidates = catalog
        .iter()
        .filter(|entry| {
            entry.build_id == raw.build_id
                && entry.module == module
                && entry.identity == raw.identity
                && entry.abi == raw.abi
                && entry.offset == raw.offset
        })
        .map(|entry| entry.symbol.clone())
        .collect::<BTreeSet<_>>();
    match candidates.len() {
        0 => Symbolication::Unresolved { raw },
        1 => match candidates.into_iter().next() {
            Some(symbol) => Symbolication::Resolved { raw, symbol },
            None => Symbolication::Unresolved { raw },
        },
        _ => Symbolication::Ambiguous {
            raw,
            candidates: candidates.into_iter().collect(),
        },
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CatalogBuildIdentity {
    pub build_id: String,
    pub schema_fingerprint: String,
    pub application_id: String,
    pub version_code: u64,
    pub version_name: String,
    pub variant: String,
    pub crashpad_source_sha256: Option<String>,
    pub crashpad_patch_set_sha256: Option<String>,
    pub rust_lock_sha256: Option<String>,
    pub dependency_verification_sha256: Option<String>,
    pub dependency_lock_sha256: Option<String>,
}

/// Native and R8 entries emitted by the Gradle `symbol-catalog.tsv` artifact.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GeneratedSymbolCatalog {
    build_identity: CatalogBuildIdentity,
    native_entries: Vec<SymbolCatalogEntry>,
    native_modules: BTreeMap<String, BTreeSet<String>>,
    native_identities: BTreeMap<(String, String), BTreeSet<String>>,
    r8_entries: Vec<R8CatalogEntry>,
    r8_identities: BTreeSet<String>,
}

impl GeneratedSymbolCatalog {
    #[must_use]
    pub const fn build_identity(&self) -> &CatalogBuildIdentity {
        &self.build_identity
    }
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
    Io(String),
    CatalogTooLarge(u64),
    TooManyRows(usize),
    RowTooLarge {
        line: usize,
        bytes: usize,
    },
    FieldTooLarge {
        line: usize,
        field: usize,
        bytes: usize,
    },
    MalformedLine {
        line: usize,
        detail: String,
    },
}

impl fmt::Display for CatalogError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(detail) => write!(formatter, "catalog I/O error: {detail}"),
            Self::CatalogTooLarge(bytes) => write!(formatter, "symbol catalog too large: {bytes}"),
            Self::TooManyRows(rows) => {
                write!(formatter, "symbol catalog has too many rows: {rows}")
            }
            Self::RowTooLarge { line, bytes } => {
                write!(formatter, "symbol catalog row {line} is too large: {bytes}")
            }
            Self::FieldTooLarge { line, field, bytes } => {
                write!(
                    formatter,
                    "symbol catalog field {field} on line {line} is too large: {bytes}"
                )
            }
            Self::MalformedLine { line, detail } => {
                write!(formatter, "malformed catalog line {line}: {detail}")
            }
        }
    }
}

impl std::error::Error for CatalogError {}

/// Opens a generated catalog only after checking its size, and caps a growing input while reading.
///
/// # Errors
///
/// Returns a typed I/O, UTF-8, size, or catalog-validation error without accepting partial rows.
pub fn read_generated_symbol_catalog(path: &Path) -> Result<GeneratedSymbolCatalog, CatalogError> {
    let length = std::fs::metadata(path)
        .map_err(|error| CatalogError::Io(error.to_string()))?
        .len();
    if length > MAX_SYMBOL_CATALOG_BYTES {
        return Err(CatalogError::CatalogTooLarge(length));
    }
    let capacity = usize::try_from(length).map_err(|_| CatalogError::CatalogTooLarge(length))?;
    let mut bytes = Vec::with_capacity(capacity);
    File::open(path)
        .map_err(|error| CatalogError::Io(error.to_string()))?
        .take(MAX_SYMBOL_CATALOG_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| CatalogError::Io(error.to_string()))?;
    if bytes.len() > capacity {
        return Err(CatalogError::CatalogTooLarge(bytes.len() as u64));
    }
    let contents =
        String::from_utf8(bytes).map_err(|_| malformed_catalog_line(0, "catalog must be UTF-8"))?;
    parse_generated_symbol_catalog(&contents)
}

/// Parses a bounded, generated tab-separated catalog without accepting unknown record kinds.
///
/// # Errors
///
/// Returns a typed size or row-validation error for malformed or ambiguous catalog input.
pub fn parse_generated_symbol_catalog(
    contents: &str,
) -> Result<GeneratedSymbolCatalog, CatalogError> {
    parse_generated_symbol_catalog_with_limits(
        contents,
        MAX_SYMBOL_CATALOG_BYTES,
        MAX_SYMBOL_CATALOG_ROWS,
        MAX_SYMBOL_CATALOG_ROW_BYTES,
        MAX_SYMBOL_CATALOG_FIELD_BYTES,
    )
}

// The parser keeps every bounded row kind in one match so unknown and legacy shapes cannot be
// accepted by a less strict secondary path.
#[allow(clippy::too_many_lines)]
fn parse_generated_symbol_catalog_with_limits(
    contents: &str,
    max_bytes: u64,
    max_rows: usize,
    max_row_bytes: usize,
    max_field_bytes: usize,
) -> Result<GeneratedSymbolCatalog, CatalogError> {
    if contents.len() as u64 > max_bytes {
        return Err(CatalogError::CatalogTooLarge(contents.len() as u64));
    }
    let mut build_identity = None;
    let mut native_entries = Vec::new();
    let mut native_modules = BTreeMap::<String, BTreeSet<String>>::new();
    let mut native_identities = BTreeMap::<(String, String), BTreeSet<String>>::new();
    let mut r8_entries = Vec::new();
    let mut r8_identities = BTreeSet::new();
    let mut rows = 0_usize;
    let mut saw_header = false;
    let mut saw_nonempty_line = false;
    for (index, raw_line) in contents.lines().enumerate() {
        let line_number = index + 1;
        if raw_line.is_empty() {
            continue;
        }
        if !saw_nonempty_line {
            saw_nonempty_line = true;
            if raw_line != SYMBOL_CATALOG_V2_HEADER {
                return Err(malformed_catalog_line(
                    line_number,
                    "expected # tracebox-symbol-catalog-v2 header",
                ));
            }
            saw_header = true;
            continue;
        }
        if raw_line == SYMBOL_CATALOG_V2_HEADER {
            return Err(malformed_catalog_line(
                line_number,
                "duplicate symbol catalog header",
            ));
        }
        if raw_line.starts_with('#') {
            continue;
        }
        rows = rows.saturating_add(1);
        if rows > max_rows {
            return Err(CatalogError::TooManyRows(rows));
        }
        if raw_line.len() > max_row_bytes {
            return Err(CatalogError::RowTooLarge {
                line: line_number,
                bytes: raw_line.len(),
            });
        }
        let fields = raw_line.split('\t').collect::<Vec<_>>();
        for (field, value) in fields.iter().enumerate() {
            if value.len() > max_field_bytes {
                return Err(CatalogError::FieldTooLarge {
                    line: line_number,
                    field: field + 1,
                    bytes: value.len(),
                });
            }
        }
        match fields.first().copied() {
            Some("build") => {
                if fields.len() != 12 || fields[1..].iter().any(|field| field.is_empty()) {
                    return Err(malformed_catalog_line(
                        line_number,
                        "expected build identity with 11 fields",
                    ));
                }
                if build_identity.is_some() {
                    return Err(malformed_catalog_line(
                        line_number,
                        "duplicate build identity",
                    ));
                }
                let version_code = fields[4]
                    .parse::<u64>()
                    .map_err(|_| malformed_catalog_line(line_number, "version code must be u64"))?;
                build_identity = Some(CatalogBuildIdentity {
                    build_id: fields[1].to_owned(),
                    schema_fingerprint: fields[2].to_owned(),
                    application_id: fields[3].to_owned(),
                    version_code,
                    version_name: fields[5].to_owned(),
                    variant: fields[6].to_owned(),
                    crashpad_source_sha256: optional_catalog_hash(fields[7]),
                    crashpad_patch_set_sha256: optional_catalog_hash(fields[8]),
                    rust_lock_sha256: optional_catalog_hash(fields[9]),
                    dependency_verification_sha256: optional_catalog_hash(fields[10]),
                    dependency_lock_sha256: optional_catalog_hash(fields[11]),
                });
            }
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
                let module = module_basename(fields[1]);
                if module.is_empty() {
                    return Err(malformed_catalog_line(
                        line_number,
                        "native module basename is empty",
                    ));
                }
                native_modules
                    .entry(module.to_owned())
                    .or_default()
                    .insert(fields[3].to_owned());
                native_identities
                    .entry((module.to_owned(), fields[3].to_owned()))
                    .or_default()
                    .insert(fields[2].to_owned());
                if fields[5] != "identity-only" {
                    native_entries.push(SymbolCatalogEntry {
                        build_id: String::new(),
                        module: module.to_owned(),
                        identity: fields[2].to_owned(),
                        abi: fields[3].to_owned(),
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
                return Err(malformed_catalog_line(
                    line_number,
                    "unknown symbol catalog record kind",
                ));
            }
        }
    }
    if !saw_header {
        return Err(malformed_catalog_line(
            0,
            "expected # tracebox-symbol-catalog-v2 header",
        ));
    }
    let build_identity =
        build_identity.ok_or_else(|| malformed_catalog_line(0, "missing build identity row"))?;
    let build_id = build_identity.build_id.clone();
    for entry in &mut native_entries {
        entry.build_id.clone_from(&build_id);
    }
    Ok(GeneratedSymbolCatalog {
        build_identity,
        native_entries,
        native_modules,
        native_identities,
        r8_entries,
        r8_identities,
    })
}

/// Resolves only an exact generated full build, ABI, native module identity, and offset.
#[must_use]
pub fn symbolize_generated_catalog(
    catalog: &GeneratedSymbolCatalog,
    raw: RawFrame,
) -> Symbolication {
    if catalog.build_identity.build_id != raw.build_id {
        return Symbolication::BuildIdentityMismatch {
            raw,
            available: vec![catalog.build_identity.build_id.clone()],
        };
    }
    let module = module_basename(&raw.module);
    let available_abis = catalog
        .native_modules
        .get(module)
        .map(|abis| abis.iter().cloned().collect::<Vec<_>>())
        .unwrap_or_default();
    if !available_abis.is_empty() && !available_abis.iter().any(|abi| abi == &raw.abi) {
        return Symbolication::AbiMismatch {
            raw,
            available: available_abis,
        };
    }
    let available_identities = catalog
        .native_identities
        .get(&(module.to_owned(), raw.abi.clone()))
        .map(|identities| identities.iter().cloned().collect::<Vec<_>>())
        .unwrap_or_default();
    if !available_identities.is_empty()
        && !available_identities
            .iter()
            .any(|identity| identity == &raw.identity)
    {
        return Symbolication::IdentityMismatch {
            raw,
            available: available_identities,
        };
    }
    let candidates = catalog
        .native_entries
        .iter()
        .filter(|entry| {
            entry.build_id == raw.build_id
                && entry.module == module
                && entry.identity == raw.identity
                && entry.abi == raw.abi
                && entry.offset == raw.offset
        })
        .map(|entry| entry.symbol.clone())
        .collect::<BTreeSet<_>>();
    match candidates.len() {
        0 => Symbolication::Unresolved { raw },
        1 => match candidates.into_iter().next() {
            Some(symbol) => Symbolication::Resolved { raw, symbol },
            None => Symbolication::Unresolved { raw },
        },
        _ => Symbolication::Ambiguous {
            raw,
            candidates: candidates.into_iter().collect(),
        },
    }
}

/// Result of exact R8 retracing against one full build and mapping identity.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RetraceResult {
    Resolved {
        obfuscated: String,
        original: String,
    },
    Unresolved {
        obfuscated: String,
    },
    Ambiguous {
        obfuscated: String,
        candidates: Vec<String>,
    },
    BuildIdentityMismatch {
        requested: String,
        available: Vec<String>,
    },
    IdentityMismatch {
        requested: String,
        available: Vec<String>,
    },
}

/// Retraces only an exact full build identity, R8 mapping identity, and residual frame spelling.
#[must_use]
pub fn retrace_generated_catalog(
    catalog: &GeneratedSymbolCatalog,
    build_id: String,
    mapping_identity: String,
    obfuscated: String,
) -> RetraceResult {
    if catalog.build_identity.build_id != build_id {
        return RetraceResult::BuildIdentityMismatch {
            requested: build_id,
            available: vec![catalog.build_identity.build_id.clone()],
        };
    }
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

fn optional_catalog_hash(value: &str) -> Option<String> {
    (value != "-").then(|| value.to_owned())
}

fn module_basename(value: &str) -> &str {
    value
        .rsplit(['/', '\\'])
        .next()
        .expect("split always returns one item")
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
    const TEST_CATALOG_PREFIX: &str = "# tracebox-symbol-catalog-v2\n\
        build\tbuild-good\tschema\tdev.tracebox\t7\t1.0\trelease\t-\t-\t-\t-\t-\n";

    fn zip(name: &str, payload: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        let n = name.as_bytes();
        let crc = crc32(payload);
        let flags = 0x0800_u16;
        let payload_len = u32::try_from(payload.len()).expect("test payload is ZIP32-sized");
        let name_len = u16::try_from(n.len()).expect("test name is ZIP16-sized");
        out.extend_from_slice(&LOCAL_FILE_HEADER.to_le_bytes());
        out.extend_from_slice(&20_u16.to_le_bytes());
        out.extend_from_slice(&flags.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out.extend_from_slice(&DOS_TIME.to_le_bytes());
        out.extend_from_slice(&DOS_DATE.to_le_bytes());
        out.extend_from_slice(&crc.to_le_bytes());
        out.extend_from_slice(&payload_len.to_le_bytes());
        out.extend_from_slice(&payload_len.to_le_bytes());
        out.extend_from_slice(&name_len.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out.extend_from_slice(n);
        out.extend_from_slice(payload);
        let cd = out.len();
        out.extend_from_slice(&CENTRAL_DIRECTORY_HEADER.to_le_bytes());
        out.extend_from_slice(&20_u16.to_le_bytes());
        out.extend_from_slice(&20_u16.to_le_bytes());
        out.extend_from_slice(&flags.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out.extend_from_slice(&DOS_TIME.to_le_bytes());
        out.extend_from_slice(&DOS_DATE.to_le_bytes());
        out.extend_from_slice(&crc.to_le_bytes());
        out.extend_from_slice(&payload_len.to_le_bytes());
        out.extend_from_slice(&payload_len.to_le_bytes());
        out.extend_from_slice(&name_len.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out.extend_from_slice(&0_u32.to_le_bytes());
        out.extend_from_slice(&0_u32.to_le_bytes());
        out.extend_from_slice(n);
        let size = out.len() - cd;
        let central_size = u32::try_from(size).expect("test central directory is ZIP32-sized");
        let central_offset = u32::try_from(cd).expect("test central offset is ZIP32-sized");
        out.extend_from_slice(&END_OF_CENTRAL_DIRECTORY.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out.extend_from_slice(&1_u16.to_le_bytes());
        out.extend_from_slice(&1_u16.to_le_bytes());
        out.extend_from_slice(&central_size.to_le_bytes());
        out.extend_from_slice(&central_offset.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out
    }
    fn compatible_manifest(
        package_version: u8,
        record_version: u8,
        schema_fingerprint: [u8; 32],
    ) -> Vec<u8> {
        let mut manifest = vec![
            0xa8,
            0x61,
            b'v',
            package_version,
            0x65,
            b'e',
            b'p',
            b'o',
            b'c',
            b'h',
            0x01,
            0x65,
            b'r',
            b'a',
            b'n',
            b'g',
            b'e',
            0x80,
            0x66,
            b'r',
            b'e',
            b'c',
            b'o',
            b'r',
            b'd',
            record_version,
            0x66,
            b's',
            b'c',
            b'h',
            b'e',
            b'm',
            b'a',
            0x58,
            0x20,
        ];
        manifest.extend_from_slice(&schema_fingerprint);
        manifest.extend_from_slice(&[
            0x67, b'e', b'n', b't', b'r', b'i', b'e', b's', 0x80, 0x67, b'p', b'r', b'i', b'v',
            b'a', b'c', b'y', 0x62, b'C', b'0', 0x69, b'o', b'm', b'i', b's', b's', b'i', b'o',
            b'n', b's', 0x80,
        ]);
        manifest
    }
    fn package_record(index: usize, event_id: u32, payload: &[u8]) -> ArchiveEntry {
        let mut bytes = Vec::new();
        for value in [1_u32, event_id, 1, 1, 0, u32::try_from(index).unwrap()] {
            bytes.extend_from_slice(&value.to_be_bytes());
        }
        bytes.extend_from_slice(&0_u64.to_be_bytes());
        bytes.extend_from_slice(payload);
        ArchiveEntry {
            name: format!("records/{index:06}.tbr"),
            bytes,
        }
    }
    fn central_directory_offset(bytes: &[u8]) -> usize {
        usize::try_from(u32_at(bytes, bytes.len() - 6).unwrap())
            .expect("ZIP32 offset fits the host pointer size")
    }
    fn set_u16(bytes: &mut [u8], offset: usize, value: u16) {
        bytes[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
    }
    fn set_u32(bytes: &mut [u8], offset: usize, value: u32) {
        bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
    }
    #[test]
    fn validates_stored_archive_without_extraction() {
        let parsed = parse_archive(&zip("manifest.cbor", b"ok")).unwrap();
        assert_eq!(parsed.entries[0].name, "manifest.cbor");
    }
    #[test]
    fn package_validation_requires_canonical_versions_and_exact_schema() {
        let compatible = compatible_manifest(1, 1, CURRENT_SCHEMA_FINGERPRINT);
        let archive = Archive {
            entries: vec![ArchiveEntry {
                name: "manifest.cbor".into(),
                bytes: compatible.clone(),
            }],
        };
        assert_eq!(validate_package(&archive), Ok(()));

        let wrong_package = Archive {
            entries: vec![ArchiveEntry {
                name: "manifest.cbor".into(),
                bytes: compatible_manifest(2, 1, CURRENT_SCHEMA_FINGERPRINT),
            }],
        };
        assert_eq!(
            validate_package(&wrong_package),
            Err(PackageValidationError::UnsupportedPackageVersion(2))
        );

        let wrong_record = Archive {
            entries: vec![ArchiveEntry {
                name: "manifest.cbor".into(),
                bytes: compatible_manifest(1, 2, CURRENT_SCHEMA_FINGERPRINT),
            }],
        };
        assert_eq!(
            validate_package(&wrong_record),
            Err(PackageValidationError::UnsupportedRecordVersion(2))
        );

        let wrong_schema = Archive {
            entries: vec![ArchiveEntry {
                name: "manifest.cbor".into(),
                bytes: compatible_manifest(1, 1, [0x5a; 32]),
            }],
        };
        assert_eq!(
            validate_package(&wrong_schema),
            Err(PackageValidationError::SchemaMismatch)
        );

        let mut non_shortest = compatible;
        non_shortest[3] = 0x18;
        non_shortest.insert(4, 0x01);
        let non_canonical = Archive {
            entries: vec![ArchiveEntry {
                name: "manifest.cbor".into(),
                bytes: non_shortest,
            }],
        };
        assert!(matches!(
            validate_package(&non_canonical),
            Err(PackageValidationError::ManifestMalformed(_))
        ));
    }
    #[test]
    fn validates_and_decodes_the_exact_kotlin_v1_package_golden() {
        let compact = include_str!("../../../tooling/fixtures/tbdiag-v1-golden.hex")
            .chars()
            .filter(|character| !character.is_whitespace())
            .collect::<String>();
        let bytes = compact
            .as_bytes()
            .chunks_exact(2)
            .map(|pair| {
                u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16)
                    .expect("package golden is hexadecimal")
            })
            .collect::<Vec<_>>();

        let archive = parse_archive(&bytes).unwrap();
        validate_package(&archive).unwrap();
        let records = decode_package_records(&archive).unwrap();

        assert_eq!(
            archive
                .entries
                .iter()
                .map(|entry| entry.name.as_str())
                .collect::<Vec<_>>(),
            vec!["manifest.cbor", "records/000001.tbr", "records/000002.tbr"]
        );
        assert_eq!(
            records
                .iter()
                .map(|record| record.event_id)
                .collect::<Vec<_>>(),
            vec![3, 4]
        );
    }
    #[test]
    fn rejects_path_traversal() {
        assert_eq!(
            parse_archive(&zip("../escape", b"x")),
            Err(ArchiveError::InvalidEntryName)
        );
    }
    #[test]
    fn rejects_encryption_flag_in_local_or_central_header() {
        let mut local = zip("manifest.cbor", b"ok");
        set_u16(&mut local, 6, 0x0801);
        assert_eq!(parse_archive(&local), Err(ArchiveError::EncryptedEntry));
        let mut central = zip("manifest.cbor", b"ok");
        let offset = central_directory_offset(&central);
        set_u16(&mut central, offset + 8, 0x0801);
        assert_eq!(parse_archive(&central), Err(ArchiveError::EncryptedEntry));
    }
    #[test]
    fn rejects_data_descriptor_flag_in_local_or_central_header() {
        let mut local = zip("manifest.cbor", b"ok");
        set_u16(&mut local, 6, 0x0808);
        assert_eq!(
            parse_archive(&local),
            Err(ArchiveError::DataDescriptorEntry)
        );
        let mut central = zip("manifest.cbor", b"ok");
        let offset = central_directory_offset(&central);
        set_u16(&mut central, offset + 8, 0x0808);
        assert_eq!(
            parse_archive(&central),
            Err(ArchiveError::DataDescriptorEntry)
        );
    }
    #[test]
    fn rejects_non_canonical_zip_timestamps() {
        let mut local = zip("manifest.cbor", b"ok");
        set_u16(&mut local, 12, 0);
        assert_eq!(
            parse_archive(&local),
            Err(ArchiveError::InvalidZip("non-canonical local ZIP metadata"))
        );

        let mut central = zip("manifest.cbor", b"ok");
        let offset = central_directory_offset(&central);
        set_u16(&mut central, offset + 14, 0);
        assert_eq!(
            parse_archive(&central),
            Err(ArchiveError::InvalidZip("non-canonical ZIP timestamp"))
        );
    }
    #[test]
    fn rejects_mismatched_local_and_central_crc_or_size() {
        let mut crc_mismatch = zip("manifest.cbor", b"ok");
        set_u32(&mut crc_mismatch, 14, 0);
        assert_eq!(
            parse_archive(&crc_mismatch),
            Err(ArchiveError::LocalHeaderMismatch("CRC-32"))
        );
        let mut size_mismatch = zip("manifest.cbor", b"ok");
        let central = central_directory_offset(&size_mismatch);
        set_u32(&mut size_mismatch, central + 20, 1);
        set_u32(&mut size_mismatch, central + 24, 1);
        assert_eq!(
            parse_archive(&size_mismatch),
            Err(ArchiveError::LocalHeaderMismatch("compressed size"))
        );
    }
    #[test]
    fn rejects_mismatched_local_compression_method() {
        // The central method is forced to STORED (0) at the outer entry-loop check before payload()
        // ever runs, so only the LOCAL method field can diverge and reach the local/central compare.
        let mut method_mismatch = zip("manifest.cbor", b"ok");
        set_u16(&mut method_mismatch, 8, 5);
        assert_eq!(
            parse_archive(&method_mismatch),
            Err(ArchiveError::LocalHeaderMismatch("compression method"))
        );
    }
    #[test]
    fn rejects_mismatched_local_filename_content() {
        // Same declared length as the central name, but different bytes: length check at
        // `local_name_len != name.len()` passes, so this exercises the byte-content compare.
        let mut name_mismatch = zip("manifest.cbor", b"ok");
        name_mismatch[30] ^= 0xff;
        assert_eq!(
            parse_archive(&name_mismatch),
            Err(ArchiveError::LocalHeaderMismatch("filename"))
        );
    }
    #[test]
    fn rejects_payload_with_wrong_crc() {
        let mut corrupted = zip("manifest.cbor", b"ok");
        corrupted[30 + "manifest.cbor".len()] ^= 0xff;
        assert!(matches!(
            parse_archive(&corrupted),
            Err(ArchiveError::CrcMismatch { .. })
        ));
    }
    #[test]
    fn mutation_corpus_never_panics() {
        let good = zip("manifest.cbor", b"ok");
        for index in 0..good.len() {
            let mut value = good.clone();
            value[index] ^= 0xff;
            assert!(std::panic::catch_unwind(|| parse_archive(&value)).is_ok());
        }
        for length in 0..good.len() {
            assert!(std::panic::catch_unwind(|| parse_archive(&good[..length])).is_ok());
        }
    }
    #[test]
    fn mismatch_preserves_raw_frame() {
        let raw = RawFrame {
            build_id: "build".into(),
            module: "libx.so".into(),
            identity: "bad".into(),
            abi: "x86_64".into(),
            offset: 42,
        };
        let outcome = symbolize(
            &[SymbolCatalogEntry {
                build_id: "build".into(),
                module: "libx.so".into(),
                identity: "good".into(),
                abi: "x86_64".into(),
                offset: 42,
                symbol: "rust_or_cpp".into(),
            }],
            raw.clone(),
        );
        assert_eq!(
            outcome,
            Symbolication::IdentityMismatch {
                raw,
                available: vec!["good".into()]
            }
        );
    }
    #[test]
    fn missing_entry_is_unresolved() {
        let raw = RawFrame {
            build_id: "build".into(),
            module: "libx.so".into(),
            identity: "good".into(),
            abi: "x86_64".into(),
            offset: 99,
        };
        assert_eq!(
            symbolize(&[], raw.clone()),
            Symbolication::Unresolved { raw }
        );
    }
    #[test]
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
    fn generated_decoder_covers_every_released_event_and_escapes_bounded_text() {
        let mut payloads = vec![
            (1, vec![0; 18]),
            (2, vec![0; 40]),
            (3, vec![0; 12]),
            (4, vec![0; 6]),
            (5, vec![0; 12]),
            (6, vec![0; 16]),
            (7, vec![0; 16]),
            (8, vec![0; 20]),
            (9, vec![0; 42]),
            (10, vec![0; 26]),
            (11, vec![0; 16]),
        ];
        let message = b"safe\n\"value\"";
        let mut log = Vec::new();
        log.extend_from_slice(&0_u32.to_le_bytes());
        log.extend_from_slice(&0_u32.to_le_bytes());
        log.extend_from_slice(&0_u64.to_le_bytes());
        log.extend_from_slice(&u16::try_from(message.len()).unwrap().to_le_bytes());
        log.extend_from_slice(message);
        log.extend_from_slice(&0_u32.to_le_bytes());
        log.extend_from_slice(&0_u64.to_le_bytes());
        log.extend_from_slice(&0_u64.to_le_bytes());
        log.extend_from_slice(&0_u32.to_le_bytes());
        payloads[8].1 = log;

        let archive = Archive {
            entries: payloads
                .into_iter()
                .enumerate()
                .map(|(index, (event_id, payload))| package_record(index + 1, event_id, &payload))
                .collect(),
        };
        let decoded = decode_package_records(&archive).unwrap();
        assert_eq!(decoded.len(), generated_schema::EVENTS.len());
        assert_eq!(
            decoded
                .iter()
                .map(|record| record.event_id)
                .collect::<Vec<_>>(),
            (1_u32..=11).collect::<Vec<_>>()
        );
        assert_eq!(
            decoded[8]
                .fields
                .iter()
                .find(|(name, _)| name == "rendered_message")
                .map(|(_, value)| value.as_str()),
            Some("\"safe\\n\\\"value\\\"\"")
        );
    }
    #[test]
    fn retrace_refuses_ambiguous_r8_residual_frames() {
        let contents = format!(
            "{TEST_CATALOG_PREFIX}r8\tsha256:mapping\ta.b.c\tdev.tracebox.First.method\n\
             r8\tsha256:mapping\ta.b.c\tdev.tracebox.Second.method\n"
        );
        let catalog = parse_generated_symbol_catalog(&contents).unwrap();
        assert!(matches!(
            retrace_generated_catalog(
                &catalog,
                "build-good".into(),
                "sha256:mapping".into(),
                "a.b.c".into()
            ),
            RetraceResult::Ambiguous { .. }
        ));
    }

    #[test]
    fn generated_catalog_rejects_wrong_full_build_and_abi() {
        let contents = format!(
            "{TEST_CATALOG_PREFIX}native\tlibtracebox.so\telf-good\tx86_64\t42\tnative_symbol\n"
        );
        let catalog = parse_generated_symbol_catalog(&contents).unwrap();
        let wrong_build = RawFrame {
            build_id: "build-wrong".into(),
            module: "libtracebox.so".into(),
            identity: "elf-good".into(),
            abi: "x86_64".into(),
            offset: 42,
        };
        assert!(matches!(
            symbolize_generated_catalog(&catalog, wrong_build),
            Symbolication::BuildIdentityMismatch { .. }
        ));
        let wrong_abi = RawFrame {
            build_id: "build-good".into(),
            module: "libtracebox.so".into(),
            identity: "elf-good".into(),
            abi: "arm64-v8a".into(),
            offset: 42,
        };
        assert!(matches!(
            symbolize_generated_catalog(&catalog, wrong_abi),
            Symbolication::AbiMismatch { .. }
        ));
    }

    #[test]
    fn generated_catalog_requires_v2_header_and_one_build_row() {
        assert!(matches!(
            parse_generated_symbol_catalog(
                "build\tbuild-good\tschema\tdev.tracebox\t7\t1.0\trelease\t-\t-\t-\t-\t-\n"
            ),
            Err(CatalogError::MalformedLine { .. })
        ));
        assert!(matches!(
            parse_generated_symbol_catalog(
                "# tracebox-symbol-catalog-v2\n\
                 native\tlibtracebox.so\telf-good\tx86_64\t42\tnative_symbol\n"
            ),
            Err(CatalogError::MalformedLine { .. })
        ));
        let legacy = format!("{TEST_CATALOG_PREFIX}libtracebox.so\telf-good\t42\tnative_symbol\n");
        assert!(matches!(
            parse_generated_symbol_catalog(&legacy),
            Err(CatalogError::MalformedLine { .. })
        ));
    }

    #[test]
    fn generated_catalog_normalizes_runtime_module_and_refuses_alias_ambiguity() {
        let aliases = format!(
            "{TEST_CATALOG_PREFIX}\
             native\tlib/arm64-v8a/libtracebox_crashpad.so\telf-good\tarm64-v8a\t42\talias_one\n\
             native\tlib/arm64-v8a/libtracebox_crashpad.so\telf-good\tarm64-v8a\t42\talias_two\n"
        );
        let catalog = parse_generated_symbol_catalog(&aliases).unwrap();
        let raw = RawFrame {
            build_id: "build-good".into(),
            module: "/data/app/dev.tracebox/lib/arm64/libtracebox_crashpad.so".into(),
            identity: "elf-good".into(),
            abi: "arm64-v8a".into(),
            offset: 42,
        };
        assert_eq!(
            symbolize_generated_catalog(&catalog, raw.clone()),
            Symbolication::Ambiguous {
                raw,
                candidates: vec!["alias_one".into(), "alias_two".into()],
            }
        );

        let identical = format!(
            "{TEST_CATALOG_PREFIX}\
             native\tlibtracebox_crashpad.so\telf-good\tarm64-v8a\t42\talias_one\n\
             native\tlibtracebox_crashpad.so\telf-good\tarm64-v8a\t42\talias_one\n"
        );
        let catalog = parse_generated_symbol_catalog(&identical).unwrap();
        let raw = RawFrame {
            build_id: "build-good".into(),
            module: "libtracebox_crashpad.so".into(),
            identity: "elf-good".into(),
            abi: "arm64-v8a".into(),
            offset: 42,
        };
        assert_eq!(
            symbolize_generated_catalog(&catalog, raw.clone()),
            Symbolication::Resolved {
                raw,
                symbol: "alias_one".into(),
            }
        );
    }

    #[test]
    fn catalog_limits_are_checked_before_field_allocation() {
        let short_build = "# tracebox-symbol-catalog-v2\n\
            build\tb\ts\ta\t1\tv\tr\t-\t-\t-\t-\t-\n";
        let too_many = format!("{short_build}r8\tm\ta\tb\n");
        assert!(matches!(
            parse_generated_symbol_catalog_with_limits(&too_many, 1024, 1, 128, 32),
            Err(CatalogError::TooManyRows(2))
        ));
        let oversized_field = format!("{short_build}r8\tm\tabcdef\tb\n");
        assert!(matches!(
            parse_generated_symbol_catalog_with_limits(&oversized_field, 1024, 8, 128, 5),
            Err(CatalogError::FieldTooLarge { .. })
        ));
        assert!(matches!(
            parse_generated_symbol_catalog_with_limits(
                "# tracebox-symbol-catalog-v2\n",
                4,
                8,
                128,
                32
            ),
            Err(CatalogError::CatalogTooLarge(_))
        ));
    }
}
