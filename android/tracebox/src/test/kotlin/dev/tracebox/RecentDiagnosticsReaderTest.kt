package dev.tracebox

import dev.tracebox.api.DiagnosticHistoryQuery
import dev.tracebox.api.generated.GeneratedBreadcrumb
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedOsExit
import dev.tracebox.core.ControlPage
import dev.tracebox.core.PolicySnapshot
import dev.tracebox.core.PolicyTaggedRecord
import dev.tracebox.core.RecordPriority
import dev.tracebox.core.WriterPolicyGate
import dev.tracebox.storage.GeneratedRecordCodec
import dev.tracebox.storage.PersistedSegmentIdentity
import dev.tracebox.storage.RoleQuotaLedger
import dev.tracebox.storage.RoleQuotaPolicy
import dev.tracebox.storage.SegmentHeader
import dev.tracebox.storage.SegmentWriter
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecentDiagnosticsReaderTest {
    @Test fun readsDurableUnsealedRecordsWithTimeTypeLimitAndPolicyFilters() {
        val root = Files.createTempDirectory("tracebox-history")
        val page = ControlPage(root.resolve("control"))
        page.commit(PolicySnapshot(9, 0))
        val gate = WriterPolicyGate(page)
        gate.reload()
        val path = root.resolve("events.tbseg")
        val writer = SegmentWriter.create(
            path,
            SegmentHeader(PersistedSegmentIdentity(ByteArray(32) { 1 }, ByteArray(32) { 2 }), ByteArray(32) { 3 }, 9, 0, 7),
            gate, RoleQuotaLedger(RoleQuotaPolicy(mapOf(7 to 1_000_000)), root),
        )
        try {
            val records = listOf(GeneratedBreadcrumb(1u, 1u), GeneratedOsExit(6, 0, 100, 0u, 0u), GeneratedBreadcrumb(2u, 2u))
            records.forEach { value ->
                writer.append(value.eventId.stableId, PolicyTaggedRecord(4L, 9, RecordPriority.BREADCRUMB, GeneratedRecordCodec.encode(value)))
            }
            Files.setLastModifiedTime(path, FileTime.fromMillis(10_000))
            val latest = readRecentDiagnostics(root, 9, DiagnosticHistoryQuery(limit = 1))
            assertEquals(2u, assertIs<GeneratedBreadcrumb>(latest.single().record).code)
            assertEquals(10_000L, latest.single().observedAtMillis)
            val exits = readRecentDiagnostics(root, 9, DiagnosticHistoryQuery(eventIds = setOf(GeneratedEventId.OSEXIT)))
            assertEquals(6, assertIs<GeneratedOsExit>(exits.single().record).reason)
            assertTrue(readRecentDiagnostics(root, 10, DiagnosticHistoryQuery()).isEmpty())
            assertTrue(readRecentDiagnostics(root, 9, DiagnosticHistoryQuery(sinceMillis = 10_001)).isEmpty())
        } finally {
            writer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun rejectsUnboundedQueries() {
        assertFailsWith<IllegalArgumentException> { DiagnosticHistoryQuery(limit = 0) }
        assertFailsWith<IllegalArgumentException> { DiagnosticHistoryQuery(limit = 2_001) }
        assertFailsWith<IllegalArgumentException> { DiagnosticHistoryQuery(sinceMillis = -1) }
    }
}
