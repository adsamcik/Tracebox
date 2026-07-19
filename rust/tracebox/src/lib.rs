//! Safe bounded wrappers around Tracebox C/JNI boundaries and panic capture.

#![deny(missing_docs)]

use std::panic::PanicHookInfo;
use std::sync::Arc;

#[cfg(panic = "unwind")]
use std::panic::{AssertUnwindSafe, catch_unwind};

pub use tracebox_sys::{BreadcrumbV1, HeaderV1, PanicRecordV1, StatusV1};

/// Compile-time panic behavior used to select the sound boundary path.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PanicStrategy {
    /// Panics unwind and can be contained only at an explicit Rust boundary.
    Unwind,
    /// Panics abort through the process's common native fault path.
    Abort,
}

/// Returns the panic strategy selected for this crate build.
#[must_use]
pub const fn panic_strategy() -> PanicStrategy {
    #[cfg(panic = "unwind")]
    {
        PanicStrategy::Unwind
    }
    #[cfg(panic = "abort")]
    {
        PanicStrategy::Abort
    }
}

/// Bounded kind of a panic payload. Payload content is intentionally never retained.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PanicPayloadKind {
    /// An opaque payload or any payload other than the bounded standard forms.
    Opaque,
    /// A `&'static str` payload, without preserving its contents.
    StaticString,
    /// An owned string payload, without preserving its contents.
    String,
}

/// Bounded source-location fields emitted by the panic hook.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PanicLocation {
    /// Truncated source path.
    pub file: String,
    /// Source line.
    pub line: u32,
    /// Source column.
    pub column: u32,
}

/// Structured panic record that contains no formatted panic text or backtrace.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PanicRecord {
    /// Bounded payload classification.
    pub payload: PanicPayloadKind,
    /// Bounded source location where the runtime supplied one.
    pub location: Option<PanicLocation>,
}

/// Sink invoked synchronously by the installed panic hook.
pub trait PanicRecordSink: Send + Sync + 'static {
    /// Accepts one bounded record before an unwind or abort proceeds.
    fn record(&self, record: PanicRecord);
}

/// Synchronous bridge from the global hook to the bounded native structured-record ABI.
pub struct NativePanicRecordSink;

impl PanicRecordSink for NativePanicRecordSink {
    fn record(&self, record: PanicRecord) {
        let (payload_kind, has_location, line, column) = match record.location {
            Some(location) => (payload_kind(record.payload), 1, location.line, location.column),
            None => (payload_kind(record.payload), 0, 0, 0),
        };
        let _ = tracebox_sys::tb_tracebox_record_panic_v1(PanicRecordV1 {
            header: HeaderV1 {
                struct_size: std::mem::size_of::<PanicRecordV1>() as u32,
                abi_version: 1,
            },
            payload_kind,
            has_location,
            line,
            column,
        });
    }
}

fn payload_kind(payload: PanicPayloadKind) -> u32 {
    match payload {
        PanicPayloadKind::Opaque => 0,
        PanicPayloadKind::StaticString => 1,
        PanicPayloadKind::String => 2,
    }
}

/// Installs a global hook that emits structured, bounded panic metadata before termination.
///
/// The hook deliberately does not format the payload, capture a backtrace, or allocate an
/// unbounded diagnostic string. Installing a later application hook replaces this hook per Rust's
/// process-global panic-hook contract.
pub fn install_bounded_panic_hook(sink: Arc<dyn PanicRecordSink>) {
    std::panic::set_hook(Box::new(move |info| sink.record(bounded_panic_record(info))));
}

fn bounded_panic_record(info: &PanicHookInfo<'_>) -> PanicRecord {
    let payload = if info.payload().is::<&'static str>() {
        PanicPayloadKind::StaticString
    } else if info.payload().is::<String>() {
        PanicPayloadKind::String
    } else {
        PanicPayloadKind::Opaque
    };
    let location = info.location().map(|value| PanicLocation {
        file: value.file().chars().take(160).collect(),
        line: value.line(),
        column: value.column(),
    });
    PanicRecord { payload, location }
}

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
    #[cfg(panic = "unwind")]
    {
        return catch_unwind(AssertUnwindSafe(|| recorder.record_breadcrumb(value)))
            .unwrap_or(StatusV1::Dropped);
    }
    #[cfg(panic = "abort")]
    {
        // An abort cannot be caught. The panic hook runs before the common native fault path.
        recorder.record_breadcrumb(value)
    }
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

    #[cfg(panic = "unwind")]
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

    #[test]
    fn panic_strategy_selects_only_supported_containment() {
        #[cfg(panic = "unwind")]
        assert_eq!(panic_strategy(), PanicStrategy::Unwind);
        #[cfg(panic = "abort")]
        assert_eq!(panic_strategy(), PanicStrategy::Abort);
    }

    #[test]
    fn native_panic_sink_makes_hook_metadata_retrievable_from_the_bounded_bridge_sink() {
        let _ = tracebox_sys::drain_panic_records_v1();
        NativePanicRecordSink.record(PanicRecord {
            payload: PanicPayloadKind::StaticString,
            location: Some(PanicLocation {
                file: "not-sent-over-abi".to_owned(),
                line: 7,
                column: 3,
            }),
        });
        assert_eq!(
            tracebox_sys::drain_panic_records_v1(),
            vec![PanicRecordV1 {
                header: HeaderV1 {
                    struct_size: std::mem::size_of::<PanicRecordV1>() as u32,
                    abi_version: 1,
                },
                payload_kind: 1,
                has_location: 1,
                line: 7,
                column: 3,
            }],
        );
    }
}
