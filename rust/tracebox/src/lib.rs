//! Safe bounded wrappers around Tracebox C/JNI boundaries.

#![deny(missing_docs)]

use std::panic::{AssertUnwindSafe, catch_unwind};

pub use tracebox_sys::{BreadcrumbV1, HeaderV1, StatusV1};

/// A safe recorder boundary. Implementors may bridge to C or JNI.
pub trait NativeBreadcrumbRecorder {
    /// Records the bounded ABI value and returns the native typed status.
    fn record_breadcrumb(&self, value: BreadcrumbV1) -> StatusV1;
}

/// Invokes a C/JNI boundary while containing an unwind.
///
/// In `panic = "abort"` builds, Rust aborts through the common native crash path
/// before it can unwind. In unwind-enabled builds this function returns
/// [`StatusV1::Dropped`] instead of allowing a panic to cross the boundary.
pub fn record_breadcrumb(
    recorder: &dyn NativeBreadcrumbRecorder,
    value: BreadcrumbV1,
) -> StatusV1 {
    catch_unwind(AssertUnwindSafe(|| recorder.record_breadcrumb(value)))
        .unwrap_or(StatusV1::Dropped)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct PanickingRecorder;

    impl NativeBreadcrumbRecorder for PanickingRecorder {
        fn record_breadcrumb(&self, _: BreadcrumbV1) -> StatusV1 {
            panic!("test boundary panic");
        }
    }

    #[test]
    fn panic_does_not_cross_the_c_boundary() {
        let value = BreadcrumbV1 {
            header: HeaderV1 {
                struct_size: 16,
                abi_version: 1,
            },
            code: 1,
            monotonic_time_ns: 0,
        };
        assert_eq!(record_breadcrumb(&PanickingRecorder, value), StatusV1::Dropped);
    }
}
