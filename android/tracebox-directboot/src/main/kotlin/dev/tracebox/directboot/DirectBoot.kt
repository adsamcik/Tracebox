package dev.tracebox.directboot

import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.api.Crc32c
import dev.tracebox.core.BarrierAck
import dev.tracebox.core.GlobalPolicyCoordinator
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.ProfileUpdateResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** The separate C0-only Direct Boot schema; it has no C1/C2 fields or generic payload. */
data class C0DirectBootRecord(
    val schemaFingerprint: ByteArray,
    val processRole: Int,
    val elapsedMillis: Long,
    val readinessCode: Int,
    val signalOrExitReason: Int,
    val statusCode: Int,
    /** One or more generated C0 category bits represented by this record. */
    val categoryMask: Long,
) {
    init {
        require(schemaFingerprint.size == 32)
        require(categoryMask != 0L) { "C0 records require a category" }
    }

}

/**
 * Generated-only Direct Boot input. It accepts the schema compiler's C0 EmergencyRecord and
 * exposes no arbitrary payload, text, or privacy-class override.
 */
class GeneratedDirectBootRecord private constructor(
    internal val c0: C0DirectBootRecord,
) {
    companion object {
        fun fromEmergency(
            schemaFingerprint: ByteArray,
            value: GeneratedEmergencyRecord,
            elapsedMillis: Long,
            readinessCode: Int,
            categoryMask: Long,
        ): GeneratedDirectBootRecord = GeneratedDirectBootRecord(
            C0DirectBootRecord(
                schemaFingerprint = schemaFingerprint,
                processRole = value.process_role.toInt(),
                elapsedMillis = elapsedMillis,
                readinessCode = readinessCode,
                signalOrExitReason = value.signal_number,
                statusCode = value.signal_code,
                categoryMask = categoryMask,
            ),
        )
    }
}

/** Immediate typed rejection for any attempted C1/C2 write to device-protected storage. */
enum class DirectBootWriteResult { WRITTEN, REJECTED_NON_C0, DENIED, DISABLED }
enum class PrivacyClass { C0, C1, C2 }

/** Device-protected C0 store whose typed API accepts only [C0DirectBootRecord]. */
class DirectBootStore(private val records: Path, private val mirror: DenyMirror) {
    /** Production entry point: only the generated C0 emergency schema can reach DE persistence. */
    fun appendGenerated(record: GeneratedDirectBootRecord): DirectBootWriteResult = append(record.c0)

    fun append(record: C0DirectBootRecord): DirectBootWriteResult {
        val policy = mirror.effective() ?: return DirectBootWriteResult.DISABLED
        if (policy.disabled) return DirectBootWriteResult.DISABLED
        if ((policy.c0DenyMask and record.categoryMask) != 0L) return DirectBootWriteResult.DENIED
        Files.createDirectories(records.parent)
        FileChannel.open(records, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use {
            it.write(ByteBuffer.wrap(encode(record)))
            it.force(true)
        }
        return DirectBootWriteResult.WRITTEN
    }

    /** Boundary adapter for native/generated callers: non-C0 input fails immediately. */
    fun appendClassified(privacy: PrivacyClass, record: C0DirectBootRecord): DirectBootWriteResult =
        if (privacy != PrivacyClass.C0) DirectBootWriteResult.REJECTED_NON_C0 else append(record)

    private fun encode(record: C0DirectBootRecord): ByteArray =
        ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).put(record.schemaFingerprint)
            .putInt(record.processRole).putLong(record.elapsedMillis).putInt(record.readinessCode)
            .putInt(record.signalOrExitReason).putInt(record.statusCode).putLong(record.categoryMask).array()
}

/** Active/pending deny state. `disabled` and masks are combined conservatively on ambiguity. */
data class DenyState(val epoch: Long, val disabled: Boolean, val c0DenyMask: Long)

/** Fail-closed DE mirror. Absent, corrupt, and newer-version mirrors return null. */
class DenyMirror(private val activePath: Path, private val pendingPath: Path) {
    fun active(): DenyState? = read(activePath)
    fun pending(): DenyState? = read(pendingPath)
    fun effective(): DenyState? {
        val active = active()
        val pending = pending()
        if ((Files.exists(activePath) && active == null) || (Files.exists(pendingPath) && pending == null)) return null
        if (active == null && pending == null) return null
        return mostRestrictive(active, pending)
    }

    fun writePending(state: DenyState) = write(pendingPath, state)
    fun promotePending() {
        val pending = pending() ?: return
        write(activePath, pending)
        Files.deleteIfExists(pendingPath)
    }
    fun clearPending() { Files.deleteIfExists(pendingPath) }

    fun reconcile(ce: DenyState): DenyState {
        val result = mostRestrictive(mostRestrictive(active(), pending()), ce)
        write(activePath, result)
        clearPending()
        return result
    }

    private fun read(path: Path): DenyState? {
        if (!Files.exists(path)) return null
        val bytes = try { Files.readAllBytes(path) } catch (_: java.io.IOException) { return null }
        if (bytes.size != SIZE) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != MAGIC || b.int != VERSION) return null
        val epoch = b.long
        val disabled = b.int != 0
        val mask = b.long
        if (b.int != crc(bytes, 0, SIZE - Int.SIZE_BYTES)) return null
        return DenyState(epoch, disabled, mask)
    }

    private fun write(path: Path, state: DenyState) {
        Files.createDirectories(path.parent)
        val b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC).putInt(VERSION).putLong(state.epoch).putInt(if (state.disabled) 1 else 0).putLong(state.c0DenyMask)
        b.putInt(crc(b.array(), 0, SIZE - Int.SIZE_BYTES)).flip()
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
            it.write(b)
            it.force(true)
        }
    }

    private fun mostRestrictive(first: DenyState?, second: DenyState?): DenyState = when {
        first == null -> second!!
        second == null -> first
        else -> DenyState(maxOf(first.epoch, second.epoch), first.disabled || second.disabled, first.c0DenyMask or second.c0DenyMask)
    }

    private fun crc(bytes: ByteArray, offset: Int, length: Int): Int =
        Crc32c.value(bytes, offset, length)

    private companion object {
        const val MAGIC = 0x5442444d
        const val VERSION = 1
        const val SIZE = 32
    }
}

/** Crash injection seam around each ordered persistence boundary. */
fun interface DirectBootCrashInjector { fun after(boundary: DirectBootBoundary) }
enum class DirectBootBoundary { PENDING_SYNCED, CE_COMMITTED, PENDING_PROMOTED }

/** Two-phase policy mirror coordinator; CE persistence is supplied by the future global coordinator. */
class DirectBootPolicyCoordinator(
    private val mirror: DenyMirror,
    private val commitCe: (DenyState) -> Unit,
) {
    fun tighten(target: DenyState, injector: DirectBootCrashInjector? = null) {
        mirror.writePending(target)
        injector?.after(DirectBootBoundary.PENDING_SYNCED)
        commitCe(target)
        injector?.after(DirectBootBoundary.CE_COMMITTED)
        mirror.promotePending()
        injector?.after(DirectBootBoundary.PENDING_PROMOTED)
    }

    fun loosen(target: DenyState, injector: DirectBootCrashInjector? = null) {
        commitCe(target)
        injector?.after(DirectBootBoundary.CE_COMMITTED)
        mirror.writePending(target)
        mirror.promotePending()
        injector?.after(DirectBootBoundary.PENDING_PROMOTED)
    }
}

/** Exact result of a CE/global barrier before a DE mirror may become permissive. */
enum class DirectBootGlobalTransitionResult { SUCCESS, PARTIAL, FAILED }

/**
 * Production CE/DE wiring: a tightening writes the fail-closed DE pending mirror before entering
 * the handler-owned global barrier. A loosening never changes DE until that barrier succeeds.
 */
class HandlerCoordinatedDirectBootPolicyCoordinator(
    private val mirror: DenyMirror,
    private val global: GlobalPolicyCoordinator,
    private val handlerBarrier: () -> BarrierAck,
) {
    fun tighten(target: DenyState): DirectBootGlobalTransitionResult {
        mirror.writePending(target)
        return when (global.updateProfile(target.toPolicySnapshot(), handlerBarrier)) {
            is ProfileUpdateResult.Success -> {
                mirror.promotePending()
                DirectBootGlobalTransitionResult.SUCCESS
            }
            is ProfileUpdateResult.Partial -> DirectBootGlobalTransitionResult.PARTIAL
            is ProfileUpdateResult.Failed -> DirectBootGlobalTransitionResult.FAILED
        }
    }

    fun loosen(target: DenyState): DirectBootGlobalTransitionResult {
        return when (global.updateProfile(target.toPolicySnapshot(), handlerBarrier)) {
            is ProfileUpdateResult.Success -> {
                mirror.writePending(target)
                mirror.promotePending()
                DirectBootGlobalTransitionResult.SUCCESS
            }
            is ProfileUpdateResult.Partial -> DirectBootGlobalTransitionResult.PARTIAL
            is ProfileUpdateResult.Failed -> DirectBootGlobalTransitionResult.FAILED
        }
    }

    private fun DenyState.toPolicySnapshot(): PolicySnapshot =
        PolicySnapshot(epoch, c0DenyMask, disabled)
}
