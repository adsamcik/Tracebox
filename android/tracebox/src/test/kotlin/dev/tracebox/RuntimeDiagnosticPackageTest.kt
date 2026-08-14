package dev.tracebox

import dev.tracebox.api.SaveFailure
import dev.tracebox.api.SavePackageResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeDiagnosticPackageTest {
    @Test
    fun disclosure_formats_package_size_without_hiding_exact_technical_bytes() {
        assertEquals("0 bytes", formatPackageSize(0L))
        assertEquals("1 KB", formatPackageSize(1_024L))
        assertEquals("2 KB", formatPackageSize(1_536L))
        assertEquals("1 MB", formatPackageSize(1_048_576L))
    }

    @Test
    fun staging_expiry_is_a_future_deadline_and_fresh_files_are_not_expired() {
        val now = 1_000_000L
        val deadline = packageStagingExpiryDeadlineMillis(now)

        assertTrue(deadline > now)
        assertFalse(packageStagingExpired(deadline, now))
        assertFalse(packageStagingExpired(deadline, deadline - 1L))
        assertTrue(packageStagingExpired(deadline, deadline))
        assertEquals(Long.MAX_VALUE, packageStagingExpiryDeadlineMillis(Long.MAX_VALUE))
    }

    @Test
    fun save_contains_open_io_and_security_failures_before_any_recipient_exists() {
        val io = savePackageBytes(
            byteArrayOf(1),
            openOutput = { throw IOException("open") },
            isCancelled = { false },
        )
        val security = savePackageBytes(
            byteArrayOf(1),
            openOutput = { throw SecurityException("open") },
            isCancelled = { false },
        )
        val unavailable = savePackageBytes(
            byteArrayOf(1),
            openOutput = { null },
            isCancelled = { false },
        )
        val hostileProvider = savePackageBytes(
            byteArrayOf(1),
            openOutput = { throw IllegalStateException("provider") },
            isCancelled = { false },
        )

        listOf(io, security, unavailable, hostileProvider).forEach {
            assertEquals(
                SavePackageResult.Failed(SaveFailure.OUTPUT_UNAVAILABLE),
                it,
            )
        }
    }

    @Test
    fun save_reports_partial_copy_for_write_flush_and_close_failures() {
        val bytes = ByteArray(9_000) { (it and 0xff).toByte() }
        var writeCalls = 0
        val writeFailure = savePackageBytes(
            bytes,
            openOutput = {
                object : OutputStream() {
                    override fun write(value: Int) = Unit

                    override fun write(value: ByteArray, offset: Int, length: Int) {
                        if (writeCalls++ > 0) throw IOException("write")
                    }
                }
            },
            isCancelled = { false },
        )
        val flushFailure = savePackageBytes(
            byteArrayOf(1, 2, 3),
            openOutput = {
                object : ByteArrayOutputStream() {
                    override fun flush() {
                        throw SecurityException("flush")
                    }
                }
            },
            isCancelled = { false },
        )
        val closeFailure = savePackageBytes(
            byteArrayOf(1, 2, 3, 4),
            openOutput = {
                object : ByteArrayOutputStream() {
                    override fun close() {
                        throw IOException("close")
                    }
                }
            },
            isCancelled = { false },
        )
        val hostileWrite = savePackageBytes(
            byteArrayOf(1),
            openOutput = {
                object : OutputStream() {
                    override fun write(value: Int) {
                        throw IllegalStateException("provider")
                    }
                }
            },
            isCancelled = { false },
        )

        assertEquals(
            SavePackageResult.PartialCopyWarning(8_192L, cancelled = false),
            writeFailure,
        )
        assertEquals(
            SavePackageResult.PartialCopyWarning(3L, cancelled = false),
            flushFailure,
        )
        assertEquals(
            SavePackageResult.PartialCopyWarning(4L, cancelled = false),
            closeFailure,
        )
        assertEquals(
            SavePackageResult.PartialCopyWarning(0L, cancelled = false),
            hostileWrite,
        )
    }

    @Test
    fun save_only_completes_after_close_and_preserves_cancellation_warning() {
        val output = ByteArrayOutputStream()
        val complete = savePackageBytes(
            byteArrayOf(1, 2, 3),
            openOutput = { output },
            isCancelled = { false },
        )
        val cancelled = savePackageBytes(
            byteArrayOf(4, 5, 6),
            openOutput = { ByteArrayOutputStream() },
            isCancelled = { true },
        )
        val cancelledWithHostileClose = savePackageBytes(
            byteArrayOf(7, 8, 9),
            openOutput = {
                object : ByteArrayOutputStream() {
                    override fun close() {
                        throw IllegalStateException("provider close")
                    }
                }
            },
            isCancelled = { true },
        )

        assertEquals(SavePackageResult.Complete(3L), complete)
        assertContentEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertIs<SavePackageResult.PartialCopyWarning>(cancelled).also {
            assertEquals(0L, it.bytesWritten)
            assertTrue(it.cancelled)
        }
        assertEquals(
            SavePackageResult.PartialCopyWarning(0L, cancelled = true),
            cancelledWithHostileClose,
        )
    }
}
