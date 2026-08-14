package dev.tracebox

import dev.tracebox.api.generated.GeneratedEmergencyRecord
import dev.tracebox.directboot.DirectBootDrainStatus
import dev.tracebox.directboot.DirectBootDrainToken
import dev.tracebox.directboot.DirectBootDurableAck
import dev.tracebox.directboot.DirectBootLayout
import dev.tracebox.directboot.DirectBootManager
import dev.tracebox.directboot.DirectBootRetireResult
import dev.tracebox.storage.PersistedSegmentIdentity

internal enum class DirectBootCeDurability {
    ALREADY_DURABLE,
    APPENDED_DURABLE,
    RETRY_REQUIRED,
}

internal class DirectBootImportCandidate(
    sourceId: ByteArray,
    schemaFingerprint: ByteArray,
    val record: GeneratedEmergencyRecord,
) {
    private val sourceIdBytes = sourceId.copyOf()
    private val schemaBytes = schemaFingerprint.copyOf()

    init {
        require(sourceIdBytes.size == PersistedSegmentIdentity.ID_SIZE)
    }

    val sourceId: ByteArray
        get() = sourceIdBytes.copyOf()

    val schemaFingerprint: ByteArray
        get() = schemaBytes.copyOf()

    val sourceKey: String
        get() = sourceIdBytes.toHex()
}

internal data class DirectBootImportBatch(
    val status: DirectBootDrainStatus,
    val candidates: List<DirectBootImportCandidate>,
)

internal interface DirectBootImportSource {
    fun drain(maximumRecords: Int): DirectBootImportBatch

    fun retire(
        candidate: DirectBootImportCandidate,
        durableSourceKeys: Set<String>,
    ): DirectBootRetireResult
}

internal fun interface DirectBootCeImportSink {
    fun ensureDurable(candidate: DirectBootImportCandidate): DirectBootCeDurability
}

internal data class DirectBootStartupImportReport(
    val drainStatus: DirectBootDrainStatus,
    val examinedRecords: Int,
    val durableRecords: Int,
    val retiredRecords: Int,
    val complete: Boolean,
)

/**
 * Bounded unlocked importer for the fixed Direct Boot C0 store.
 *
 * CE durability is established oldest-first. DE retirement then runs newest-first and is allowed
 * only for source IDs the sink has proven durable. A quota/policy/IO retry therefore leaves a
 * valid DE prefix, and a restart can recognize an already-durable CE record without appending it
 * twice before completing retirement.
 */
internal class DirectBootStartupImporter(
    expectedSchemaFingerprint: ByteArray,
) {
    private val expectedSchema = expectedSchemaFingerprint.copyOf()

    init {
        require(expectedSchema.size == PersistedSegmentIdentity.ID_SIZE)
    }

    fun import(
        source: DirectBootImportSource,
        sink: DirectBootCeImportSink,
    ): DirectBootStartupImportReport {
        val batch = source.drain(DirectBootLayout.RECORD_CAPACITY)
        if (batch.status == DirectBootDrainStatus.POLICY_DENIED) {
            return report(batch, durable = 0, retired = 0, complete = true)
        }
        if (batch.status != DirectBootDrainStatus.READY) {
            return report(batch, durable = 0, retired = 0, complete = false)
        }
        if (batch.candidates.size > DirectBootLayout.RECORD_CAPACITY ||
            batch.candidates.any {
                !it.schemaFingerprint.contentEquals(expectedSchema)
            } ||
            batch.candidates.map(DirectBootImportCandidate::sourceKey).toSet().size !=
            batch.candidates.size
        ) {
            return report(batch, durable = 0, retired = 0, complete = false)
        }

        val durableSourceKeys = LinkedHashSet<String>(batch.candidates.size)
        batch.candidates.forEach { candidate ->
            when (sink.ensureDurable(candidate)) {
                DirectBootCeDurability.ALREADY_DURABLE,
                DirectBootCeDurability.APPENDED_DURABLE,
                -> durableSourceKeys += candidate.sourceKey

                DirectBootCeDurability.RETRY_REQUIRED -> Unit
            }
        }

        var complete = durableSourceKeys.size == batch.candidates.size
        var retired = 0
        for (candidate in batch.candidates.asReversed()) {
            if (candidate.sourceKey !in durableSourceKeys) {
                complete = false
                break
            }
            when (source.retire(candidate, durableSourceKeys)) {
                DirectBootRetireResult.RETIRED,
                DirectBootRetireResult.ALREADY_RETIRED,
                -> retired += 1

                DirectBootRetireResult.NOT_DURABLY_ACKNOWLEDGED,
                DirectBootRetireResult.NOT_TAIL,
                DirectBootRetireResult.STALE_TOKEN,
                DirectBootRetireResult.NOT_ACTIVATED,
                DirectBootRetireResult.INVALID_ACTIVATION,
                DirectBootRetireResult.STORAGE_INELIGIBLE,
                DirectBootRetireResult.INVALID_STORAGE,
                -> {
                    complete = false
                    break
                }
            }
        }
        return report(
            batch,
            durable = durableSourceKeys.size,
            retired = retired,
            complete = complete,
        )
    }

    private fun report(
        batch: DirectBootImportBatch,
        durable: Int,
        retired: Int,
        complete: Boolean,
    ): DirectBootStartupImportReport =
        DirectBootStartupImportReport(
            drainStatus = batch.status,
            examinedRecords = batch.candidates.size,
            durableRecords = durable,
            retiredRecords = retired,
            complete = complete,
        )
}

/** Production adapter that keeps opaque DE drain tokens inside the Direct Boot module boundary. */
internal class ManagerDirectBootImportSource(
    private val manager: DirectBootManager,
) : DirectBootImportSource {
    private var tokensBySourceKey = emptyMap<String, DirectBootDrainToken>()

    override fun drain(maximumRecords: Int): DirectBootImportBatch {
        val drained = manager.drain(maximumRecords)
        tokensBySourceKey = drained.records.associate { record ->
            record.sourceId.hex to record.token
        }
        return DirectBootImportBatch(
            status = drained.status,
            candidates = drained.records.map { record ->
                DirectBootImportCandidate(
                    sourceId = record.sourceId.toByteArray(),
                    schemaFingerprint = record.schemaFingerprint,
                    record = record.toGeneratedEmergencyRecord(),
                )
            },
        )
    }

    override fun retire(
        candidate: DirectBootImportCandidate,
        durableSourceKeys: Set<String>,
    ): DirectBootRetireResult {
        val token = tokensBySourceKey[candidate.sourceKey]
            ?: return DirectBootRetireResult.STALE_TOKEN
        return manager.retireAcknowledged(
            token,
            DirectBootDurableAck { sourceId -> sourceId.hex in durableSourceKeys },
        )
    }
}

private fun ByteArray.toHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        this@toHex.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(alphabet[unsigned ushr 4])
            append(alphabet[unsigned and 0xf])
        }
    }
}
