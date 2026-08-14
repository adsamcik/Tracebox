package dev.tracebox.export

import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedExportMetadata
import dev.tracebox.api.generated.GeneratedBreadcrumb
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.storage.GeneratedRecordCodec
import dev.tracebox.storage.UidAccounting
import dev.tracebox.storage.UidBucket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import java.util.PriorityQueue

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
    internal val encodedPayload: ByteArray = GeneratedRecordCodec.encode(generated)

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

/**
 * Keeps only the deterministic most-recent Standard records while a snapshot is planned.
 *
 * The heap owns at most [SnapshotPreparer.MAX_STANDARD_RECORD_ENTRIES] records. Since every
 * [OrdinarySourceRecord] is itself bounded, both planning memory and the eventual materialized
 * record bytes have a fixed upper bound before ZIP construction begins.
 */
internal class BoundedRecentStandardRecordSelection {
    private data class Candidate(
        val processIdentity: InternalIdentity,
        val segmentIdentity: InternalIdentity,
        val processRole: Int,
        val record: OrdinarySourceRecord,
    )

    private data class SegmentKey(
        val processIdentity: InternalIdentity,
        val segmentIdentity: InternalIdentity,
        val processRole: Int,
    )

    private val newest = PriorityQueue<Candidate>(SnapshotPreparer.MAX_STANDARD_RECORD_ENTRIES, RECENCY)

    fun offer(
        processIdentity: InternalIdentity,
        segmentIdentity: InternalIdentity,
        processRole: Int,
        record: OrdinarySourceRecord,
    ) {
        require(processRole >= 0)
        val candidate = Candidate(processIdentity, segmentIdentity, processRole, record)
        if (newest.size < SnapshotPreparer.MAX_STANDARD_RECORD_ENTRIES) {
            newest.add(candidate)
            return
        }
        val oldestSelected = checkNotNull(newest.peek())
        if (RECENCY.compare(candidate, oldestSelected) > 0) {
            newest.remove()
            newest.add(candidate)
        }
    }

    fun toSegments(): List<SegmentSource> {
        val canonical = newest.toList().sortedWith(CANONICAL)
        check(
            canonical.sumOf {
                MATERIALIZED_RECORD_HEADER_BYTES + it.record.encodedPayload.size.toLong()
            } <= SnapshotPreparer.MAX_STANDARD_SELECTED_MATERIALIZED_BYTES,
        )
        val grouped = LinkedHashMap<SegmentKey, MutableList<OrdinarySourceRecord>>()
        canonical.forEach { candidate ->
            val key = SegmentKey(
                candidate.processIdentity,
                candidate.segmentIdentity,
                candidate.processRole,
            )
            grouped.getOrPut(key, ::mutableListOf).add(candidate.record)
        }
        return grouped.map { (key, records) ->
            SegmentSource(
                processIdentity = key.processIdentity,
                segmentIdentity = key.segmentIdentity,
                processRole = key.processRole,
                records = records.toList(),
            )
        }
    }

    private companion object {
        const val MATERIALIZED_RECORD_HEADER_BYTES =
            Int.SIZE_BYTES * 6L + Long.SIZE_BYTES

        val RECENCY = Comparator<Candidate> { left, right ->
            compareValues(left.record.occurredAtMillis, right.record.occurredAtMillis)
                .takeUnless { it == 0 }
                ?: compareValues(left.record.sequence, right.record.sequence)
                    .takeUnless { it == 0 }
                ?: left.processIdentity.compareTo(right.processIdentity)
                    .takeUnless { it == 0 }
                ?: left.segmentIdentity.compareTo(right.segmentIdentity)
                    .takeUnless { it == 0 }
                ?: compareValues(left.processRole, right.processRole)
                    .takeUnless { it == 0 }
                ?: compareValues(left.record.eventId.stableId, right.record.eventId.stableId)
                    .takeUnless { it == 0 }
                ?: compareByteArrays(left.record.encodedPayload, right.record.encodedPayload)
                    .takeUnless { it == 0 }
                ?: compareNullableIdentities(
                    left.record.artifactIdentity,
                    right.record.artifactIdentity,
                ).takeUnless { it == 0 }
                ?: compareValues(left.record.privacyClass.ordinal, right.record.privacyClass.ordinal)
                    .takeUnless { it == 0 }
                ?: compareValues(left.record.valid, right.record.valid)
        }

        val CANONICAL = Comparator<Candidate> { left, right ->
            left.processIdentity.compareTo(right.processIdentity)
                .takeUnless { it == 0 }
                ?: left.segmentIdentity.compareTo(right.segmentIdentity)
                    .takeUnless { it == 0 }
                ?: compareValues(left.processRole, right.processRole)
                    .takeUnless { it == 0 }
                ?: compareValues(left.record.sequence, right.record.sequence)
                    .takeUnless { it == 0 }
                ?: compareValues(left.record.occurredAtMillis, right.record.occurredAtMillis)
                    .takeUnless { it == 0 }
                ?: compareValues(left.record.eventId.stableId, right.record.eventId.stableId)
                    .takeUnless { it == 0 }
                ?: compareByteArrays(left.record.encodedPayload, right.record.encodedPayload)
                    .takeUnless { it == 0 }
                ?: compareNullableIdentities(
                    left.record.artifactIdentity,
                    right.record.artifactIdentity,
                ).takeUnless { it == 0 }
                ?: compareValues(left.record.privacyClass.ordinal, right.record.privacyClass.ordinal)
                    .takeUnless { it == 0 }
                ?: compareValues(left.record.valid, right.record.valid)
        }

        fun compareNullableIdentities(
            left: InternalIdentity?,
            right: InternalIdentity?,
        ): Int = when {
            left === right -> 0
            left == null -> -1
            right == null -> 1
            else -> left.compareTo(right)
        }

        fun compareByteArrays(left: ByteArray, right: ByteArray): Int {
            val shared = minOf(left.size, right.size)
            repeat(shared) { index ->
                val comparison =
                    (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
                if (comparison != 0) return comparison
            }
            return left.size.compareTo(right.size)
        }
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
        val recent = BoundedRecentStandardRecordSelection()
        request.segments.forEach { segment ->
            val cutoff = request.sequenceCutoffs[segment.segmentIdentity]
                ?: throw SnapshotFailure.CorruptInput("missing frozen cutoff")
            segment.records.forEach { record ->
                if (record.sequence <= cutoff) {
                    recent.offer(
                        segment.processIdentity,
                        segment.segmentIdentity,
                        segment.processRole,
                        record,
                    )
                }
            }
        }
        val selected = recent.toSegments().map { segment -> segment to segment.records }

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
                val unsupported = transforms.firstOrNull {
                    it != "none" && it != "parameter_redaction"
                }
                if (unsupported != null) {
                    throw SnapshotFailure.SchemaTransform(record.eventId, unsupported)
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

    companion object {
        /** One of DeterministicZip's entries is always the manifest. */
        const val MAX_STANDARD_RECORD_ENTRIES = DeterministicZip.MAX_ENTRIES - 1

        /**
         * Exact bound for all materialized record bodies retained by Standard planning.
         * The manifest and ZIP metadata are separately covered by DeterministicZip's bounds.
         */
        const val MAX_STANDARD_SELECTED_MATERIALIZED_BYTES =
            MAX_STANDARD_RECORD_ENTRIES *
                (Int.SIZE_BYTES * 6L + Long.SIZE_BYTES +
                    OrdinarySourceRecord.MAX_ORDINARY_RECORD_BYTES)
    }
}
