//! Generated raw ABI declarations and exported panic-contained Tracebox native surface.

#![deny(missing_docs)]

use std::panic::{AssertUnwindSafe, catch_unwind};

#[allow(missing_docs)]
mod generated;

pub use generated::*;

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

/// Versioned, size-prefixed input used to prove exported-boundary panic containment.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PanicProbeV1 {
    /// Required ABI header.
    pub header: HeaderV1,
    /// Nonzero requests a deliberately panicking bridge operation.
    pub panic_requested: u32,
    /// Reserved append-only field.
    pub reserved_flags: u32,
}

const HEADER_V1_SIZE: u32 = 8;
const BREADCRUMB_V1_SIZE: u32 = 24;
const PANIC_PROBE_V1_SIZE: u32 = 16;

fn valid_header(header: HeaderV1, expected_size: u32) -> Result<(), StatusV1> {
    if header.abi_version != 1 {
        return Err(StatusV1::UnsupportedVersion);
    }
    if header.struct_size < HEADER_V1_SIZE
        || header.struct_size > expected_size
    {
        return Err(StatusV1::InvalidArgument);
    }
    Ok(())
}

/// Records a size-prefixed breadcrumb through an exported C ABI boundary.
///
/// Every unwind-enabled build catches bridge panics and returns [`StatusV1::Dropped`].
/// With `panic = "abort"`, Rust cannot unwind: it terminates through the common native
/// crash/signal path instead, so no Rust panic crosses this ABI boundary.
#[unsafe(no_mangle)]
pub extern "C" fn tb_tracebox_record_breadcrumb_v1(value: BreadcrumbV1) -> StatusV1 {
    catch_unwind(AssertUnwindSafe(|| -> Result<StatusV1, StatusV1> {
        valid_header(value.header, BREADCRUMB_V1_SIZE)?;
        if value.code == 0 {
            return Ok(StatusV1::Dropped);
        }
        Ok(StatusV1::Ok)
    }))
    .unwrap_or(Ok(StatusV1::Dropped))
    .unwrap_or(StatusV1::Dropped)
}

/// Calls a deliberately fallible bridge operation through the real exported C ABI.
///
/// This test hook is versioned and size-prefixed so `CTest` or Rust integration callers can
/// exercise the same containment pattern. Abort-mode builds use the native crash/signal path.
///
/// # Panics
///
/// Never: the deliberate panic is caught before this exported function returns.
#[unsafe(no_mangle)]
pub extern "C" fn tb_tracebox_panic_containment_probe_v1(value: PanicProbeV1) -> StatusV1 {
    catch_unwind(AssertUnwindSafe(|| -> Result<StatusV1, StatusV1> {
        valid_header(value.header, PANIC_PROBE_V1_SIZE)?;
        assert!(value.panic_requested == 0, "contained exported C ABI probe panic");
        Ok(StatusV1::Ok)
    }))
    .unwrap_or(Ok(StatusV1::Dropped))
    .unwrap_or(StatusV1::Dropped)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exported_c_abi_boundary_contains_bridge_panic() {
        let result = tb_tracebox_panic_containment_probe_v1(PanicProbeV1 {
            header: HeaderV1 {
                struct_size: PANIC_PROBE_V1_SIZE,
                abi_version: 1,
            },
            panic_requested: 1,
            reserved_flags: 0,
        });
        assert_eq!(result, StatusV1::Dropped);
    }

    #[test]
    fn exported_c_abi_boundary_validates_versioned_input() {
        let result = tb_tracebox_record_breadcrumb_v1(BreadcrumbV1 {
            header: HeaderV1 {
                struct_size: HEADER_V1_SIZE,
                abi_version: 1,
            },
            code: 1,
            monotonic_time_ns: 0,
        });
        assert_eq!(result, StatusV1::Ok);
    }
}
