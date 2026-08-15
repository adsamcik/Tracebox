package dev.tracebox.export

import dev.tracebox.api.generated.GeneratedSchemaFingerprint
import java.io.ByteArrayOutputStream

sealed interface CborValue {
    data class Unsigned(val value: Long) : CborValue { init { require(value >= 0) } }
    data class Text(val value: String) : CborValue
    data class Bytes(val value: ByteArray) : CborValue
    data class Array(val value: List<CborValue>) : CborValue
    data class Map(val value: kotlin.collections.Map<String, CborValue>) : CborValue
}

/** RFC 8949 §4.2.1 deterministic encoding: map keys sort by encoded length, then bytes. */
object CanonicalCbor {
    fun encode(value: CborValue): ByteArray = ByteArrayOutputStream().also { write(value, it) }.toByteArray()

    private fun write(value: CborValue, output: ByteArrayOutputStream) {
        when (value) {
            is CborValue.Unsigned -> writeHead(0, value.value, output)
            is CborValue.Text -> value.value.toByteArray(Charsets.UTF_8).also { writeHead(3, it.size.toLong(), output); output.write(it) }
            is CborValue.Bytes -> value.value.also { writeHead(2, it.size.toLong(), output); output.write(it) }
            is CborValue.Array -> {
                writeHead(4, value.value.size.toLong(), output)
                value.value.forEach { write(it, output) }
            }
            is CborValue.Map -> {
                val sorted = value.value.entries.map { encode(CborValue.Text(it.key)) to it }
                    .sortedWith { left, right ->
                        val size = left.first.size.compareTo(right.first.size)
                        if (size != 0) size else compareBytes(left.first, right.first)
                    }
                writeHead(5, sorted.size.toLong(), output)
                sorted.forEach { (key, entry) -> output.write(key); write(entry.value, output) }
            }
        }
    }

    private fun writeHead(major: Int, value: Long, output: ByteArrayOutputStream) {
        when {
            value < 24 -> output.write((major shl 5) or value.toInt())
            value <= 0xff -> { output.write((major shl 5) or 24); output.write(value.toInt()) }
            value <= 0xffff -> { output.write((major shl 5) or 25); output.write((value shr 8).toInt()); output.write(value.toInt()) }
            value <= 0xffff_ffffL -> {
                output.write((major shl 5) or 26)
                (3 downTo 0).forEach { output.write((value shr (it * 8)).toInt()) }
            }
            else -> {
                output.write((major shl 5) or 27)
                (7 downTo 0).forEach { output.write((value shr (it * 8)).toInt()) }
            }
        }
    }

    private fun compareBytes(left: ByteArray, right: ByteArray): Int {
        left.indices.forEach { index ->
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return 0
    }
}

sealed interface PackageManifest {
    fun bytes(): ByteArray
    fun entryHashes(): Map<String, ByteArray>
}

/** File-private concrete manifest prevents construction from forged CBOR outside ManifestEncoder. */
private class EncodedPackageManifest(bytes: ByteArray, entryHashes: Map<String, ByteArray>) : PackageManifest {
    private val encoded = bytes.copyOf()
    private val hashes = entryHashes.mapValues { (_, hash) -> hash.copyOf() }
    override fun bytes(): ByteArray = encoded.copyOf()
    override fun entryHashes(): Map<String, ByteArray> = hashes.mapValues { (_, hash) -> hash.copyOf() }
}

object ManifestEncoder {
    fun encode(snapshot: PreparedSnapshot): PackageManifest {
        val entries = snapshot.entries.sortedBy(MaterializedEntry::path)
        val hashes = entries.associate { it.path to it.sha256() }
        val cbor = CborValue.Map(
            mapOf(
                "v" to CborValue.Unsigned(PACKAGE_FORMAT_VERSION),
                "record" to CborValue.Unsigned(PACKAGE_RECORD_VERSION),
                "schema" to CborValue.Bytes(GeneratedSchemaFingerprint.bytes()),
                "epoch" to CborValue.Unsigned(snapshot.policyEpoch),
                "privacy" to CborValue.Text(snapshot.maximumPrivacyClass.name),
                "range" to CborValue.Array(
                    snapshot.sourceRangeMillis?.let {
                        listOf(CborValue.Unsigned(it.first), CborValue.Unsigned(it.last))
                    } ?: emptyList(),
                ),
                "entries" to CborValue.Array(entries.map { entry ->
                    CborValue.Map(
                        mapOf(
                            "hash" to CborValue.Bytes(checkNotNull(hashes[entry.path])),
                            "path" to CborValue.Text(entry.path),
                            "size" to CborValue.Unsigned(entry.size),
                            "class" to CborValue.Text(entry.privacyClass.name),
                            "process" to CborValue.Unsigned(entry.processLocalId.toLong()),
                            "segment" to CborValue.Unsigned(entry.segmentLocalId.toLong()),
                            "record" to CborValue.Unsigned(entry.recordLocalId.toLong()),
                            "transforms" to CborValue.Array(entry.transforms.sorted().map(CborValue::Text)),
                        ),
                    )
                }),
                "omissions" to CborValue.Array(snapshot.omissions.map {
                    CborValue.Map(
                        mapOf(
                            "process" to CborValue.Unsigned(it.processLocalId.toLong()),
                            "segment" to CborValue.Unsigned(it.segmentLocalId.toLong()),
                            "sequence" to CborValue.Unsigned(it.sequence),
                            "reason" to CborValue.Text(it.reason),
                        ),
                    )
                }),
            ),
        )
        return EncodedPackageManifest(CanonicalCbor.encode(cbor), hashes)
    }

    const val PACKAGE_FORMAT_VERSION = 1L
    const val PACKAGE_RECORD_VERSION = 1L
    fun schemaFingerprint(): ByteArray = GeneratedSchemaFingerprint.bytes()
}
