package dev.tracebox.export.ui

import dev.tracebox.export.MaterializedPackage
import dev.tracebox.export.DeterministicZip
import dev.tracebox.export.ManifestEncoder
import dev.tracebox.export.PackagePrivacyClass
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

enum class ProtectionMode { LOCAL_ONLY }

class RecipientSet private constructor(val labels: List<String>) {
    companion object {
        val LocalOnly = RecipientSet(emptyList())
    }
}

data class DisclosureEntry(
    val path: String,
    val size: Long,
    val privacyClass: PackagePrivacyClass,
    val transforms: List<String>,
    val processLocalId: Long,
    val sourceSegmentLocalId: Long,
    val recordLocalId: Long,
    val sha256: ByteArray,
)

data class DisclosureFacts(
    val includedCount: Int,
    val includedBytes: Long,
    val privacyClasses: Set<PackagePrivacyClass>,
    val transformations: Set<String>,
    val omissions: List<String>,
    val sourceRangeMillis: LongRange?,
    val policyEpoch: Long,
    val plaintextDigest: ByteArray,
    val entries: List<DisclosureEntry>,
    val rawC2Artifacts: List<DisclosureEntry>,
)

sealed interface DisclosureDecodeResult {
    data class Decoded(val facts: DisclosureFacts) : DisclosureDecodeResult
    data class Invalid(val reason: String) : DisclosureDecodeResult
}

/** Decodes the supplied finalized ZIP, rather than a snapshot or another package derivation. */
object DisclosureDecoder {
    fun decode(exactPackageBytes: ByteArray): DisclosureDecodeResult {
        if (exactPackageBytes.size.toLong() > MAX_ARCHIVE_BYTES) {
            return DisclosureDecodeResult.Invalid("archive too large")
        }
        return try {
            val files = linkedMapOf<String, ByteArray>()
            var totalBytes = 0L
            ZipInputStream(ByteArrayInputStream(exactPackageBytes)).use { stream ->
                while (true) {
                    val entry = stream.nextEntry ?: break
                    if (files.size >= DeterministicZip.MAX_ENTRIES ||
                        entry.isDirectory ||
                        entry.method != ZipEntry.STORED ||
                        entry.size < 0 ||
                        entry.compressedSize != entry.size ||
                        !isSafePath(entry.name) ||
                        entry.name in files
                    ) {
                        return DisclosureDecodeResult.Invalid("invalid archive entry")
                    }
                    val remainingBytes = DeterministicZip.HARD_PLAINTEXT_LIMIT - totalBytes
                    if (entry.size > remainingBytes ||
                        (entry.name == MANIFEST_PATH && entry.size > MAX_MANIFEST_BYTES)
                    ) {
                        return DisclosureDecodeResult.Invalid("archive content too large")
                    }
                    val contents = readExactEntry(stream, entry.size.toInt())
                        ?: return DisclosureDecodeResult.Invalid("archive entry size mismatch")
                    files[entry.name] = contents
                    totalBytes += entry.size
                }
            }
            val manifest = files.remove(MANIFEST_PATH) ?: return DisclosureDecodeResult.Invalid("manifest missing")
            val root = CborReader(manifest).read() as? Cbor.Map
                ?: return DisclosureDecodeResult.Invalid("manifest malformed")
            if (root.value.keys != MANIFEST_FIELDS) {
                return DisclosureDecodeResult.Invalid("manifest fields unsupported")
            }
            if ((root.value["v"] as? Cbor.UInt)?.value != ManifestEncoder.PACKAGE_FORMAT_VERSION) {
                return DisclosureDecodeResult.Invalid("package version unsupported")
            }
            if ((root.value["record"] as? Cbor.UInt)?.value != ManifestEncoder.PACKAGE_RECORD_VERSION) {
                return DisclosureDecodeResult.Invalid("record version unsupported")
            }
            val schemaFingerprint = (root.value["schema"] as? Cbor.Bytes)?.value
                ?: return DisclosureDecodeResult.Invalid("schema fingerprint missing")
            if (!MessageDigest.isEqual(schemaFingerprint, ManifestEncoder.schemaFingerprint())) {
                return DisclosureDecodeResult.Invalid("schema fingerprint unsupported")
            }
            val entries = (root.value["entries"] as? Cbor.Array)?.value?.map { decodeEntry(it, files) }
                ?: return DisclosureDecodeResult.Invalid("entries malformed")
            if (entries.any { it == null }) return DisclosureDecodeResult.Invalid("entry mismatch")
            val decodedEntries = entries.filterNotNull()
            if (files.keys != decodedEntries.map(DisclosureEntry::path).toSet()) {
                return DisclosureDecodeResult.Invalid("unlisted entry")
            }
            val rangeValues = (root.value["range"] as? Cbor.Array)?.value.orEmpty()
                .mapNotNull { (it as? Cbor.UInt)?.value }
            val range = if (rangeValues.size == 2) rangeValues[0]..rangeValues[1] else null
            val privacy = (root.value["privacy"] as? Cbor.Text)?.value?.let {
                runCatching { PackagePrivacyClass.valueOf(it) }.getOrNull()
            } ?: return DisclosureDecodeResult.Invalid("privacy malformed")
            val omissions = (root.value["omissions"] as? Cbor.Array)?.value.orEmpty().map { omission ->
                val value = omission as? Cbor.Map ?: return DisclosureDecodeResult.Invalid("omission malformed")
                (value.value["reason"] as? Cbor.Text)?.value
                    ?: return DisclosureDecodeResult.Invalid("omission reason malformed")
            }
            DisclosureDecodeResult.Decoded(
                DisclosureFacts(
                    includedCount = decodedEntries.size,
                    includedBytes = decodedEntries.sumOf(DisclosureEntry::size),
                    privacyClasses = decodedEntries.map(DisclosureEntry::privacyClass).toSet() + privacy,
                    transformations = decodedEntries.flatMap(DisclosureEntry::transforms).toSet(),
                    omissions = omissions,
                    sourceRangeMillis = range,
                    policyEpoch = (root.value["epoch"] as? Cbor.UInt)?.value
                        ?: return DisclosureDecodeResult.Invalid("epoch malformed"),
                    plaintextDigest = MessageDigest.getInstance("SHA-256").digest(exactPackageBytes),
                    entries = decodedEntries,
                    rawC2Artifacts = decodedEntries.filter { it.path.startsWith("raw-c2/") },
                ),
            )
        } catch (_: IllegalArgumentException) {
            DisclosureDecodeResult.Invalid("manifest malformed")
        } catch (_: IOException) {
            DisclosureDecodeResult.Invalid("invalid archive")
        }
    }

    private fun readExactEntry(stream: ZipInputStream, expectedSize: Int): ByteArray? {
        val contents = ByteArray(expectedSize)
        var offset = 0
        while (offset < contents.size) {
            val read = stream.read(contents, offset, contents.size - offset)
            if (read <= 0) return null
            offset += read
        }
        return if (stream.read() == -1) contents else null
    }

    private fun decodeEntry(value: Cbor, files: Map<String, ByteArray>): DisclosureEntry? {
        val fields = (value as? Cbor.Map)?.value ?: return null
        val path = (fields["path"] as? Cbor.Text)?.value ?: return null
        val contents = files[path] ?: return null
        val expectedHash = (fields["hash"] as? Cbor.Bytes)?.value ?: return null
        if (!MessageDigest.getInstance("SHA-256").digest(contents).contentEquals(expectedHash)) return null
        val expectedSize = (fields["size"] as? Cbor.UInt)?.value ?: return null
        if (expectedSize != contents.size.toLong()) return null
        return DisclosureEntry(
            path,
            expectedSize,
            (fields["class"] as? Cbor.Text)?.value?.let { runCatching { PackagePrivacyClass.valueOf(it) }.getOrNull() } ?: return null,
            ((fields["transforms"] as? Cbor.Array)?.value.orEmpty().mapNotNull { (it as? Cbor.Text)?.value }),
            (fields["process"] as? Cbor.UInt)?.value ?: return null,
            (fields["segment"] as? Cbor.UInt)?.value ?: return null,
            (fields["record"] as? Cbor.UInt)?.value ?: return null,
            expectedHash.copyOf(),
        )
    }

    private fun isSafePath(path: String) =
        path.isNotEmpty() && !path.startsWith("/") && !path.contains('\\') && path.split('/').none { it == "." || it == ".." || it.isEmpty() }

    private const val MANIFEST_PATH = "manifest.cbor"
    private val MANIFEST_FIELDS = setOf(
        "v",
        "record",
        "schema",
        "epoch",
        "privacy",
        "range",
        "entries",
        "omissions",
    )
    private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
    private const val MAX_ARCHIVE_OVERHEAD_BYTES = DeterministicZip.MAX_ENTRIES * 1024L
    private const val MAX_ARCHIVE_BYTES = DeterministicZip.HARD_PLAINTEXT_LIMIT + MAX_ARCHIVE_OVERHEAD_BYTES
}

object DisclosureRenderer {
    fun render(materialized: MaterializedPackage): DisclosureDecodeResult {
        val decoded = DisclosureDecoder.decode(materialized.exactBytes())
        return if (decoded is DisclosureDecodeResult.Decoded &&
            decoded.facts.plaintextDigest.contentEquals(materialized.plaintextSha256())
        ) decoded else DisclosureDecodeResult.Invalid("package digest mismatch")
    }

}

private sealed interface Cbor {
    data class UInt(val value: Long) : Cbor
    data class Text(val value: String) : Cbor
    data class Bytes(val value: ByteArray) : Cbor
    data class Array(val value: List<Cbor>) : Cbor
    data class Map(val value: kotlin.collections.Map<String, Cbor>) : Cbor
}

private class CborReader(private val bytes: ByteArray) {
    private var offset = 0

    fun read(): Cbor {
        val value = readValue(0)
        if (offset != bytes.size) throw IllegalArgumentException("trailing CBOR")
        return value
    }

    private fun readValue(depth: Int): Cbor {
        if (depth >= MAX_DEPTH) throw IllegalArgumentException("CBOR nesting too deep")
        val initial = take().toInt() and 0xff
        val major = initial ushr 5
        val count = length(initial and 0x1f)
        if (count < 0) throw IllegalArgumentException("CBOR value is too large")
        return when (major) {
            0 -> Cbor.UInt(count)
            2 -> Cbor.Bytes(takeMany(count))
            3 -> Cbor.Text(takeMany(count).toString(Charsets.UTF_8))
            4 -> {
                val itemCount = collectionSize(count)
                Cbor.Array(List(itemCount) { readValue(depth + 1) })
            }
            5 -> {
                val itemCount = collectionSize(count)
                val values = linkedMapOf<String, Cbor>()
                repeat(itemCount) {
                    val key = (readValue(depth + 1) as? Cbor.Text)?.value
                        ?: throw IllegalArgumentException("non-text key")
                    if (key in values) throw IllegalArgumentException("duplicate key")
                    values[key] = readValue(depth + 1)
                }
                Cbor.Map(values)
            }
            else -> throw IllegalArgumentException("unsupported CBOR")
        }
    }

    private fun length(additional: Int): Long = when {
        additional < 24 -> additional.toLong()
        additional == 24 -> take().toLong() and 0xff
        additional == 25 -> takeCount(2)
        additional == 26 -> takeCount(4)
        additional == 27 -> takeCount(8)
        else -> throw IllegalArgumentException("indefinite CBOR")
    }

    private fun collectionSize(count: Long): Int {
        if (count > MAX_COLLECTION_ITEMS) throw IllegalArgumentException("CBOR collection too large")
        return count.toInt()
    }

    private fun takeCount(count: Int): Long = (0 until count).fold(0L) { result, _ -> (result shl 8) or (take().toLong() and 0xff) }
    private fun takeMany(count: Long): ByteArray {
        if (count > Int.MAX_VALUE || count > bytes.size - offset) throw IllegalArgumentException("truncated CBOR")
        val countAsInt = count.toInt()
        return bytes.copyOfRange(offset, offset + countAsInt).also { offset += countAsInt }
    }
    private fun take(): Byte {
        if (offset == bytes.size) throw IllegalArgumentException("truncated CBOR")
        return bytes[offset++]
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_COLLECTION_ITEMS = 4_096L
    }
}
