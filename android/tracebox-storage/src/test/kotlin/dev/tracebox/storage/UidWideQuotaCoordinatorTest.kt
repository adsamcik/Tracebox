package dev.tracebox.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UidWideQuotaCoordinatorTest {
    private fun root(): Path =
        Path.of("build", "phase2-tests", UUID.randomUUID().toString()).also(Files::createDirectories)

    private fun quota() = UidQuota(
        mapOf(
            UidBucket.ROLE_SEGMENTS to 64L,
            UidBucket.RAW_ARTIFACTS to 16L,
            UidBucket.SUMMARY_SPOOL to 64L,
            UidBucket.SUMMARY_STAGING to 32L,
            UidBucket.SNAPSHOTS to 64L,
            UidBucket.COMPACTION to 64L,
            UidBucket.EMERGENCY to 256L,
            UidBucket.METADATA to 4_096L,
        ),
    )

    private fun files() = UidBucket.entries.associateWith { 16 }

    @Test fun durable_uid_wide_ownership_counts_writer_raw_spool_and_metadata_before_use() {
        val root = root()
        val coordinator = UidWideQuotaCoordinator(root, quota(), files())
        val segment = root.resolve("writer.tbseg")
        val raw = root.resolve("handler.tbraw")
        val spool = root.resolve("handler.tbsummary")
        val journal = root.resolve("handler.tbrawjournal")

        assertTrue(coordinator.reserve(segment, UidBucket.ROLE_SEGMENTS, 64))
        assertTrue(coordinator.reserve(raw, UidBucket.RAW_ARTIFACTS, 16))
        assertTrue(coordinator.reserve(spool, UidBucket.SUMMARY_SPOOL, 64))
        assertTrue(coordinator.reserve(journal, UidBucket.METADATA, 32))
        assertFalse(coordinator.reserve(root.resolve("raw-overflow"), UidBucket.RAW_ARTIFACTS, 1))

        val restarted = UidWideQuotaCoordinator(root, quota(), files())
        assertEquals(64L, restarted.used(UidBucket.ROLE_SEGMENTS))
        assertEquals(16L, restarted.used(UidBucket.RAW_ARTIFACTS))
        assertEquals(64L, restarted.used(UidBucket.SUMMARY_SPOOL))
        assertTrue(restarted.release(raw))
        assertTrue(restarted.reserve(root.resolve("replacement.tbraw"), UidBucket.RAW_ARTIFACTS, 16))
    }
}
