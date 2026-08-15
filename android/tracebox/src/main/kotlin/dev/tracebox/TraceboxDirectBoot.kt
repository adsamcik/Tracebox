package dev.tracebox

import android.content.Context
import android.os.Build
import android.os.SystemClock
import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.directboot.DenyMirror
import dev.tracebox.directboot.DirectBootActivationStatus
import dev.tracebox.directboot.DirectBootLayout
import dev.tracebox.directboot.DirectBootManager
import dev.tracebox.directboot.DirectBootMutation
import dev.tracebox.directboot.DirectBootStorageMutationGuard
import dev.tracebox.directboot.DirectBootWriteResult
import dev.tracebox.directboot.GeneratedDirectBootRecord
import dev.tracebox.directboot.GeneratedDirectBootSchemaFingerprint
import dev.tracebox.storage.ExistingUidStorageMutationLease
import dev.tracebox.storage.TraceboxOwnedStorageRoot
import java.util.concurrent.atomic.AtomicLong

/**
 * Outcomes from the bounded, generated-only Direct Boot emergency capture entry point.
 *
 * The call never enables diagnostics, creates storage, reads credential-protected data, or accepts
 * an arbitrary payload. [Tracebox.install] must previously have completed an unlocked explicit
 * setup with `setDirectBootC0Enabled(true)`.
 */
enum class TraceboxDirectBootWriteResult {
    WRITTEN,
    ALREADY_PRESENT,
    DENIED,
    DISABLED,
    NOT_ACTIVATED,
    INVALID_ACTIVATION,
    POLICY_MISMATCH,
    QUOTA_EXHAUSTED,
    STORAGE_INELIGIBLE,
    INVALID_STORAGE,
    UNSUPPORTED_ANDROID_VERSION,
}

/**
 * Locked-boot-safe C0 emergency capture.
 *
 * This intentionally exposes only the fixed numeric fields of the generated
 * `EmergencyRecord`. It is suitable for a `directBootAware` component and is independent of the
 * ordinary [Tracebox.install] process singleton.
 */
object TraceboxDirectBoot {
    /**
     * Appends one generated emergency record to the preallocated device-protected store.
     *
     * [processRole] must match the stable role assigned to this process by the host. Handler role
     * `2` is reserved by Tracebox. [threadRole] is a host-defined non-negative closed code.
     */
    @JvmStatic
    fun recordEmergency(
        context: Context,
        signalNumber: Int,
        signalCode: Int,
        processRole: Int,
        threadRole: Int,
        flags: Long = 0L,
    ): TraceboxDirectBootWriteResult {
        require(processRole > 0 && processRole != HANDLER_PROCESS_ROLE)
        require(threadRole >= 0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return TraceboxDirectBootWriteResult.UNSUPPORTED_ANDROID_VERSION
        }

        val paths = DirectBootLayout.fromDeviceProtectedContext(context)
        val guard = DirectBootStorageMutationGuard { request, mutation ->
            if (request.operation != DirectBootMutation.APPEND ||
                request.recordsPath.toAbsolutePath().normalize() !=
                paths.records.toAbsolutePath().normalize()
            ) {
                false
            } else {
                val lease = ExistingUidStorageMutationLease.tryAcquire(
                    paths.root,
                    LOCK_TIMEOUT_MILLIS,
                )
                if (lease == null) {
                    false
                } else {
                    lease.use {
                        if (!TraceboxOwnedStorageRoot.isEligible(paths.root)) {
                            false
                        } else {
                            mutation()
                            true
                        }
                    }
                }
            }
        }
        val manager = DirectBootManager.fromDeviceProtectedContext(
            context,
            GeneratedDirectBootSchemaFingerprint.bytes(),
            guard,
        )
        val policy = runCatching {
            DenyMirror(paths.activeDeny, paths.pendingDeny).effective()
        }.getOrNull() ?: return TraceboxDirectBootWriteResult.DISABLED
        if (policy.disabled) return TraceboxDirectBootWriteResult.DISABLED

        val generated = GeneratedDirectBootRecord.fromEmergency(
            GeneratedDirectBootSchemaFingerprint.bytes(),
            GeneratedEmergencyRecord(
                slot_sequence = nextSlotSequence(),
                policy_epoch = policy.epoch.toULong(),
                signal_number = signalNumber,
                signal_code = signalCode,
                process_role = processRole.toUInt(),
                thread_role = threadRole.toUInt(),
                flags = flags.toULong(),
            ),
            elapsedMillis = SystemClock.elapsedRealtime(),
            readinessCode = LOCKED_BOOT_READINESS_CODE,
        )
        val capture = manager.openCapture() ?: return when (manager.activationStatus()) {
            DirectBootActivationStatus.ABSENT,
            DirectBootActivationStatus.DISABLED,
            -> TraceboxDirectBootWriteResult.NOT_ACTIVATED

            DirectBootActivationStatus.INVALID ->
                TraceboxDirectBootWriteResult.INVALID_ACTIVATION

            DirectBootActivationStatus.ACTIVE ->
                TraceboxDirectBootWriteResult.INVALID_STORAGE
        }
        return mapDirectBootWriteResult(capture.appendGenerated(generated))
    }

    private fun nextSlotSequence(): ULong {
        val now = maxOf(1L, SystemClock.elapsedRealtimeNanos())
        return sequence.updateAndGet { previous ->
            if (previous >= Long.MAX_VALUE - 1L) now else maxOf(now, previous + 1L)
        }.toULong()
    }

    private const val HANDLER_PROCESS_ROLE = 2
    private const val LOCKED_BOOT_READINESS_CODE = 1
    private const val LOCK_TIMEOUT_MILLIS = 250L
    private val sequence = AtomicLong()
}

internal fun mapDirectBootWriteResult(
    result: DirectBootWriteResult,
): TraceboxDirectBootWriteResult = when (result) {
    DirectBootWriteResult.WRITTEN -> TraceboxDirectBootWriteResult.WRITTEN
    DirectBootWriteResult.ALREADY_PRESENT -> TraceboxDirectBootWriteResult.ALREADY_PRESENT
    DirectBootWriteResult.DENIED -> TraceboxDirectBootWriteResult.DENIED
    DirectBootWriteResult.DISABLED -> TraceboxDirectBootWriteResult.DISABLED
    DirectBootWriteResult.NOT_ACTIVATED -> TraceboxDirectBootWriteResult.NOT_ACTIVATED
    DirectBootWriteResult.INVALID_ACTIVATION ->
        TraceboxDirectBootWriteResult.INVALID_ACTIVATION
    DirectBootWriteResult.POLICY_MISMATCH -> TraceboxDirectBootWriteResult.POLICY_MISMATCH
    DirectBootWriteResult.QUOTA_EXHAUSTED -> TraceboxDirectBootWriteResult.QUOTA_EXHAUSTED
    DirectBootWriteResult.STORAGE_INELIGIBLE ->
        TraceboxDirectBootWriteResult.STORAGE_INELIGIBLE
    DirectBootWriteResult.INVALID_STORAGE -> TraceboxDirectBootWriteResult.INVALID_STORAGE
}
