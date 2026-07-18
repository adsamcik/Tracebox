//! Generated raw ABI declarations for the versioned Tracebox native surface.

#![deny(missing_docs)]

#[allow(missing_docs)]
mod generated;

pub use generated::{EventId, SCHEMA_FINGERPRINT};

/// Exact C layout for every ABI-visible structure prefix.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct HeaderV1 {
    /// Byte size supplied by the caller.
    pub struct_size: u32,
    /// ABI version supplied by the caller.
    pub abi_version: u32,
}

/// Raw typed native status values.
#[repr(u32)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StatusV1 {
    /// Operation succeeded.
    Ok = 0,
    /// Recorder is not ready.
    NotReady = 1,
    /// Bounded recorder dropped the record.
    Dropped = 2,
    /// The caller used an unsupported ABI version.
    UnsupportedVersion = 3,
    /// A pointer, length, or struct-size contract was invalid.
    InvalidArgument = 4,
}

/// Raw bounded breadcrumb ABI input.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BreadcrumbV1 {
    /// Required ABI header.
    pub header: HeaderV1,
    /// Generated breadcrumb code.
    pub code: u32,
    /// Reserved append-only field.
    pub reserved_flags: u32,
}
