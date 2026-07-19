package dev.tracebox.export

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

sealed class PackageConstructionFailure(message: String) : IllegalArgumentException(message) {
    data class InvalidPath(val path: String) : PackageConstructionFailure("invalid archive path: $path")
    data class DuplicatePath(val path: String) : PackageConstructionFailure("duplicate archive path: $path")
    data class NestedArchive(val path: String) : PackageConstructionFailure("nested archive: $path")
    data class EntryLimit(val count: Int) : PackageConstructionFailure("too many entries: $count")
    data class PlaintextLimit(val bytes: Long) : PackageConstructionFailure("plaintext exceeds limit: $bytes")
    data class Zip64(val value: Long) : PackageConstructionFailure("ZIP64 would be required: $value")
    data class SizeMismatch(val path: String) : PackageConstructionFailure("declared size differs: $path")
}

data class ZipEntryInput(val path: String, val bytes: ByteArray, val declaredSize: Long = bytes.size.toLong()) {
    init { require(declaredSize >= 0) }
}

sealed interface MaterializedPackage {
    val manifest: PackageManifest
    fun exactBytes(): ByteArray
    fun plaintextSha256(): ByteArray
    fun transferStagingQuota(destination: java.nio.file.Path): Boolean
    fun releaseStagingQuota()
    fun withQuotaReservation(reservation: SnapshotPreparer.PackageQuotaReservation): MaterializedPackage
}

/** File-private concrete package prevents arbitrary JVM callers from fabricating finalized bytes. */
private class FinalizedPackage(
    bytes: ByteArray,
    digest: ByteArray,
    override val manifest: PackageManifest,
    private val quotaReservation: SnapshotPreparer.PackageQuotaReservation?,
) : MaterializedPackage {
    private val materializedBytes = bytes.copyOf()
    private val digest = digest.copyOf()
    override fun exactBytes(): ByteArray = materializedBytes.copyOf()
    override fun plaintextSha256(): ByteArray = digest.copyOf()
    override fun transferStagingQuota(destination: java.nio.file.Path): Boolean =
        quotaReservation?.transferTo(destination) ?: false
    override fun releaseStagingQuota() { quotaReservation?.release() }

    override fun withQuotaReservation(reservation: SnapshotPreparer.PackageQuotaReservation): MaterializedPackage =
        FinalizedPackage(materializedBytes, digest, manifest, reservation)
}

sealed interface PackagePipelineResult {
    data class Ready(val snapshot: PreparedSnapshot, val packageBytes: MaterializedPackage) : PackagePipelineResult
    data class Failed(val failure: PackagePipelineFailure) : PackagePipelineResult
}

sealed interface PackagePipelineFailure {
    data class Snapshot(val cause: SnapshotFailure) : PackagePipelineFailure
    data class Materialization(val cause: PackageConstructionFailure) : PackagePipelineFailure
}

/** The only production path from a Standard request to exact, finalized package bytes. */
class StandardPackagePipeline(
    private val preparer: SnapshotPreparer,
    private val zip: DeterministicZip = DeterministicZip(),
) {
    fun finalize(request: StandardSnapshotRequest): PackagePipelineResult = try {
        val snapshot = preparer.prepare(request)
        val materialized = zip.materialize(snapshot)
        val reservation = preparer.reserveFinalizedPackage(materialized.exactBytes().size.toLong())
        PackagePipelineResult.Ready(snapshot, materialized.withQuotaReservation(reservation))
    } catch (failure: SnapshotFailure) {
        PackagePipelineResult.Failed(PackagePipelineFailure.Snapshot(failure))
    } catch (failure: PackageConstructionFailure) {
        PackagePipelineResult.Failed(PackagePipelineFailure.Materialization(failure))
    }
}

class DeterministicZip(private val plaintextLimit: Long = DEFAULT_PLAINTEXT_LIMIT) {
    init { require(plaintextLimit in 1..HARD_PLAINTEXT_LIMIT) }

    fun materialize(snapshot: PreparedSnapshot): MaterializedPackage {
        val manifest = ManifestEncoder.encode(snapshot)
        val entries = listOf(ZipEntryInput("manifest.cbor", manifest.bytes())) +
            snapshot.entries.map { ZipEntryInput(it.path, it.bytes()) }
        val bytes = write(entries)
        return FinalizedPackage(bytes, MessageDigest.getInstance("SHA-256").digest(bytes), manifest, null)
    }

    fun write(input: List<ZipEntryInput>): ByteArray {
        if (input.size > MAX_ENTRIES) throw PackageConstructionFailure.EntryLimit(input.size)
        val entries = input.sortedBy(ZipEntryInput::path)
        val names = mutableSetOf<String>()
        entries.forEach {
            validatePath(it.path)
            if (!names.add(it.path)) throw PackageConstructionFailure.DuplicatePath(it.path)
            if (it.declaredSize > UINT32_MAX) throw PackageConstructionFailure.Zip64(it.declaredSize)
            if (it.declaredSize > HARD_PLAINTEXT_LIMIT) throw PackageConstructionFailure.PlaintextLimit(it.declaredSize)
            if (it.declaredSize != it.bytes.size.toLong()) throw PackageConstructionFailure.SizeMismatch(it.path)
        }
        val total = entries.sumOf { it.declaredSize }
        if (total > plaintextLimit) throw PackageConstructionFailure.PlaintextLimit(total)
        val output = ByteArrayOutputStream()
        val central = mutableListOf<CentralDirectoryEntry>()
        entries.forEach { entry ->
            val offset = output.size().toLong()
            if (offset > UINT32_MAX) throw PackageConstructionFailure.Zip64(offset)
            val name = entry.path.toByteArray(Charsets.UTF_8)
            val crc = CRC32().also { it.update(entry.bytes) }.value
            writeInt(output, LOCAL_FILE_HEADER)
            writeShort(output, VERSION_NEEDED)
            writeShort(output, UTF8_FLAG)
            writeShort(output, STORED_METHOD)
            writeShort(output, DOS_TIME)
            writeShort(output, DOS_DATE)
            writeInt(output, crc)
            writeInt(output, entry.declaredSize)
            writeInt(output, entry.declaredSize)
            writeShort(output, name.size)
            writeShort(output, 0)
            output.write(name)
            output.write(entry.bytes)
            central += CentralDirectoryEntry(entry.path, crc, entry.declaredSize, offset)
        }
        val centralOffset = output.size().toLong()
        central.forEach { entry ->
            val name = entry.path.toByteArray(Charsets.UTF_8)
            writeInt(output, CENTRAL_DIRECTORY_HEADER)
            writeShort(output, VERSION_MADE_BY)
            writeShort(output, VERSION_NEEDED)
            writeShort(output, UTF8_FLAG)
            writeShort(output, STORED_METHOD)
            writeShort(output, DOS_TIME)
            writeShort(output, DOS_DATE)
            writeInt(output, entry.crc)
            writeInt(output, entry.size)
            writeInt(output, entry.size)
            writeShort(output, name.size)
            writeShort(output, 0)
            writeShort(output, 0)
            writeShort(output, 0)
            writeShort(output, 0)
            writeInt(output, 0)
            writeInt(output, entry.offset)
            output.write(name)
        }
        val centralSize = output.size().toLong() - centralOffset
        if (centralOffset > UINT32_MAX || centralSize > UINT32_MAX) throw PackageConstructionFailure.Zip64(maxOf(centralOffset, centralSize))
        writeInt(output, END_OF_CENTRAL_DIRECTORY)
        writeShort(output, 0)
        writeShort(output, 0)
        writeShort(output, central.size)
        writeShort(output, central.size)
        writeInt(output, centralSize)
        writeInt(output, centralOffset)
        writeShort(output, 0)
        return output.toByteArray()
    }

    private fun validatePath(path: String) {
        val encoded = path.toByteArray(Charsets.UTF_8)
        val lower = path.lowercase()
        if (path.isEmpty() || encoded.size > 255 || path.startsWith("/") || path.startsWith("\\") ||
            Regex("^[A-Za-z]:").containsMatchIn(path) || path.contains('\\') || path.contains('\u0000') ||
            path.split('/').any { it == ".." || it.isEmpty() || it == "." }
        ) throw PackageConstructionFailure.InvalidPath(path)
        if (listOf(".zip", ".tbdiag", ".jar", ".apk").any(lower::endsWith)) throw PackageConstructionFailure.NestedArchive(path)
    }

    private fun writeShort(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeInt(output: ByteArrayOutputStream, value: Long) {
        (0..3).forEach { output.write(((value ushr (it * 8)) and 0xff).toInt()) }
    }

    private data class CentralDirectoryEntry(val path: String, val crc: Long, val size: Long, val offset: Long)

    companion object {
        const val DEFAULT_PLAINTEXT_LIMIT = 64L * 1024 * 1024
        const val HARD_PLAINTEXT_LIMIT = 128L * 1024 * 1024
        const val MAX_ENTRIES = 128
        private const val UINT32_MAX = 0xffff_ffffL
        private const val LOCAL_FILE_HEADER = 0x04034b50L
        private const val CENTRAL_DIRECTORY_HEADER = 0x02014b50L
        private const val END_OF_CENTRAL_DIRECTORY = 0x06054b50L
        private const val VERSION_NEEDED = 20
        private const val VERSION_MADE_BY = 20
        private const val UTF8_FLAG = 1 shl 11
        private const val STORED_METHOD = 0
        private const val DOS_TIME = 0
        private const val DOS_DATE = 0x0021 // 1980-01-01
    }
}
