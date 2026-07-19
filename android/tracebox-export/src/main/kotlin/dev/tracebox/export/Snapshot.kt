package dev.tracebox.export

import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedExportMetadata
import dev.tracebox.api.generated.GeneratedBreadcrumb
import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.api.generated.GeneratedHandledError
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.api.generated.GeneratedStructuralSummary
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

/**
 * A Standard record can only be constructed from a [GeneratedRecord], never a bare [ByteArray].
 * This signature structurally prevents raw Crashpad bytes from being relabeled as ordinary input.
 */
class OrdinarySourceRecord(
    val sequence: Long,
    val generated: GeneratedRecord,
    val occurredAtMillis: Long,
    val privacyClass: PackagePrivacyClass,
    val artifactIdentity: InternalIdentity? = null,
    val valid: Boolean = true,
) : PackageSourceInput {
    internal val encodedPayload: ByteArray = GeneratedRecordPayloadEncoder.encode(generated)

    init {
        require(sequence >= 0 && occurredAtMillis >= 0 && encodedPayload.size <= MAX_ORDINARY_RECORD_BYTES)
    }

    val eventId: GeneratedEventId get() = generated.eventId

    companion object {
        const val MAX_ORDINARY_RECORD_BYTES = 16 * 1024
    }
}

/** Reserved for a future Enhanced-mode pipeline; StandardSnapshotRequest has no reference to it. */
class RawArtifactSource private constructor(
    private val rawBytes: ByteArray,
    val artifactIdentity: InternalIdentity,
) : PackageSourceInput {
    companion object {
        fun captured(bytes: ByteArray, artifactIdentity: InternalIdentity): RawArtifactSource =
            RawArtifactSource(bytes.copyOf(), artifactIdentity)
    }
}

/** The Phase 3 generated-record primitive encoding, limited to bounded schema fields. */
private object GeneratedRecordPayloadEncoder {
    fun encode(value: GeneratedRecord): ByteArray = when (value) {
        is GeneratedStructuralSummary -> ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(value.stream_count.toInt()).putInt(value.thread_count.toInt())
            putInt(value.module_count.toInt()).putInt(value.exception_code.toInt())
            putShort(value.processor_architecture.toShort())
        }.array()
        is GeneratedEmergencyRecord -> ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(value.slot_sequence.toLong()).putLong(value.policy_epoch.toLong())
            putInt(value.signal_number).putInt(value.signal_code)
            putInt(value.process_role.toInt()).putInt(value.thread_role.toInt()).putLong(value.flags.toLong())
        }.array()
        is GeneratedBreadcrumb -> ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(value.code.toInt()).putLong(value.monotonic_time_ns.toLong())
        }.array()
        is GeneratedHandledError -> ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(value.kind.toInt()).putShort(value.frame_count.toShort())
        }.array()
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
                    record.encodedPayload,
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
        private var released = false

        internal fun reserveLease(destination: java.nio.file.Path): PackageQuotaReservation? {
            if (!accounting.reserve(destination, UidBucket.SNAPSHOTS, bytes)) return null
            return PackageQuotaReservation(accounting, destination, bytes)
        }

        internal fun release() {
            if (!released) {
                accounting.release(path)
                released = true
            }
        }
    }
}
