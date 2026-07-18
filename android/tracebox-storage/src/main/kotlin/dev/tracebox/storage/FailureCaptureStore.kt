package dev.tracebox.storage

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64

/** Raw crash bytes may only contribute an ID-free structural summary and are never package eligible. */
enum class RawArtifactDisposition { STRUCTURAL_SUMMARY_ONLY }
data class RawArtifactJournal(
    val id: ByteArray,
    val originRole: Int,
    val acceptedEpoch: Long,
    val disposition: RawArtifactDisposition = RawArtifactDisposition.STRUCTURAL_SUMMARY_ONLY,
) { init { require(id.size == 32) } }

/** CE handler raw-artifact store with a separate, hard byte budget. */
class RawArtifactStore(private val root: Path, private val rawQuotaBytes: Long) {
    init { require(rawQuotaBytes >= 0) }

    fun preCapture(id: ByteArray, originRole: Int, acceptedEpoch: Long): Boolean {
        val journal = RawArtifactJournal(id.copyOf(), originRole, acceptedEpoch)
        val path = journalPath(id)
        if (Files.exists(path)) return false
        Files.createDirectories(root)
        forceWrite(path, "${encode(journal.id)}|${journal.originRole}|${journal.acceptedEpoch}".toByteArray())
        return true
    }

    fun commitRaw(id: ByteArray, bytes: ByteArray): Boolean {
        if (journal(id) == null || bytes.size.toLong() + usedRawBytes() > rawQuotaBytes) return false
        forceWrite(rawPath(id), bytes)
        return true
    }

    fun journal(id: ByteArray): RawArtifactJournal? {
        val path = journalPath(id)
        if (!Files.isRegularFile(path)) return null
        val parts = try { Files.readString(path).trim().split('|') } catch (_: java.io.IOException) { return null }
        if (parts.size != 3) return null
        return try {
            RawArtifactJournal(decode(parts[0]), parts[1].toInt(), parts[2].toLong())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Tracebox-generated raw bytes without a valid lifecycle journal are destroyed, never parsed. */
    fun deleteUnverifiableOrphans() {
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tbraw") }.forEach { raw ->
                val id = raw.fileName.toString().removeSuffix(".tbraw")
                if (journalByName(id) == null) Files.deleteIfExists(raw)
            }
        }
    }

    private fun journalByName(id: String): RawArtifactJournal? =
        try { journal(decode(id)) } catch (_: IllegalArgumentException) { null }
    private fun rawPath(id: ByteArray): Path = root.resolve("${encode(id)}.tbraw")
    private fun journalPath(id: ByteArray): Path = root.resolve("${encode(id)}.tbrawjournal")
    private fun usedRawBytes(): Long = Files.list(root).use { it.filter { path -> path.fileName.toString().endsWith(".tbraw") }.mapToLong(Files::size).sum() }
    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}

/** Durable states make spool replay recoverable after every source-retirement boundary. */
private enum class SpoolState { JOURNALED, APPENDED, ACKNOWLEDGED, RETIRED }

/**
 * Handler structural-summary spool. Its canonical content excludes IDs; `stage` writes the tuple
 * and deterministic ID before appending, and `replay` retains source until a durable acknowledgement.
 */
class StructuralSummarySpool(private val root: Path) {
    fun stage(rawId: ByteArray, extractorVersion: Int, schema: ByteArray, canonicalBody: ByteArray): String {
        require(rawId.size == 32 && schema.size == 32)
        val digest = sha256(canonicalBody)
        val id = summaryId(rawId, extractorVersion, schema, digest)
        val path = recordPath(id)
        if (!Files.exists(path)) {
            Files.createDirectories(root)
            forceWrite(path, listOf(SpoolState.JOURNALED.name, encode(canonicalBody)).joinToString("|").toByteArray())
        }
        return id
    }

    fun replay(import: (String, ByteArray) -> Unit) {
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tbsummary") }.forEach { path ->
                val id = path.fileName.toString().removeSuffix(".tbsummary")
                val fields = Files.readString(path).trim().split('|', limit = 2)
                if (fields.size != 2) return@forEach
                if (fields[0] == SpoolState.RETIRED.name) return@forEach
                val body = decode(fields[1])
                import(id, body)
                forceWrite(path, "${SpoolState.ACKNOWLEDGED.name}|${fields[1]}".toByteArray())
                forceWrite(path, "${SpoolState.RETIRED.name}|${fields[1]}".toByteArray())
            }
        }
    }

    fun isRetired(id: String): Boolean = Files.readString(recordPath(id)).startsWith(SpoolState.RETIRED.name)
    private fun recordPath(id: String): Path = root.resolve("$id.tbsummary")
    private fun summaryId(raw: ByteArray, version: Int, schema: ByteArray, digest: ByteArray): String =
        encode(sha256("tracebox-summary-v1".toByteArray() + raw + version.toLittleEndian() + schema + digest))
    private fun Int.toLittleEndian(): ByteArray = byteArrayOf(toByte(), (this shr 8).toByte(), (this shr 16).toByte(), (this shr 24).toByte())
    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}

private fun forceWrite(path: Path, bytes: ByteArray) {
    FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
        it.write(java.nio.ByteBuffer.wrap(bytes))
        it.force(true)
    }
}
