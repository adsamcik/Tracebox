package dev.tracebox.core

import dev.tracebox.api.Crc32c
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Durable boundary for the native/CE/Direct-Boot policy transaction.
 *
 * Two fixed slots retain the last valid generation when a process dies during the next write.
 * [PolicyTransitionPhase.LOCAL_DURABLE] is the point of no return: recovery must roll forward at
 * the stored epoch. Earlier phases restore [PolicyTransition.previous] before restarting native
 * capture. COMPLETE is a logical clear and remains on disk as bounded recovery evidence.
 */
class PolicyTransitionJournal private constructor(
    private val basePath: Path,
    private val beforeSlotWrite: ((Path, Long) -> Unit)?,
    @Suppress("UNUSED_PARAMETER") private val testSeam: Unit,
) {
    constructor(basePath: Path) : this(basePath, null, Unit)

    internal constructor(
        basePath: Path,
        beforeSlotWrite: (Path, Long) -> Unit,
    ) : this(basePath, beforeSlotWrite, Unit)

    val slotPaths: List<Path> = listOf(
        basePath.resolveSibling("${basePath.fileName}-a"),
        basePath.resolveSibling("${basePath.fileName}-b"),
    )

    @Synchronized
    fun load(): PolicyTransitionLoad {
        return when (val loaded = latest()) {
            is Latest.Valid -> if (
                loaded.slot.transition.phase == PolicyTransitionPhase.COMPLETE
            ) {
                PolicyTransitionLoad.Empty
            } else {
                PolicyTransitionLoad.Active(loaded.slot.transition)
            }
            Latest.Corrupt -> PolicyTransitionLoad.Corrupt
            Latest.Empty -> PolicyTransitionLoad.Empty
        }
    }

    /**
     * Highest target epoch retained by any independently valid slot.
     *
     * Both valid slots contribute even when an equal-generation disagreement makes [load]
     * corrupt. Repair can therefore move strictly beyond every tuple it is about to supersede.
     */
    @Synchronized
    fun highWaterEpoch(): Long? =
        validSlots().maxOfOrNull { it.transition.target.epoch }

    /** Last durably resolved target, retained for monotonic fail-closed CE repair. */
    @Synchronized
    fun lastCompletedTarget(): PolicySnapshot? =
        (latest() as? Latest.Valid)
            ?.slot
            ?.transition
            ?.takeIf { it.phase == PolicyTransitionPhase.COMPLETE }
            ?.target

    /**
     * Persists the complete rollback/roll-forward tuple before PREPARE can reach the handler.
     */
    @Synchronized
    fun begin(previous: PolicySnapshot, target: PolicySnapshot): PolicyTransition {
        require(previous.epoch >= 0)
        require(target.epoch > previous.epoch)
        when (val loaded = latest()) {
            is Latest.Corrupt -> throw PolicyTransitionException.Corrupt
            is Latest.Valid -> {
                val current = loaded.slot.transition
                if (current.phase != PolicyTransitionPhase.COMPLETE) {
                    if (current.previous == previous && current.target == target) return current
                    throw PolicyTransitionException.Conflicting
                }
            }
            Latest.Empty -> Unit
        }
        return persist(
            PolicyTransition(
                previous = previous,
                target = target,
                phase = PolicyTransitionPhase.INTENT,
            ),
        )
    }

    @Synchronized
    fun markPrepared(targetEpoch: Long): PolicyTransition =
        advance(targetEpoch, PolicyTransitionPhase.PREPARED)

    @Synchronized
    fun markLocalDurable(targetEpoch: Long): PolicyTransition =
        advance(targetEpoch, PolicyTransitionPhase.LOCAL_DURABLE)

    /** Logical clear written only after native and local policy converge at [targetEpoch]. */
    @Synchronized
    fun complete(targetEpoch: Long) {
        advance(targetEpoch, PolicyTransitionPhase.COMPLETE)
    }

    /**
     * Resolves an interrupted transition after recovery has converged on the selected tuple.
     *
     * Recovery deliberately bypasses the normal phase sequence: a transition before
     * [PolicyTransitionPhase.LOCAL_DURABLE] is rolled back to [PolicyTransition.previous],
     * while one at or after that boundary is rolled forward to [PolicyTransition.target].
     * The caller must stop the old native participants, durably restore the selected local
     * tuple, and restart native capture at its stored epoch before calling this method.
     */
    @Synchronized
    fun resolveAfterRecovery(targetEpoch: Long) {
        val current = when (val loaded = latest()) {
            is Latest.Valid -> loaded.slot.transition
            Latest.Empty -> throw PolicyTransitionException.Missing
            Latest.Corrupt -> throw PolicyTransitionException.Corrupt
        }
        if (current.target.epoch != targetEpoch) throw PolicyTransitionException.Conflicting
        if (current.phase == PolicyTransitionPhase.COMPLETE) return
        persist(current.copy(phase = PolicyTransitionPhase.COMPLETE))
    }

    /** Rebuilds corrupt/empty recovery state using the crash-safe supersession protocol. */
    @Synchronized
    fun reinitializeCompleted(previous: PolicySnapshot, target: PolicySnapshot) {
        supersedeCompleted(previous, target)
    }

    /**
     * Supersedes any active, completed, empty, or corrupt journal after the caller has durably
     * committed a fresh fail-closed [target].
     *
     * The first completed slot uses a generation above every independently valid old slot and is
     * written over a non-latest slot where possible. It therefore dominates an untouched old slot
     * if the second write fails, while a first-write failure retains the prior latest slot. The
     * second forced slot restores redundancy at the next generation.
     */
    @Synchronized
    fun supersedeCompleted(previous: PolicySnapshot, target: PolicySnapshot) {
        require(previous.epoch >= 0)
        require(target.epoch > previous.epoch)
        val transition = PolicyTransition(
            previous = previous,
            target = target,
            phase = PolicyTransitionPhase.COMPLETE,
        )
        val valid = validSlots()
        val maximumGeneration = valid.maxOfOrNull(StoredSlot::generation) ?: 0L
        val firstGeneration = nextGeneration(maximumGeneration)
        val secondGeneration = nextGeneration(firstGeneration)
        val preserved = valid.maxWithOrNull(
            compareBy<StoredSlot> { it.generation }
                .thenBy { if (it.path == slotPaths[0]) 0 else 1 },
        )
        val firstPath = if (preserved?.path == slotPaths[0]) slotPaths[1] else slotPaths[0]
        val secondPath = if (firstPath == slotPaths[0]) slotPaths[1] else slotPaths[0]
        writeSlot(firstPath, firstGeneration, transition)
        writeSlot(secondPath, secondGeneration, transition)
    }

    private fun advance(targetEpoch: Long, phase: PolicyTransitionPhase): PolicyTransition {
        val current = when (val loaded = latest()) {
            is Latest.Valid -> loaded.slot.transition
            Latest.Empty -> throw PolicyTransitionException.Missing
            Latest.Corrupt -> throw PolicyTransitionException.Corrupt
        }
        if (current.target.epoch != targetEpoch) throw PolicyTransitionException.Conflicting
        if (current.phase == phase) return current
        if (current.phase == PolicyTransitionPhase.COMPLETE ||
            phase.ordinal != current.phase.ordinal + 1
        ) {
            throw PolicyTransitionException.InvalidPhase
        }
        return persist(current.copy(phase = phase))
    }

    private fun persist(transition: PolicyTransition): PolicyTransition {
        val loaded = latest()
        val latestSlot = when (loaded) {
            is Latest.Valid -> loaded.slot
            Latest.Empty -> null
            Latest.Corrupt -> throw PolicyTransitionException.Corrupt
        }
        val generation = nextGeneration(latestSlot?.generation ?: 0L)
        val target = if (latestSlot?.path == slotPaths[0]) slotPaths[1] else slotPaths[0]
        writeSlot(target, generation, transition)
        return transition
    }

    private fun writeSlot(target: Path, generation: Long, transition: PolicyTransition) {
        val bytes = encode(generation, transition)
        try {
            beforeSlotWrite?.invoke(target, generation)
            Files.createDirectories(target.parent)
            FileChannel.open(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                var buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            forceDirectory(target.parent)
        } catch (error: IOException) {
            throw PolicyTransitionException.Unavailable(error)
        }
    }

    private fun nextGeneration(current: Long): Long = try {
        Math.addExact(current, 1L)
    } catch (_: ArithmeticException) {
        throw PolicyTransitionException.GenerationExhausted
    }

    private fun validSlots(): List<StoredSlot> =
        slotPaths.map(::readSlot).mapNotNull { (it as? SlotLoad.Valid)?.slot }

    private fun latest(): Latest {
        val loads = slotPaths.map(::readSlot)
        val valid = loads.mapNotNull { (it as? SlotLoad.Valid)?.slot }
        if (valid.size == 2 && valid[0].generation == valid[1].generation) {
            return if (valid[0].transition == valid[1].transition) {
                Latest.Valid(valid[0])
            } else {
                Latest.Corrupt
            }
        }
        valid.maxByOrNull(StoredSlot::generation)?.let { return Latest.Valid(it) }
        return if (loads.any { it is SlotLoad.Corrupt }) Latest.Corrupt else Latest.Empty
    }

    private fun readSlot(path: Path): SlotLoad {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return SlotLoad.Missing
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return SlotLoad.Corrupt
        val bytes = try {
            if (Files.size(path) != SLOT_BYTES.toLong()) return SlotLoad.Corrupt
            Files.readAllBytes(path)
        } catch (_: IOException) {
            return SlotLoad.Corrupt
        }
        if (bytes.size != SLOT_BYTES ||
            Crc32c.value(bytes, 0, CRC_OFFSET) !=
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(CRC_OFFSET)
        ) {
            return SlotLoad.Corrupt
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != MAGIC || buffer.int != VERSION) return SlotLoad.Corrupt
        val generation = buffer.long
        val phase = PolicyTransitionPhase.entries.getOrNull(buffer.int) ?: return SlotLoad.Corrupt
        if (buffer.int != 0) return SlotLoad.Corrupt
        val previous = decodePolicy(buffer) ?: return SlotLoad.Corrupt
        if (buffer.int != 0) return SlotLoad.Corrupt
        val target = decodePolicy(buffer) ?: return SlotLoad.Corrupt
        if (generation <= 0 || previous.epoch < 0 || target.epoch <= previous.epoch) {
            return SlotLoad.Corrupt
        }
        return SlotLoad.Valid(
            StoredSlot(
                path,
                generation,
                PolicyTransition(previous, target, phase),
            ),
        )
    }

    private fun encode(generation: Long, transition: PolicyTransition): ByteArray {
        val bytes = ByteBuffer.allocate(SLOT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC)
            .putInt(VERSION)
            .putLong(generation)
            .putInt(transition.phase.ordinal)
            .putInt(0)
            .also { encodePolicy(it, transition.previous) }
            .putInt(0)
            .also { encodePolicy(it, transition.target) }
            .array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(CRC_OFFSET, Crc32c.value(bytes, 0, CRC_OFFSET))
        return bytes
    }

    private fun encodePolicy(buffer: ByteBuffer, value: PolicySnapshot) {
        buffer.putLong(value.epoch)
        buffer.putLong(value.denyMask)
        buffer.putInt(if (value.disabled) 1 else 0)
    }

    private fun decodePolicy(buffer: ByteBuffer): PolicySnapshot? {
        val epoch = buffer.long
        val mask = buffer.long
        val disabled = when (buffer.int) {
            0 -> false
            1 -> true
            else -> return null
        }
        return PolicySnapshot(epoch, mask, disabled)
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // The slot itself is forced; directory handles are unavailable on some providers.
        } catch (_: UnsupportedOperationException) {
            // Some host and desugared file-system providers cannot open a directory channel.
        } catch (_: SecurityException) {
            // The forced slot still retains the previous valid generation on failure.
        }
    }

    private data class StoredSlot(
        val path: Path,
        val generation: Long,
        val transition: PolicyTransition,
    )

    private sealed interface SlotLoad {
        data object Missing : SlotLoad
        data object Corrupt : SlotLoad
        data class Valid(val slot: StoredSlot) : SlotLoad
    }

    private sealed interface Latest {
        data object Empty : Latest
        data object Corrupt : Latest
        data class Valid(val slot: StoredSlot) : Latest
    }

    companion object {
        const val SLOT_BYTES = 72
        private const val MAGIC = 0x54425054
        private const val VERSION = 1
        private const val CRC_OFFSET = SLOT_BYTES - Int.SIZE_BYTES
    }
}

enum class PolicyTransitionPhase {
    INTENT,
    PREPARED,
    LOCAL_DURABLE,
    COMPLETE,
}

data class PolicyTransition(
    val previous: PolicySnapshot,
    val target: PolicySnapshot,
    val phase: PolicyTransitionPhase,
)

sealed interface PolicyTransitionLoad {
    data object Empty : PolicyTransitionLoad
    data object Corrupt : PolicyTransitionLoad
    data class Active(val transition: PolicyTransition) : PolicyTransitionLoad
}

sealed class PolicyTransitionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause) {
    data object Missing : PolicyTransitionException("policy transition journal is missing")
    data object Corrupt : PolicyTransitionException("policy transition journal is corrupt")
    data object Conflicting : PolicyTransitionException("a different policy transition is active")
    data object InvalidPhase : PolicyTransitionException("policy transition phase is invalid")
    data object GenerationExhausted :
        PolicyTransitionException("policy transition journal generation is exhausted")
    class Unavailable(cause: Throwable) :
        PolicyTransitionException("policy transition journal is unavailable", cause)
}
