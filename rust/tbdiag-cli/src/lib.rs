//! Offline, bounded `.tbdiag` archive validation and exact-identity symbol resolution.
//!
//! v1 accepts only the deterministic STORED ZIP shape emitted by Tracebox.  Rejecting
//! unsupported compression is intentional: it keeps every declared size checked before
//! allocating or copying attacker-controlled bytes.
use std::collections::BTreeSet;
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
    DuplicateEntry, InvalidUtf8Name, InvalidLocalHeader,
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
        let method = u16_at(bytes, cursor + 10)?;
        if method != 0 { return Err(ArchiveError::UnsupportedCompression(method)); }
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
        let payload = payload(bytes, local_offset, name.as_bytes(), uncompressed)?;
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
fn payload<'a>(bytes: &'a [u8], offset: usize, name: &[u8], size: u64) -> Result<&'a [u8], ArchiveError> {
    if u32_at(bytes, offset)? != LOCAL_FILE_HEADER { return Err(ArchiveError::InvalidLocalHeader); }
    if u16_at(bytes, offset + 8)? != 0 { return Err(ArchiveError::InvalidLocalHeader); }
    let local_name_len = u16_at(bytes, offset + 26)? as usize;
    let extra_len = u16_at(bytes, offset + 28)? as usize;
    if local_name_len != name.len() || extra_len != 0 { return Err(ArchiveError::InvalidLocalHeader); }
    let name_start = offset.checked_add(30).ok_or(ArchiveError::Truncated)?;
    let data_start = name_start.checked_add(local_name_len).ok_or(ArchiveError::Truncated)?;
    let data_end = data_start.checked_add(usize::try_from(size).map_err(|_| ArchiveError::EntryTooLarge(size))?).ok_or(ArchiveError::Truncated)?;
    if data_end > bytes.len() || &bytes[name_start..data_start] != name { return Err(ArchiveError::InvalidLocalHeader); }
    Ok(&bytes[data_start..data_end])
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

#[cfg(test)]
mod tests {
 use super::*;
 fn zip(name: &str, payload: &[u8]) -> Vec<u8> { let mut out=Vec::new(); let n=name.as_bytes(); out.extend_from_slice(&LOCAL_FILE_HEADER.to_le_bytes()); out.extend_from_slice(&[20,0,0,0,0,0,0,0,0,0,0,0,0,0]); out.extend_from_slice(&(payload.len() as u32).to_le_bytes()); out.extend_from_slice(&(payload.len() as u32).to_le_bytes()); out.extend_from_slice(&(n.len() as u16).to_le_bytes()); out.extend_from_slice(&[0,0]); out.extend_from_slice(n); out.extend_from_slice(payload); let cd=out.len(); out.extend_from_slice(&CENTRAL_DIRECTORY_HEADER.to_le_bytes()); out.extend_from_slice(&[20,0,20,0,0,0,0,0,0,0,0,0,0,0,0,0]); out.extend_from_slice(&(payload.len() as u32).to_le_bytes()); out.extend_from_slice(&(payload.len() as u32).to_le_bytes()); out.extend_from_slice(&(n.len() as u16).to_le_bytes()); out.extend_from_slice(&[0,0,0,0,0,0,0,0,0,0,0,0]); out.extend_from_slice(&0_u32.to_le_bytes()); out.extend_from_slice(n); let size=out.len()-cd; out.extend_from_slice(&END_OF_CENTRAL_DIRECTORY.to_le_bytes()); out.extend_from_slice(&[0,0,0,0,1,0,1,0]); out.extend_from_slice(&(size as u32).to_le_bytes()); out.extend_from_slice(&(cd as u32).to_le_bytes()); out.extend_from_slice(&[0,0]); out }
 #[test] fn validates_stored_archive_without_extraction() { let parsed=parse_archive(&zip("manifest.cbor", b"ok")).unwrap(); assert_eq!(parsed.entries[0].name,"manifest.cbor"); }
 #[test] fn rejects_path_traversal() { assert_eq!(parse_archive(&zip("../escape",b"x")),Err(ArchiveError::InvalidEntryName)); }
 #[test] fn mutation_corpus_never_panics() { let good=zip("manifest.cbor",b"ok"); for index in 0..good.len() { let mut value=good.clone(); value[index]^=0xff; assert!(std::panic::catch_unwind(|| parse_archive(&value)).is_ok()); } for length in 0..good.len() { assert!(std::panic::catch_unwind(|| parse_archive(&good[..length])).is_ok()); } }
 #[test] fn mismatch_preserves_raw_frame() { let raw=RawFrame { module:"libx.so".into(), identity:"bad".into(), offset:42 }; let outcome=symbolize(&[SymbolCatalogEntry{module:"libx.so".into(),identity:"good".into(),offset:42,symbol:"rust_or_cpp".into()}],raw.clone()); assert_eq!(outcome,Symbolication::IdentityMismatch{raw,available:vec!["good".into()]}); }
 #[test] fn missing_entry_is_unresolved() { let raw=RawFrame { module:"libx.so".into(), identity:"good".into(), offset:99 }; assert_eq!(symbolize(&[],raw.clone()),Symbolication::Unresolved{raw}); }
}