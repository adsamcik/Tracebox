package dev.tracebox

import dev.tracebox.api.DiagnosticHistoryEntry
import dev.tracebox.api.DiagnosticHistoryQuery
import dev.tracebox.export.RecoveredSnapshotRequestAdapter
import dev.tracebox.storage.SegmentWriter
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Uses the same bounded, ordinary-record recovery as the reviewed package pipeline. */
internal fun readRecentDiagnostics(
    root: Path,
    policyEpoch: Long,
    query: DiagnosticHistoryQuery,
): List<DiagnosticHistoryEntry> {
    val paths = Files.walk(root).use { candidates ->
        candidates.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .filter { it.fileName.toString().endsWith(".tbseg") }
            .filter { Files.getLastModifiedTime(it, LinkOption.NOFOLLOW_LINKS).toMillis() >= query.sinceMillis }
            .filter { SegmentWriter.recover(it, repair = false).header.policyGeneration == policyEpoch }
            .toList()
    }
    return RecoveredSnapshotRequestAdapter().build(policyEpoch, Long.MAX_VALUE, paths, query.eventIds)
        .segments.flatMap { it.records }
        .filter { it.eventId in query.eventIds }
        .sortedWith(compareBy({ it.occurredAtMillis }, { it.sequence }))
        .takeLast(query.limit)
        .map { DiagnosticHistoryEntry(it.occurredAtMillis, it.generated) }
}
