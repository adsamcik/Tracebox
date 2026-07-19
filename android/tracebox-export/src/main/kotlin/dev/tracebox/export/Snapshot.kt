package dev.tracebox.export

import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedExportMetadata
import dev.tracebox.storage.UidAccounting
import dev.tracebox.storage.UidBucket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/** A persisted identity may be used for planning, but is never serialized into a package. */
class InternalIdentity private constructor(private val value: ByteArray) : Comparable<InternalIdentity> {
    init {
        require(value.isNotEmpty() && value.size <= 64)
    }

    fun bytes(): ByteArray = value.copyOf()

    override fun compareTo(other: InternalIdentity): Int {
        value.indices.forEach { index ->
            val comparison = (value[index].toInt() and 0xff).compareTo(other.value[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return value.size.compareTo(other.value.size)
    }

    override fun equals(other: Any?): Boolean = other is InternalIdentity && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()

    companion object {
        fun fromBytes(value: ByteArray): InternalIdentity = InternalIdentity(value.copyOf())
    }
}

enum class PackagePrivacyClass { C0, C1, C2 }

/**
 * Standard snapshots only accept ordinary generated records. `RawArtifactSource` is deliberately
 * not a subtype of this type and `SegmentSource.records` is `List<OrdinarySourceRecord>`, so raw
 * Crashpad artifacts cannot be passed to `StandardSnapshotRequest` at compile time.
 */
sealed interface PackageSourceInput

data class OrdinarySourceRecord(
    val sequence: Long,
    val eventId: GeneratedEventId,
    val payload: ByteArray,
    val occurredAtMillis: Long,
    val privacyClass: PackagePrivacyClass,
    val artifactIdentity: InternalIdentity? = null,
    val valid: Boolean = true,
) : PackageSourceInput {
    init {
        require(sequence >= 0 && occurredAtMillis >= 0 && payload.size <= MAX_ORDINARY_RECORD_BYTES)
    }

    companion object {
        const val MAX_ORDINARY_RECORD_BYTES = 16 * 1024
    }
}

/** Reserved for a future Enhanced-mode pipeline; StandardSnapshotRequest has no reference to it. */
class RawArtifactSource private constructor(
    val bytes: ByteArray,
    val artifactIdentity: InternalIdentity,
) : PackageSourceInput {
    companion object {
        fun captured(bytes: ByteArray, artifactIdentity: InternalIdentity): RawArtifactSource =
            RawArtifactSource(bytes.copyOf(), artifactIdentity)
    }
}

data class SegmentSource(
    val processIdentity: InternalIdentity,
    val segmentIdentity: InternalIdentity,
    val processRole: Int,
    val records: List<OrdinarySourceRecord>,
)

/**
 * Standard requests cannot contain a raw-artifact source. This type-level boundary makes raw
 * Crashpad data ineligible before selection, rather than relying on a removable filter.
 */
data class StandardSnapshotRequest(
    val policyEpoch: Long,
    val sequenceCutoffs: Map<InternalIdentity, Long>,
    val segments: List<SegmentSource>,
) {
    init {
        require(policyEpoch >= 0 && sequenceCutoffs.values.all { it >= 0 })
    }
}

sealed class SnapshotFailure(message: String) : IllegalStateException(message) {
    data class CorruptInput(val detail: String) : SnapshotFailure(detail)
    data class SchemaTransform(val event: GeneratedEventId, val transform: String) :
        SnapshotFailure("unsupported schema transformation $transform for $event")
    data class InternalIdentityLeak(val location: String) : SnapshotFailure("internal identity in $location")
    data class StagingQuota(val required: Long) : SnapshotFailure("snapshot staging quota exhausted for $required bytes")
}

/** Future Enhanced selection must invoke this before any raw bytes become package candidates. */
object RawArtifactIdentityScanner {
    fun requireNoKnownIdentityEncoding(
        bytes: ByteArray,
        identities: Collection<InternalIdentity>,
        location: String = "raw artifact",
    ) {
        identities.forEach { identity ->
            val binary = identity.bytes()
            val hex = binary.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
            val base64 = Base64.getUrlEncoder().withoutPadding().encode(binary)
            val standardBase64 = Base64.getEncoder().encode(binary)
            if (contains(bytes, binary) || contains(bytes, hex) || contains(bytes, base64) || contains(bytes, standardBase64)) {
                throw SnapshotFailure.InternalIdentityLeak(location)
            }
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { offset ->
            needle.indices.all { index -> haystack[offset + index] == needle[index] }
        }
    }
}

data class SnapshotOmission(
    val processLocalId: Int,
    val segmentLocalId: Int,
    val sequence: Long,
    val reason: String,
)

class MaterializedEntry internal constructor(
    val path: String,
    private val body: ByteArray,
    val privacyClass: PackagePrivacyClass,
    val transforms: List<String>,
    val processLocalId: Int,
    val segmentLocalId: Int,
    val recordLocalId: Int,
    val occurredAtMillis: Long,
) {
    fun bytes(): ByteArray = body.copyOf()
    fun sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(body)
    val size: Long get() = body.size.toLong()
}

class PreparedSnapshot internal constructor(
    val policyEpoch: Long,
    val frozenCutoffs: Map<InternalIdentity, Long>,
    val entries: List<MaterializedEntry>,
    val omissions: List<SnapshotOmission>,
    val maximumPrivacyClass: PackagePrivacyClass,
    val sourceRangeMillis: LongRange?,
) {
    fun totalBytes(): Long = entries.sumOf(MaterializedEntry::size)
}

/**
 * Materializes canonical, package-local record bodies. It intentionally accepts only the standard
 * source type, which has no raw-artifact variant.
 */
class SnapshotPreparer(private val accounting: UidAccounting, private val stagingKey: java.nio.file.Path) {
    fun prepare(request: StandardSnapshotRequest): PreparedSnapshot {
        val selected = request.segments
            .sortedWith(compareBy<SegmentSource>({ it.processIdentity }, { it.segmentIdentity }))
            .map { segment ->
                val cutoff = request.sequenceCutoffs[segment.segmentIdentity]
                    ?: throw SnapshotFailure.CorruptInput("missing frozen cutoff")
                segment to segment.records.filter { it.sequence <= cutoff }.sortedBy(OrdinarySourceRecord::sequence)
            }

        val processIds = selected.map { it.first.processIdentity }.distinct().sorted()
            .withIndex().associate { (index, identity) -> identity to index + 1 }
        val segmentIds = selected.map { it.first.segmentIdentity }.distinct().sorted()
            .withIndex().associate { (index, identity) -> identity to index + 1 }
        val artifactIds = selected.flatMap { (_, records) -> records.mapNotNull(OrdinarySourceRecord::artifactIdentity) }.distinct().sorted()
            .withIndex().associate { (index, identity) -> identity to index + 1 }
        val allInternalIdentities = (selected.flatMap { listOf(it.first.processIdentity, it.first.segmentIdentity) } + artifactIds.keys).distinct()

        val entries = mutableListOf<MaterializedEntry>()
        val omissions = mutableListOf<SnapshotOmission>()
        var nextRecordId = 1
        selected.forEach { (segment, records) ->
            val processLocal = checkNotNull(processIds[segment.processIdentity])
            val segmentLocal = checkNotNull(segmentIds[segment.segmentIdentity])
            records.forEach { record ->
                if (!record.valid) {
                    omissions += SnapshotOmission(processLocal, segmentLocal, record.sequence, "corrupt_ordinary_record")
                    return@forEach
                }
                if (!GeneratedExportMetadata.standardVisible(record.eventId)) {
                    throw SnapshotFailure.CorruptInput("event ${record.eventId} is not Standard-package eligible")
                }
                val transforms = GeneratedExportMetadata.transforms(record.eventId)
                if (transforms.any { it != "none" }) {
                    throw SnapshotFailure.SchemaTransform(record.eventId, transforms.first { it != "none" })
                }
                val recordLocal = nextRecordId++
                val artifactLocal = record.artifactIdentity?.let { checkNotNull(artifactIds[it]) } ?: 0
                val transformed = encodeRecord(
                    record.eventId.stableId,
                    processLocal,
                    segmentLocal,
                    artifactLocal,
                    recordLocal,
                    record.occurredAtMillis,
                    record.payload,
                )
                assertNoInternalIdentities(
                    transformed,
                    allInternalIdentities,
                    "materialized record",
                )
                entries += MaterializedEntry(
                    path = "records/${recordLocal.toString().padStart(6, '0')}.tbr",
                    body = transformed,
                    privacyClass = record.privacyClass,
                    transforms = transforms,
                    processLocalId = processLocal,
                    segmentLocalId = segmentLocal,
                    recordLocalId = recordLocal,
                    occurredAtMillis = record.occurredAtMillis,
                )
            }
        }
        val range = entries.map(MaterializedEntry::occurredAtMillis).let {
            if (it.isEmpty()) null else it.min()..it.max()
        }
        return PreparedSnapshot(
            request.policyEpoch,
            request.sequenceCutoffs.toMap(),
            entries.toList(),
            omissions.sortedWith(compareBy(SnapshotOmission::processLocalId, SnapshotOmission::segmentLocalId, SnapshotOmission::sequence)),
            entries.maxOfOrNull(MaterializedEntry::privacyClass) ?: PackagePrivacyClass.C0,
            range,
        )
    }

    /**
     * Charges the exact final ZIP bytes, including the manifest and ZIP metadata—not entry bodies.
     * The reservation is transferred atomically to a staging lease when one is created.
     */
    internal fun reserveFinalizedPackage(exactBytes: Long): PackageQuotaReservation {
        if (!accounting.reserve(stagingKey, UidBucket.SNAPSHOTS, exactBytes)) {
            throw SnapshotFailure.StagingQuota(exactBytes)
        }
        return PackageQuotaReservation(accounting, stagingKey, exactBytes)
    }

    private fun encodeRecord(
        eventId: Int,
        processId: Int,
        segmentId: Int,
        artifactId: Int,
        recordId: Int,
        occurredAtMillis: Long,
        payload: ByteArray,
    ): ByteArray = ByteBuffer.allocate(4 * 6 + Long.SIZE_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
        putInt(1).putInt(eventId).putInt(processId).putInt(segmentId).putInt(artifactId).putInt(recordId)
        putLong(occurredAtMillis).put(payload)
    }.array()

    private fun assertNoInternalIdentities(bytes: ByteArray, identities: List<InternalIdentity>, location: String) {
        RawArtifactIdentityScanner.requireNoKnownIdentityEncoding(bytes, identities, location)
    }

    class PackageQuotaReservation internal constructor(
        private val accounting: UidAccounting,
        private var path: java.nio.file.Path,
        val bytes: Long,
    ) {
        internal fun transferTo(destination: java.nio.file.Path): Boolean {
            if (!accounting.transfer(path, destination, UidBucket.SNAPSHOTS, bytes)) return false
            path = destination
            return true
        }

        internal fun release() = accounting.release(path)
    }
}
