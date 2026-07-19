package dev.tracebox.export

import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.storage.GeneratedRecordCodec
import dev.tracebox.storage.SegmentWriter
import java.nio.file.Path

/** Converts authoritative recovered Phase 2/3 segment frames into Standard-only snapshot input. */
class RecoveredSnapshotRequestAdapter {
    fun build(
        policyEpoch: Long,
        processRole: Int,
        sequenceCutoff: Long,
        segmentPaths: Collection<Path>,
    ): StandardSnapshotRequest {
        require(policyEpoch >= 0 && processRole >= 0 && sequenceCutoff >= 0)
        val segments = segmentPaths.sortedBy { it.toAbsolutePath().normalize().toString() }.mapNotNull { path ->
            val recovered = SegmentWriter.recover(path, repair = false)
            if (recovered.header.processRole != processRole) return@mapNotNull null
            require(recovered.header.policyGeneration == policyEpoch) {
                "segment policy epoch does not match frozen snapshot epoch"
            }
            SegmentSource(
                processIdentity = InternalIdentity.fromBytes(recovered.header.identity.processInstanceId),
                segmentIdentity = InternalIdentity.fromBytes(recovered.header.identity.segmentId),
                processRole = processRole,
                records = recovered.frames.asSequence()
                    .filter { it.sequence <= sequenceCutoff }
                    .map { frame ->
                        val generated = try {
                            GeneratedRecordCodec.decode(frame.recordType, frame.payload)
                        } catch (failure: IllegalArgumentException) {
                            throw SnapshotFailure.CorruptInput("invalid generated record at ${path.fileName}:${frame.sequence}: ${failure.message}")
                        }
                        OrdinarySourceRecord(
                            sequence = frame.sequence,
                            generated = generated,
                            occurredAtMillis = 0,
                            privacyClass = privacyClass(generated),
                        )
                    }
                    .toList(),
            )
        }
        return StandardSnapshotRequest(
            policyEpoch = policyEpoch,
            sequenceCutoffs = segments.associate { it.segmentIdentity to sequenceCutoff },
            segments = segments,
        )
    }

    private fun privacyClass(record: GeneratedRecord): PackagePrivacyClass = when (record.eventId) {
        GeneratedEventId.STRUCTURALSUMMARY -> PackagePrivacyClass.C0
        GeneratedEventId.BREADCRUMB, GeneratedEventId.HANDLEDERROR -> PackagePrivacyClass.C1
        GeneratedEventId.EMERGENCYRECORD -> PackagePrivacyClass.C0
    }
}
