//! Generated raw ABI declarations and exported panic-contained Tracebox native surface.

#![deny(missing_docs)]

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Mutex;

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

/// Bounded structured panic metadata passed from the Rust hook to the native bridge.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PanicRecordV1 {
    /// Required ABI header.
    pub header: HeaderV1,
    /// Bounded payload class: opaque, static string, or owned string.
    pub payload_kind: u32,
    /// Whether a source location was supplied by the runtime.
    pub has_location: u32,
    /// Source line or zero when no location exists.
    pub line: u32,
    /// Source column or zero when no location exists.
    pub column: u32,
}

const HEADER_V1_SIZE: u32 = 8;
const BREADCRUMB_V1_SIZE: u32 = 24;
const PANIC_PROBE_V1_SIZE: u32 = 16;
const PANIC_RECORD_V1_SIZE: u32 = 24;
const PANIC_RECORD_RING_CAPACITY: usize = 64;
const EMPTY_PANIC_RECORD: PanicRecordV1 = PanicRecordV1 {
    header: HeaderV1 {
        struct_size: 0,
        abi_version: 0,
    },
    payload_kind: 0,
    has_location: 0,
    line: 0,
    column: 0,
};

struct PanicRecordRing {
    records: [PanicRecordV1; PANIC_RECORD_RING_CAPACITY],
    head: usize,
    len: usize,
}

impl PanicRecordRing {
    const fn new() -> Self {
        Self {
            records: [EMPTY_PANIC_RECORD; PANIC_RECORD_RING_CAPACITY],
            head: 0,
            len: 0,
        }
    }

    fn push(&mut self, value: PanicRecordV1) -> bool {
        if self.len == PANIC_RECORD_RING_CAPACITY {
            return false;
        }
        let index = (self.head + self.len) % PANIC_RECORD_RING_CAPACITY;
        self.records[index] = value;
        self.len += 1;
        true
    }

    fn drain(&mut self) -> Vec<PanicRecordV1> {
        let mut drained = Vec::with_capacity(self.len);
        for offset in 0..self.len {
            drained.push(self.records[(self.head + offset) % PANIC_RECORD_RING_CAPACITY]);
        }
        self.head = 0;
        self.len = 0;
        drained
    }
}

static PANIC_RECORDS: Mutex<PanicRecordRing> = Mutex::new(PanicRecordRing::new());

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

/// Records bounded panic metadata through the native structured-record bridge.
///
/// The record contains neither payload text nor a backtrace. Unwind-enabled builds contain a
/// bridge panic at this boundary; abort-enabled builds terminate through the native fault path.
#[unsafe(no_mangle)]
pub extern "C" fn tb_tracebox_record_panic_v1(value: PanicRecordV1) -> StatusV1 {
    catch_unwind(AssertUnwindSafe(|| -> Result<StatusV1, StatusV1> {
        valid_header(value.header, PANIC_RECORD_V1_SIZE)?;
        if value.payload_kind > 2 || value.has_location > 1 {
            return Ok(StatusV1::InvalidArgument);
        }
        let mut records = PANIC_RECORDS.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        Ok(if records.push(value) {
            StatusV1::Ok
        } else {
            StatusV1::Dropped
        })
    }))
    .unwrap_or(Ok(StatusV1::Dropped))
    .unwrap_or(StatusV1::Dropped)
}

/// Drains the process-local bounded panic-record sink for a native consumer or host test.
///
/// The returned vector is intentionally outside the panic path; recording itself uses a fixed-size
/// ring and performs no allocation.
pub fn drain_panic_records_v1() -> Vec<PanicRecordV1> {
    PANIC_RECORDS
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .drain()
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

    #[test]
    fn structured_panic_bridge_accepts_only_bounded_metadata() {
        let _ = drain_panic_records_v1();
        assert_eq!(
            tb_tracebox_record_panic_v1(PanicRecordV1 {
                header: HeaderV1 {
                    struct_size: PANIC_RECORD_V1_SIZE,
                    abi_version: 1,
                },
                payload_kind: 1,
                has_location: 1,
                line: 7,
                column: 3,
            }),
            StatusV1::Ok,
        );
        assert_eq!(
            tb_tracebox_record_panic_v1(PanicRecordV1 {
                header: HeaderV1 {
                    struct_size: PANIC_RECORD_V1_SIZE,
                    abi_version: 1,
                },
                payload_kind: 3,
                has_location: 0,
                line: 0,
                column: 0,
            }),
            StatusV1::InvalidArgument,
        );
        assert_eq!(drain_panic_records_v1().len(), 1);
    }
}
