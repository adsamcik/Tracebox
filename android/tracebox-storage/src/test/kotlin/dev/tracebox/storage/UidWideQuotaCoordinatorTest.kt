package dev.tracebox.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test fun forced_pending_ledger_is_recovered_conservatively_after_rename_crash_window() {
        val root = root()
        val coordinator = UidWideQuotaCoordinator(root, quota(), files())
        val first = root.resolve("first.tbseg")
        val second = root.resolve("second.tbseg")
        assertTrue(coordinator.reserve(first, UidBucket.ROLE_SEGMENTS, 20))
        val priorLedger = Files.readAllBytes(root.resolve("tracebox-uid-quota-v1"))
        assertTrue(coordinator.reserve(second, UidBucket.ROLE_SEGMENTS, 30))
        val pendingLedger = Files.readAllBytes(root.resolve("tracebox-uid-quota-v1"))

        Files.write(root.resolve("tracebox-uid-quota-v1"), priorLedger)
        Files.write(root.resolve("tracebox-uid-quota-v1.new"), pendingLedger)

        val restarted = UidWideQuotaCoordinator(root, quota(), files())
        assertEquals(50L, restarted.used(UidBucket.ROLE_SEGMENTS))
        assertFalse(Files.exists(root.resolve("tracebox-uid-quota-v1.new")))
        assertTrue(restarted.owns(first, UidBucket.ROLE_SEGMENTS, 20))
        assertTrue(restarted.owns(second, UidBucket.ROLE_SEGMENTS, 30))
    }

    @Test fun coordinator_instances_serialize_concurrent_access_to_one_process_ledger() {
        val root = root()
        val coordinators = listOf(
            UidWideQuotaCoordinator(root, quota(), files()),
            UidWideQuotaCoordinator(root, quota(), files()),
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val reservations = (0 until 16).map { index ->
                executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    coordinators[index % coordinators.size].reserve(
                        root.resolve("concurrent-$index.tbseg"),
                        UidBucket.ROLE_SEGMENTS,
                        1,
                    )
                }
            }
            start.countDown()
            assertTrue(reservations.all { it.get(5, TimeUnit.SECONDS) })
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(16L, coordinators.first().used(UidBucket.ROLE_SEGMENTS))
    }
}
