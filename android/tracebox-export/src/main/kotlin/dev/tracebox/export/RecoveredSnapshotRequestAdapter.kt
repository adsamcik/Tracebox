package dev.tracebox.export

import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedRecord
import dev.tracebox.storage.GeneratedRecordCodec
import dev.tracebox.storage.SegmentWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Converts authoritative recovered Phase 2/3 segment frames into Standard-only snapshot input. */
class RecoveredSnapshotRequestAdapter {
    fun build(
        policyEpoch: Long,
        sequenceCutoff: Long,
        segmentPaths: Collection<Path>,
    ): StandardSnapshotRequest {
        require(policyEpoch >= 0 && sequenceCutoff >= 0)
        val recent = BoundedRecentStandardRecordSelection()
        segmentPaths.sortedBy { it.toAbsolutePath().normalize().toString() }.forEach { path ->
            val recovered = SegmentWriter.recover(path, repair = false)
            require(recovered.header.policyGeneration == policyEpoch) {
                "segment policy epoch does not match frozen snapshot epoch"
            }
            val processRole = recovered.header.processRole
            require(processRole > 0) { "segment process role must be positive" }
            val processIdentity =
                InternalIdentity.fromBytes(recovered.header.identity.processInstanceId)
            val segmentIdentity = InternalIdentity.fromBytes(recovered.header.identity.segmentId)
            val durableSegmentTimeMillis = durableSegmentTimeMillis(path)
            recovered.frames.forEach { frame ->
                if (frame.sequence <= sequenceCutoff) {
                    val generated = try {
                        GeneratedRecordCodec.decode(frame.recordType, frame.payload)
                    } catch (failure: IllegalArgumentException) {
                        throw SnapshotFailure.CorruptInput(
                            "invalid generated record at ${path.fileName}:${frame.sequence}: " +
                                failure.message,
                        )
                    }
                    recent.offer(
                        processIdentity,
                        segmentIdentity,
                        processRole,
                        OrdinarySourceRecord(
                            sequence = frame.sequence,
                            generated = generated,
                            occurredAtMillis = durableSegmentTimeMillis,
                            privacyClass = privacyClass(generated),
                        ),
                    )
                }
            }
        }
        val segments = recent.toSegments()
        return StandardSnapshotRequest(
            policyEpoch = policyEpoch,
            sequenceCutoffs = segments.associate { it.segmentIdentity to sequenceCutoff },
            segments = segments,
        )
    }

    /**
     * Segment frames do not currently carry wall-clock time. The forced segment file timestamp is
     * a real durable observation time; rejecting an absent/epoch-zero value is more honest than
     * fabricating 1970-01-01 in package disclosures.
     */
    private fun durableSegmentTimeMillis(path: Path): Long {
        val timestamp = try {
            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
        } catch (failure: IOException) {
            throw SnapshotFailure.CorruptInput(
                "segment record time unavailable at ${path.fileName}: ${failure.message}",
            )
        } catch (failure: SecurityException) {
            throw SnapshotFailure.CorruptInput(
                "segment record time unavailable at ${path.fileName}: ${failure.message}",
            )
        }
        if (timestamp <= 0L) {
            throw SnapshotFailure.CorruptInput(
                "segment record time unavailable at ${path.fileName}",
            )
        }
        return timestamp
    }

    private fun privacyClass(record: GeneratedRecord): PackagePrivacyClass = when (record.eventId) {
        GeneratedEventId.STRUCTURALSUMMARY,
        GeneratedEventId.EMERGENCYRECORD,
        GeneratedEventId.ANRCANDIDATE,
        GeneratedEventId.OSEXIT
        -> PackagePrivacyClass.C0
        GeneratedEventId.BREADCRUMB,
        GeneratedEventId.HANDLEDERROR,
        GeneratedEventId.MANAGEDCRASH,
        GeneratedEventId.RUSTPANIC
        -> PackagePrivacyClass.C1
    }
}
