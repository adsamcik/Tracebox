//! Android static-library bridge for Rust-owned identities and bounded panic metadata.

#![deny(missing_docs)]

use std::io;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};

use tracebox::{NativePanicRecordSink, PanicRecord, PanicRecordSink, install_bounded_panic_hook};
use tracebox_identity::{
    CoordinatorBootSessionId, IdentityAllocator, IdentityJournal, IdentityKind, OrdinarySegmentId,
    ProcessInstanceId, RawArtifactId, SnapshotId, SummarySpoolSegmentId, canonical_summary_id,
};

const STATUS_OK: u32 = 0;
const STATUS_INVALID_ARGUMENT: u32 = 1;
const STATUS_ALLOCATION_FAILED: u32 = 2;
const MAX_MINIDUMP_BYTES: usize = 16 * 1024 * 1024;
#[cfg(any(target_os = "android", test))]
const PANIC_SLOT_SIZE: usize = 64;
#[cfg(any(target_os = "android", test))]
const PANIC_SLOT_COMPLETION: u64 = 0x5442_5255_5354_434f;

/// Fixed C-compatible result returned without exposing a pointer across the ABI.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct IdentityResultV1 {
    /// Zero on success; otherwise a stable failure code.
    pub status: u32,
    /// The candidate identity. It is usable by Kotlin only after native durable journaling.
    pub bytes: [u8; 32],
}

/// Frozen tuple used for Rust-owned canonical summary-ID derivation.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SummaryInputV1 {
    /// Journaled raw-artifact identity.
    pub raw_artifact_id: [u8; 32],
    /// Frozen extractor implementation version.
    pub extractor_version: u32,
    /// Generated event-schema fingerprint.
    pub schema_fingerprint: [u8; 32],
    /// SHA-256 of the canonical ID-free summary body.
    pub canonical_content_sha256: [u8; 32],
}

/// One bounded Rust panic record drained outside the panic path.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PanicDrainV1 {
    /// One when a record was returned, zero when the ring was empty.
    pub has_record: u32,
    /// Opaque/static-string/owned-string classification.
    pub payload_kind: u32,
    /// One when the Rust runtime supplied a source location.
    pub has_location: u32,
    /// Source line or zero.
    pub line: u32,
    /// Source column or zero.
    pub column: u32,
}

/// ID-free structural result returned by the bounded Rust minidump parser.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MinidumpSummaryV1 {
    /// Zero on success; otherwise the minidump was invalid or outside its hard bound.
    pub status: u32,
    /// Number of directory streams.
    pub stream_count: u32,
    /// Declared thread count or zero.
    pub thread_count: u32,
    /// Declared module count or zero.
    pub module_count: u32,
    /// Exception code or zero.
    pub exception_code: u32,
    /// Processor architecture or zero.
    pub processor_architecture: u16,
    /// One only when the exact allowlisted stream profile is valid.
    pub stream_profile_valid: u16,
}

#[derive(Default)]
struct CandidateJournal {
    expected: Option<IdentityKind>,
    bytes: Option<Vec<u8>>,
}

impl IdentityJournal for CandidateJournal {
    fn persist(&mut self, kind: IdentityKind, bytes: &[u8]) -> io::Result<()> {
        self.expected = Some(kind);
        self.bytes = Some(bytes.to_vec());
        Ok(())
    }
}

static ALLOCATOR: LazyLock<Mutex<IdentityAllocator>> =
    LazyLock::new(|| Mutex::new(IdentityAllocator::default()));
static PANIC_SLOT_FD: AtomicI32 = AtomicI32::new(-1);
static PANIC_EPOCH: AtomicU64 = AtomicU64::new(0);
static PANIC_ROLE: AtomicU32 = AtomicU32::new(0);
static PANIC_ENABLED: AtomicBool = AtomicBool::new(false);
static PANIC_RECORDING: AtomicBool = AtomicBool::new(false);

struct AndroidPanicRecordSink;

impl PanicRecordSink for AndroidPanicRecordSink {
    fn record(&self, record: PanicRecord) {
        // The durable, pre-opened fixed slot is always attempted before the process-local ring.
        // Both records are scalar/borrowed values; neither step formats or owns panic text.
        persist_panic_record(record);
        NativePanicRecordSink.record(record);
    }
}

/// Produces a typed Rust identity candidate.
///
/// The C++ JNI bridge must durably create its lifecycle journal before returning these bytes to
/// Kotlin. This split keeps randomness/type ownership in Rust and Android filesystem ownership in
/// the native runtime.
#[unsafe(no_mangle)]
pub extern "C" fn tb_android_allocate_identity_v1(kind: u32) -> IdentityResultV1 {
    let mut allocator = ALLOCATOR
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut journal = CandidateJournal::default();
    let result = match kind {
        1 => allocator
            .process_instance(&mut journal)
            .map(ProcessInstanceId::as_bytes),
        2 => allocator
            .ordinary_segment(&mut journal)
            .map(OrdinarySegmentId::as_bytes),
        3 => allocator
            .raw_artifact(&mut journal)
            .map(RawArtifactId::as_bytes),
        4 => allocator
            .summary_spool_segment(&mut journal)
            .map(SummarySpoolSegmentId::as_bytes),
        5 => allocator.snapshot(&mut journal).map(SnapshotId::as_bytes),
        6 => allocator
            .coordinator_boot_session(&mut journal)
            .map(CoordinatorBootSessionId::as_bytes),
        _ => {
            return IdentityResultV1 {
                status: STATUS_INVALID_ARGUMENT,
                bytes: [0; 32],
            };
        }
    };
    match result {
        Ok(bytes) => IdentityResultV1 {
            status: STATUS_OK,
            bytes,
        },
        Err(_) => IdentityResultV1 {
            status: STATUS_ALLOCATION_FAILED,
            bytes: [0; 32],
        },
    }
}

/// Derives the exact canonical summary identity in Rust.
///
/// As with random identities, the native caller must journal the tuple and result durably before
/// exposing the returned bytes to Kotlin.
#[unsafe(no_mangle)]
pub extern "C" fn tb_android_summary_id_v1(input: SummaryInputV1) -> IdentityResultV1 {
    IdentityResultV1 {
        status: STATUS_OK,
        bytes: canonical_summary_id(
            input.raw_artifact_id,
            input.extractor_version,
            input.schema_fingerprint,
            input.canonical_content_sha256,
        ),
    }
}

/// Installs the bounded Rust hook that writes the durable fixed slot before a nonblocking,
/// best-effort in-memory ring.
#[unsafe(no_mangle)]
pub extern "C" fn tb_android_install_panic_hook_v1() {
    install_bounded_panic_hook(Arc::new(AndroidPanicRecordSink));
}

/// Configures the pre-opened, fixed panic slot owned by the Android native runtime.
///
/// Disabling or replacing a slot waits for an already-admitted panic write to finish before this
/// function returns. The native owner may therefore close or reuse the previous descriptor only
/// after a successful call.
#[unsafe(no_mangle)]
pub extern "C" fn tb_android_configure_panic_slot_v1(
    file_descriptor: i32,
    epoch: u64,
    process_role: u32,
    enabled: u32,
) -> u32 {
    if enabled > 1 || (enabled == 1 && (file_descriptor < 0 || epoch == 0)) {
        return STATUS_INVALID_ARGUMENT;
    }

    // Close admission before waiting. Taking the same gate as the panic path avoids a
    // check-then-close race: once acquired, every earlier writer has completed and every later
    // writer must observe the state published below.
    PANIC_ENABLED.store(false, Ordering::Release);
    while PANIC_RECORDING
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        std::thread::yield_now();
    }
    PANIC_SLOT_FD.store(file_descriptor, Ordering::Release);
    PANIC_EPOCH.store(epoch, Ordering::Release);
    PANIC_ROLE.store(process_role, Ordering::Release);
    PANIC_ENABLED.store(enabled == 1, Ordering::Release);
    PANIC_RECORDING.store(false, Ordering::Release);
    STATUS_OK
}

/// Drains at most one fixed panic record for policy-gated Kotlin persistence.
#[unsafe(no_mangle)]
pub extern "C" fn tb_android_drain_panic_v1() -> PanicDrainV1 {
    match tracebox_sys::take_panic_record_v1() {
        Some(value) => PanicDrainV1 {
            has_record: 1,
            payload_kind: value.payload_kind,
            has_location: value.has_location,
            line: value.line,
            column: value.column,
        },
        None => PanicDrainV1 {
            has_record: 0,
            payload_kind: 0,
            has_location: 0,
            line: 0,
            column: 0,
        },
    }
}

/// Parses a bounded Crashpad minidump into an ID-free structural summary.
///
/// # Safety
///
/// `bytes` must point to `length` readable bytes for this call. The native bridge validates its
/// file read and hard size limit before invoking this function.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn tb_android_summarize_minidump_v1(
    bytes: *const u8,
    length: usize,
) -> MinidumpSummaryV1 {
    let invalid = || MinidumpSummaryV1 {
        status: STATUS_INVALID_ARGUMENT,
        stream_count: 0,
        thread_count: 0,
        module_count: 0,
        exception_code: 0,
        processor_architecture: 0,
        stream_profile_valid: 0,
    };
    if bytes.is_null() || length == 0 || length > MAX_MINIDUMP_BYTES {
        return invalid();
    }
    // SAFETY: The caller contract above requires a readable range and the native bridge owns the
    // backing buffer until this synchronous function returns.
    let input = unsafe { std::slice::from_raw_parts(bytes, length) };
    match tracebox_phase0::summarize_minidump(input) {
        Ok(summary) => MinidumpSummaryV1 {
            status: STATUS_OK,
            stream_count: summary.streams.len().try_into().unwrap_or(u32::MAX),
            thread_count: summary.thread_count.unwrap_or(0),
            module_count: summary.module_count.unwrap_or(0),
            exception_code: summary.exception_code.unwrap_or(0),
            processor_architecture: summary.processor_architecture.unwrap_or(0),
            stream_profile_valid: u16::from(summary.stream_profile_valid()),
        },
        Err(_) => invalid(),
    }
}

fn persist_panic_record(record: PanicRecord) {
    if PANIC_RECORDING
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        return;
    }
    if !PANIC_ENABLED.load(Ordering::Acquire) {
        PANIC_RECORDING.store(false, Ordering::Release);
        return;
    }
    persist_panic_record_once(record);
    PANIC_RECORDING.store(false, Ordering::Release);
}

#[cfg(target_os = "android")]
fn persist_panic_record_once(record: PanicRecord) {
    let file_descriptor = PANIC_SLOT_FD.load(Ordering::Acquire);
    if file_descriptor < 0 {
        return;
    }
    let bytes = encode_panic_slot(
        record,
        PANIC_EPOCH.load(Ordering::Acquire),
        PANIC_ROLE.load(Ordering::Acquire),
    );
    // SAFETY: The descriptor is pre-opened and owned for the lifetime of the installed native
    // capture runtime; the fixed stack buffer remains readable for the synchronous calls.
    unsafe {
        if libc::pwrite(file_descriptor, bytes.as_ptr().cast(), bytes.len(), 0)
            == isize::try_from(bytes.len()).unwrap_or(isize::MAX)
        {
            libc::fdatasync(file_descriptor);
        }
    }
}

#[cfg(not(target_os = "android"))]
fn persist_panic_record_once(_: PanicRecord) {}

#[cfg(any(target_os = "android", test))]
fn encode_panic_slot(record: PanicRecord, epoch: u64, process_role: u32) -> [u8; PANIC_SLOT_SIZE] {
    let mut bytes = [0_u8; PANIC_SLOT_SIZE];
    bytes[0..8].copy_from_slice(b"TBRUSTP1");
    bytes[8..12].copy_from_slice(&1_u32.to_le_bytes());
    bytes[12..16].copy_from_slice(
        &u32::try_from(PANIC_SLOT_SIZE)
            .unwrap_or(u32::MAX)
            .to_le_bytes(),
    );
    bytes[16..24].copy_from_slice(&epoch.to_le_bytes());
    bytes[24..28].copy_from_slice(&process_role.to_le_bytes());
    let payload = match record.payload {
        tracebox::PanicPayloadKind::Opaque => 0_u32,
        tracebox::PanicPayloadKind::StaticString => 1,
        tracebox::PanicPayloadKind::String => 2,
    };
    bytes[28..32].copy_from_slice(&payload.to_le_bytes());
    if let Some(location) = &record.location {
        bytes[32..36].copy_from_slice(&1_u32.to_le_bytes());
        bytes[36..40].copy_from_slice(&location.line.to_le_bytes());
        bytes[40..44].copy_from_slice(&location.column.to_le_bytes());
        bytes[44..48].copy_from_slice(&location.file_hash.to_le_bytes());
    }
    let checksum = crc32c(&bytes[..52]);
    bytes[52..56].copy_from_slice(&checksum.to_le_bytes());
    bytes[56..64].copy_from_slice(&PANIC_SLOT_COMPLETION.to_le_bytes());
    bytes
}

#[cfg(any(target_os = "android", test))]
fn crc32c(bytes: &[u8]) -> u32 {
    let mut crc = !0_u32;
    for byte in bytes {
        crc ^= u32::from(*byte);
        for _ in 0..8 {
            let mask = 0_u32.wrapping_sub(crc & 1);
            crc = (crc >> 1) ^ (0x82f6_3b78 & mask);
        }
    }
    !crc
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::alloc::{GlobalAlloc, Layout, System};
    use std::cell::Cell;
    use std::sync::mpsc::{self, TryRecvError};
    use std::time::{Duration, Instant};
    use tracebox::{PanicLocation, PanicPayloadKind};
    use tracebox_sys::{HeaderV1, PanicRecordV1, StatusV1, tb_tracebox_record_panic_v1};

    static PANIC_SLOT_TEST_LOCK: Mutex<()> = Mutex::new(());

    std::thread_local! {
        static COUNT_ALLOCATIONS: Cell<bool> = const { Cell::new(false) };
        static ALLOCATION_COUNT: Cell<usize> = const { Cell::new(0) };
    }

    struct TestAllocator;

    #[global_allocator]
    static TEST_ALLOCATOR: TestAllocator = TestAllocator;

    unsafe impl GlobalAlloc for TestAllocator {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            note_allocation();
            // SAFETY: Delegates the allocator contract unchanged to the system allocator.
            unsafe { System.alloc(layout) }
        }

        unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
            note_allocation();
            // SAFETY: Delegates the allocator contract unchanged to the system allocator.
            unsafe { System.alloc_zeroed(layout) }
        }

        unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
            // SAFETY: `pointer` and `layout` came from this allocator, which delegates to System.
            unsafe { System.dealloc(pointer, layout) }
        }

        unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
            note_allocation();
            // SAFETY: The allocation originated from System and the contract is forwarded intact.
            unsafe { System.realloc(pointer, layout, new_size) }
        }
    }

    fn note_allocation() {
        if COUNT_ALLOCATIONS.try_with(Cell::get).unwrap_or(false) {
            let _ = ALLOCATION_COUNT.try_with(|count| count.set(count.get().saturating_add(1)));
        }
    }

    fn count_allocations<T>(operation: impl FnOnce() -> T) -> (T, usize) {
        ALLOCATION_COUNT.with(|count| count.set(0));
        COUNT_ALLOCATIONS.with(|enabled| enabled.set(true));
        let result = operation();
        COUNT_ALLOCATIONS.with(|enabled| enabled.set(false));
        let count = ALLOCATION_COUNT.with(Cell::get);
        (result, count)
    }

    fn fixed_panic_record() -> PanicRecord {
        PanicRecord {
            payload: PanicPayloadKind::StaticString,
            location: Some(PanicLocation {
                file_hash: 0x1234_5678,
                line: 19,
                column: 7,
            }),
        }
    }

    #[test]
    fn typed_random_candidates_are_bounded_and_distinct() {
        let first = tb_android_allocate_identity_v1(1);
        let second = tb_android_allocate_identity_v1(2);
        assert_eq!(first.status, STATUS_OK);
        assert_eq!(second.status, STATUS_OK);
        assert_ne!(first.bytes, [0; 32]);
        assert_ne!(first.bytes, second.bytes);
        assert_eq!(
            tb_android_allocate_identity_v1(999).status,
            STATUS_INVALID_ARGUMENT,
        );
    }

    #[test]
    fn summary_derivation_uses_the_frozen_rust_contract() {
        let input = SummaryInputV1 {
            raw_artifact_id: [1; 32],
            extractor_version: 7,
            schema_fingerprint: [2; 32],
            canonical_content_sha256: [3; 32],
        };
        assert_eq!(
            tb_android_summary_id_v1(input).bytes,
            canonical_summary_id(
                input.raw_artifact_id,
                input.extractor_version,
                input.schema_fingerprint,
                input.canonical_content_sha256,
            ),
        );
    }

    #[test]
    fn panic_drain_returns_one_bounded_record_then_empty() {
        while tb_android_drain_panic_v1().has_record != 0 {}
        assert_eq!(
            tb_tracebox_record_panic_v1(PanicRecordV1 {
                header: HeaderV1 {
                    struct_size: 24,
                    abi_version: 1,
                },
                payload_kind: 1,
                has_location: 1,
                line: 9,
                column: 4,
            }),
            StatusV1::Ok,
        );
        let record = tb_android_drain_panic_v1();
        assert_eq!(record.has_record, 1);
        assert_eq!(record.payload_kind, 1);
        assert_eq!(tb_android_drain_panic_v1().has_record, 0);
    }

    #[test]
    fn panic_slot_reconfiguration_waits_for_an_admitted_write_before_descriptor_reuse() {
        let _test_guard = PANIC_SLOT_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        assert_eq!(tb_android_configure_panic_slot_v1(17, 1, 2, 1), STATUS_OK);

        // Model a panic writer after admission and before its fixed pwrite/fdatasync completes.
        PANIC_RECORDING.store(true, Ordering::Release);
        let (entered_sender, entered_receiver) = mpsc::channel();
        let (done_sender, done_receiver) = mpsc::channel();
        let reconfigure = std::thread::spawn(move || {
            entered_sender.send(()).expect("send configure entry");
            let status = tb_android_configure_panic_slot_v1(-1, 0, 0, 0);
            done_sender.send(status).expect("send configure result");
        });
        entered_receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("configure thread entered");

        let deadline = Instant::now() + Duration::from_secs(5);
        while PANIC_ENABLED.load(Ordering::Acquire) && Instant::now() < deadline {
            std::thread::yield_now();
        }
        assert!(!PANIC_ENABLED.load(Ordering::Acquire));
        assert_eq!(PANIC_SLOT_FD.load(Ordering::Acquire), 17);
        assert_eq!(done_receiver.try_recv(), Err(TryRecvError::Empty));

        PANIC_RECORDING.store(false, Ordering::Release);
        assert_eq!(
            done_receiver
                .recv_timeout(Duration::from_secs(5))
                .expect("configuration completed after writer release"),
            STATUS_OK,
        );
        reconfigure.join().expect("join configure thread");
        assert_eq!(PANIC_SLOT_FD.load(Ordering::Acquire), -1);
        assert_eq!(PANIC_EPOCH.load(Ordering::Acquire), 0);
        assert_eq!(PANIC_ROLE.load(Ordering::Acquire), 0);
        assert!(!PANIC_ENABLED.load(Ordering::Acquire));
        assert!(!PANIC_RECORDING.load(Ordering::Acquire));
    }

    #[test]
    fn fixed_panic_slot_encoding_has_scalar_input_and_fixed_complete_output() {
        fn require_copy<T: Copy>() {}
        require_copy::<PanicRecord>();

        let encoded = encode_panic_slot(fixed_panic_record(), 41, 3);
        assert_eq!(&encoded[0..8], b"TBRUSTP1");
        assert_eq!(
            u64::from_le_bytes(encoded[16..24].try_into().expect("fixed epoch field")),
            41,
        );
        assert_eq!(
            u32::from_le_bytes(encoded[24..28].try_into().expect("fixed role field")),
            3,
        );
        assert_eq!(
            u32::from_le_bytes(encoded[28..32].try_into().expect("fixed payload field")),
            1,
        );
        assert_eq!(
            u32::from_le_bytes(encoded[44..48].try_into().expect("fixed path-hash field")),
            0x1234_5678,
        );
        assert_eq!(
            u32::from_le_bytes(encoded[52..56].try_into().expect("fixed checksum field")),
            crc32c(&encoded[..52]),
        );
        assert_eq!(
            u64::from_le_bytes(
                encoded[56..64]
                    .try_into()
                    .expect("fixed completion marker field"),
            ),
            PANIC_SLOT_COMPLETION,
        );
    }

    #[test]
    fn production_panic_sink_and_fixed_slot_encoding_allocate_nothing() {
        let (_, encode_allocations) =
            count_allocations(|| encode_panic_slot(fixed_panic_record(), 41, 3));
        assert_eq!(encode_allocations, 0);

        while tb_android_drain_panic_v1().has_record != 0 {}
        let ((), sink_allocations) =
            count_allocations(|| AndroidPanicRecordSink.record(fixed_panic_record()));
        assert_eq!(sink_allocations, 0);
        while tb_android_drain_panic_v1().has_record != 0 {}
    }

    #[test]
    fn reentrant_panic_slot_attempt_never_waits_for_or_releases_the_active_writer() {
        let _test_guard = PANIC_SLOT_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        PANIC_RECORDING.store(true, Ordering::Release);

        persist_panic_record(fixed_panic_record());

        assert!(PANIC_RECORDING.load(Ordering::Acquire));
        PANIC_RECORDING.store(false, Ordering::Release);
    }

    #[test]
    fn minidump_bridge_rejects_invalid_and_oversized_inputs() {
        let invalid = b"not-a-minidump";
        // SAFETY: The test slice remains alive for the synchronous call.
        let result = unsafe { tb_android_summarize_minidump_v1(invalid.as_ptr(), invalid.len()) };
        assert_eq!(result.status, STATUS_INVALID_ARGUMENT);
        // SAFETY: A null pointer is explicitly rejected before it can be dereferenced.
        let oversized = unsafe {
            tb_android_summarize_minidump_v1(std::ptr::null(), MAX_MINIDUMP_BYTES.saturating_add(1))
        };
        assert_eq!(oversized.status, STATUS_INVALID_ARGUMENT);
    }
}
