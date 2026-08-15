package dev.tracebox.ui.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.tracebox.api.DiagnosticPackage
import dev.tracebox.api.SaveFailure
import dev.tracebox.api.SavePackageResult
import dev.tracebox.api.SharePackageResult
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class TraceboxDiagnosticsUiTest {
    @Test
    fun automatic_primary_action_prefers_host_upload_then_share() {
        val actions = TraceboxPackageActions()

        assertEquals(
            ResolvedPrimaryAction.UPLOAD,
            resolvePrimaryAction(TraceboxPrimaryAction.AUTOMATIC, actions, uploaderAvailable = true),
        )
        assertEquals(
            ResolvedPrimaryAction.SHARE,
            resolvePrimaryAction(TraceboxPrimaryAction.AUTOMATIC, actions, uploaderAvailable = false),
        )
    }

    @Test
    fun unavailable_or_disallowed_primary_actions_fail_closed_to_review_only() {
        assertEquals(
            ResolvedPrimaryAction.REVIEW_ONLY,
            resolvePrimaryAction(
                TraceboxPrimaryAction.UPLOAD,
                TraceboxPackageActions(upload = true, share = false),
                uploaderAvailable = false,
            ),
        )
        assertEquals(
            ResolvedPrimaryAction.REVIEW_ONLY,
            resolvePrimaryAction(
                TraceboxPrimaryAction.SHARE,
                TraceboxPackageActions(upload = false, share = false),
                uploaderAvailable = true,
            ),
        )
    }

    @Test
    fun advanced_configuration_rejects_ambiguous_control_values() {
        assertFailsWith<IllegalArgumentException> {
            TraceboxAdvancedControls(performanceThresholdsNanos = listOf(0L, -1L))
        }
        assertFailsWith<IllegalArgumentException> {
            TraceboxAdvancedControls(performanceThresholdsNanos = listOf(0L, 0L))
        }
    }

    @Test
    fun upload_request_exposes_only_bounded_metadata_and_scoped_approved_bytes() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val digest = ByteArray(32) { it.toByte() }
        val request = TraceboxUploadRequest(FakeDiagnosticPackage(bytes, digest))

        assertEquals("application/zip", request.contentType)
        assertEquals("tracebox.tbdiag", request.suggestedFileName)
        assertEquals(4L, request.sizeBytes)
        assertContentEquals(digest, request.plaintextDigestSha256)
        assertContentEquals(bytes, request.useInputStream(InputStream::readBytes))

        val returnedDigest = request.plaintextDigestSha256
        returnedDigest.fill(0)
        assertContentEquals(digest, request.plaintextDigestSha256)
    }

    @Test
    fun package_owner_closes_on_replacement_explicit_retirement_and_screen_disposal() {
        val owner = DiagnosticPackageOwner()
        val first = FakeDiagnosticPackage(byteArrayOf(1), ByteArray(32))
        val second = FakeDiagnosticPackage(byteArrayOf(2), ByteArray(32) { 2 })

        owner.replace(first)
        owner.replace(second)
        owner.retire(first)

        assertEquals(1, first.closeCalls)
        assertEquals(0, second.closeCalls)
        assertSame(second, owner.current())

        owner.close()
        owner.close()

        assertEquals(1, second.closeCalls)
        assertNull(owner.current())
    }
}

private class FakeDiagnosticPackage(
    private val bytes: ByteArray,
    private val digest: ByteArray,
) : DiagnosticPackage {
    var closeCalls: Int = 0
        private set

    override val plaintextDigestSha256: ByteArray
        get() = digest.copyOf()
    override val sizeBytes: Long = bytes.size.toLong()
    override val receipt: StateFlow<SharePackageResult> =
        MutableStateFlow(SharePackageResult.NOT_STARTED)

    override fun shareIntent(context: Context): Intent? = null
    override fun createSaveIntent(): Intent = error("not used")
    override fun save(
        context: Context,
        destination: Uri,
        isCancelled: () -> Boolean,
    ): SavePackageResult = SavePackageResult.Failed(SaveFailure.OUTPUT_UNAVAILABLE)

    override fun <T> useInputStream(block: (InputStream) -> T): T =
        bytes.inputStream().use(block)

    override fun deleteStaging(): Boolean = true

    override fun close() {
        closeCalls += 1
    }
}
