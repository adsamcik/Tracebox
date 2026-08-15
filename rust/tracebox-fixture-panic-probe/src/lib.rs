//! Destructive Rust-panic certification probe linked only into the consolidated fixture APK.

#![deny(missing_docs)]

use std::ffi::c_void;
use std::sync::Mutex;

#[cfg(panic = "unwind")]
use std::panic::{AssertUnwindSafe, catch_unwind};
#[cfg(panic = "unwind")]
use std::sync::Arc;
#[cfg(panic = "unwind")]
use tracebox::{NativePanicRecordSink, install_bounded_panic_hook};

const STATUS_SUCCESS: u8 = 1;
#[cfg(not(panic = "unwind"))]
const STATUS_UNWIND_REQUIRED: u8 = 2;
const STATUS_PANIC_NOT_CAUGHT: u8 = 3;
const STATUS_RECORD_MISSING: u8 = 4;
const STATUS_RECORD_INVALID: u8 = 5;

const STATUS_SHIFT: u32 = 0;
const PAYLOAD_SHIFT: u32 = 8;
const LOCATION_SHIFT: u32 = 16;
const FLAGS_SHIFT: u32 = 48;

const FLAG_PANIC_CAUGHT: u8 = 1;
const FLAG_LOCATION_PRESENT: u8 = 1 << 1;
const FLAG_SINGLE_RING_RECORD: u8 = 1 << 2;

static PROBE_LOCK: Mutex<()> = Mutex::new(());

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct ProbeMetadata {
    status: u8,
    payload_kind: u8,
    location_code: u32,
    flags: u8,
}

impl ProbeMetadata {
    const fn failure(status: u8) -> Self {
        Self {
            status,
            payload_kind: 0,
            location_code: 0,
            flags: 0,
        }
    }

    const fn pack(self) -> u64 {
        (self.status as u64) << STATUS_SHIFT
            | (self.payload_kind as u64) << PAYLOAD_SHIFT
            | (self.location_code as u64) << LOCATION_SHIFT
            | (self.flags as u64) << FLAGS_SHIFT
    }
}

/// Runs the real bounded Tracebox panic hook and ring, returning only packed scalar metadata.
///
/// The JNI receiver and environment are intentionally opaque and unused. No pointer or panic
/// payload crosses the boundary.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_tracebox_phase0_LabRustPanicProbe_nativeRunBoundedPanicProbe(
    _environment: *mut c_void,
    _receiver: *mut c_void,
) -> i64 {
    run_probe().pack().cast_signed()
}

#[cfg(panic = "unwind")]
fn run_probe() -> ProbeMetadata {
    let _guard = PROBE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    while tracebox_sys::take_panic_record_v1().is_some() {}
    install_bounded_panic_hook(Arc::new(NativePanicRecordSink));

    let caught = catch_unwind(AssertUnwindSafe(|| {
        panic!("fixture-only bounded Rust panic probe");
    }))
    .is_err();
    if !caught {
        return ProbeMetadata::failure(STATUS_PANIC_NOT_CAUGHT);
    }

    let Some(record) = tracebox_sys::take_panic_record_v1() else {
        return ProbeMetadata::failure(STATUS_RECORD_MISSING);
    };
    if tracebox_sys::take_panic_record_v1().is_some()
        || record.payload_kind > 2
        || record.has_location > 1
        || (record.has_location == 0 && (record.line != 0 || record.column != 0))
    {
        return ProbeMetadata::failure(STATUS_RECORD_INVALID);
    }

    let mut flags = FLAG_PANIC_CAUGHT | FLAG_SINGLE_RING_RECORD;
    let location_code = if record.has_location == 1 {
        flags |= FLAG_LOCATION_PRESENT;
        derive_location_code(record.line, record.column)
    } else {
        0
    };
    ProbeMetadata {
        status: STATUS_SUCCESS,
        payload_kind: u8::try_from(record.payload_kind).unwrap_or_default(),
        location_code,
        flags,
    }
}

#[cfg(not(panic = "unwind"))]
fn run_probe() -> ProbeMetadata {
    let _guard = PROBE_LOCK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    ProbeMetadata::failure(STATUS_UNWIND_REQUIRED)
}

const fn derive_location_code(line: u32, column: u32) -> u32 {
    line.rotate_left(11) ^ column
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[cfg(panic = "unwind")]
    fn real_hook_catches_panic_and_returns_one_bounded_ring_record() {
        let metadata = run_probe();
        assert_eq!(metadata.status, STATUS_SUCCESS);
        assert_eq!(metadata.payload_kind, 1);
        assert_ne!(metadata.location_code, 0);
        assert_eq!(
            metadata.flags,
            FLAG_PANIC_CAUGHT | FLAG_LOCATION_PRESENT | FLAG_SINGLE_RING_RECORD,
        );
        assert!(tracebox_sys::take_panic_record_v1().is_none());
    }

    #[test]
    fn packed_contract_has_only_the_documented_scalar_fields() {
        let packed = ProbeMetadata {
            status: 1,
            payload_kind: 2,
            location_code: 0x3456_789a,
            flags: 7,
        }
        .pack();
        assert_eq!(packed & 0xff, 1);
        assert_eq!((packed >> PAYLOAD_SHIFT) & 0xff, 2);
        assert_eq!((packed >> LOCATION_SHIFT) & 0xffff_ffff, 0x3456_789a);
        assert_eq!((packed >> FLAGS_SHIFT) & 0xff, 7);
        assert_eq!(packed >> 56, 0);
    }
}
