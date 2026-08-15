package dev.tracebox.phase0

/**
 * Fixture-only JNI facade for a real, unwind-contained invocation of Tracebox's bounded Rust
 * panic hook and ring.
 */
internal object LabRustPanicProbe {
    init {
        System.loadLibrary("tracebox_fixture_panic_probe")
    }

    fun capture(): RustPanicProbeMetadata? = decodeRustPanicProbe(
        nativeRunBoundedPanicProbe(),
    )

    private external fun nativeRunBoundedPanicProbe(): Long
}

internal data class RustPanicProbeMetadata(
    val payloadClass: UInt,
    val locationCode: UInt,
    val flags: UInt,
)

internal fun decodeRustPanicProbe(packed: Long): RustPanicProbeMetadata? {
    val raw = packed.toULong()
    if (raw shr RESERVED_SHIFT != 0uL) return null
    val status = (raw and BYTE_MASK).toUInt()
    if (status != STATUS_SUCCESS) return null
    val payloadClass = ((raw shr PAYLOAD_SHIFT) and BYTE_MASK).toUInt()
    if (payloadClass > MAX_PAYLOAD_CLASS) return null
    val flags = ((raw shr FLAGS_SHIFT) and BYTE_MASK).toUInt()
    if (
        flags and REQUIRED_FLAGS != REQUIRED_FLAGS ||
        flags and KNOWN_FLAGS.inv() != 0u
    ) {
        return null
    }
    return RustPanicProbeMetadata(
        payloadClass = payloadClass,
        locationCode = ((raw shr LOCATION_SHIFT) and UINT_MASK).toUInt(),
        flags = flags,
    )
}

private const val STATUS_SUCCESS = 1u
private const val MAX_PAYLOAD_CLASS = 2u
private const val REQUIRED_FLAGS = 7u
private const val KNOWN_FLAGS = 7u
private const val PAYLOAD_SHIFT = 8
private const val LOCATION_SHIFT = 16
private const val FLAGS_SHIFT = 48
private const val RESERVED_SHIFT = 56
private const val BYTE_MASK = 0xffuL
private const val UINT_MASK = 0xffff_ffffuL
