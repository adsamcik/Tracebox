package dev.tracebox.storage

import dev.tracebox.api.Crc32c
import dev.tracebox.api.generated.GeneratedRustPanic
import dev.tracebox.core.GateResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

sealed interface RustPanicIngestionResult {
    data class Ingested(val sequence: Long) : RustPanicIngestionResult
    data class Dropped(val reason: GateResult) : RustPanicIngestionResult
    data object InvalidOrIncomplete : RustPanicIngestionResult
}

/**
 * Validates and imports the fixed Rust panic slot before native initialization can reuse it.
 *
 * The record intentionally contains no panic text or file path. Its location code combines the
 * native writer's bounded file hash with line/column numbers and remains a structural C1 value.
 */
class RustPanicStartupIngestor(
    private val slot: Path,
    private val records: GeneratedRecordSegmentAdapter,
) {
    fun ingest(): RustPanicIngestionResult {
        val bytes = readExactStorageFile(slot, RECORD_SIZE)
            ?: return RustPanicIngestionResult.InvalidOrIncomplete
        val decoded = decode(bytes) ?: return RustPanicIngestionResult.InvalidOrIncomplete
        records.record(
            GeneratedRustPanic(
                payload_class = decoded.payloadClass.toUInt(),
                thread_role = decoded.processRole.toUInt(),
                location_code = decoded.locationCode,
                flags = decoded.flags,
            ),
            null,
        )
        val result = records.latestResult()
        return when (result) {
            is GeneratedRecordAppendResult.Appended -> {
                forceReset()
                RustPanicIngestionResult.Ingested(result.sequence)
            }

            is GeneratedRecordAppendResult.Dropped ->
                RustPanicIngestionResult.Dropped(result.reason)

            is GeneratedRecordAppendResult.DroppedQuota ->
                RustPanicIngestionResult.Dropped(GateResult.Denied)

            GeneratedRecordAppendResult.Ignored ->
                RustPanicIngestionResult.InvalidOrIncomplete
        }
    }

    private fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size != RECORD_SIZE ||
            !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) ||
            readInt(bytes, VERSION_OFFSET) != VERSION ||
            readInt(bytes, SIZE_OFFSET) != RECORD_SIZE ||
            readLong(bytes, EPOCH_OFFSET) == 0L ||
            readLong(bytes, COMPLETION_OFFSET) != COMPLETION ||
            Crc32c.value(bytes, 0, CHECKSUM_OFFSET) != readInt(bytes, CHECKSUM_OFFSET)
        ) {
            return null
        }
        val role = readInt(bytes, ROLE_OFFSET)
        val payload = readInt(bytes, PAYLOAD_OFFSET)
        val hasLocation = readInt(bytes, HAS_LOCATION_OFFSET)
        val line = readInt(bytes, LINE_OFFSET)
        val column = readInt(bytes, COLUMN_OFFSET)
        val fileHash = readInt(bytes, FILE_HASH_OFFSET)
        if (role < 0 || payload !in 0..2 || hasLocation !in 0..1 ||
            (hasLocation == 0 && (line != 0 || column != 0 || fileHash != 0))
        ) {
            return null
        }
        val flags =
            (hasLocation or
                (if (line != 0) 1 shl 1 else 0) or
                (if (column != 0) 1 shl 2 else 0)).toUInt()
        val locationCode = if (hasLocation == 0) {
            0u
        } else {
            (fileHash xor line.rotateLeft(13) xor column.rotateLeft(27)).toUInt()
        }
        return Decoded(role, payload, locationCode, flags)
    }

    private fun forceReset() {
        FileChannel.open(
            slot,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(ByteArray(RECORD_SIZE))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readLong(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).long

    private data class Decoded(
        val processRole: Int,
        val payloadClass: Int,
        val locationCode: UInt,
        val flags: UInt,
    )

    private companion object {
        val MAGIC = "TBRUSTP1".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1
        const val RECORD_SIZE = 64
        const val VERSION_OFFSET = 8
        const val SIZE_OFFSET = 12
        const val EPOCH_OFFSET = 16
        const val ROLE_OFFSET = 24
        const val PAYLOAD_OFFSET = 28
        const val HAS_LOCATION_OFFSET = 32
        const val LINE_OFFSET = 36
        const val COLUMN_OFFSET = 40
        const val FILE_HASH_OFFSET = 44
        const val CHECKSUM_OFFSET = 52
        const val COMPLETION_OFFSET = 56
        const val COMPLETION = 0x5442_5255_5354_434fL
    }
}
