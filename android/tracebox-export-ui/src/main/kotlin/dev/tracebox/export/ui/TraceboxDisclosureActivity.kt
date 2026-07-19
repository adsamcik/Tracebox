package dev.tracebox.export.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.tracebox.export.MaterializedPackage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The only production token issuer. A restored activity always creates a new rendered session and
 * deliberately has no approved token until the user presses Confirm again.
 */
class TraceboxDisclosureActivity : Activity() {
    private val stateMachine = DisclosureActivityStateMachine()
    private var packageHandle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageHandle = savedInstanceState?.getString(HANDLE) ?: intent.getStringExtra(HANDLE)
        val materialized = packageHandle?.let(DisclosurePackageRegistry::find)
        if (materialized == null) {
            finish()
            return
        }
        stateMachine.restore(materialized)
        showDisclosure(checkNotNull(stateMachine.facts()))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(HANDLE, packageHandle)
        // Approval is intentionally not saved. Restoration requires a new confirmation gesture.
    }

    private fun showDisclosure(facts: DisclosureFacts) {
        val details = buildString {
            append("Included: ${facts.includedCount} values, ${facts.includedBytes} bytes\n")
            append("Privacy: ${facts.privacyClasses.sortedBy { it.name }}\n")
            append("Transforms: ${facts.transformations.sorted()}\n")
            append("Omissions: ${facts.omissions}\n")
            append("Source range: ${facts.sourceRangeMillis}\n")
            append("Plaintext digest: ${facts.plaintextDigest.toHex()}\n")
            append("Entry hashes: ${facts.entries.joinToString { "${it.path}:${it.sha256.toHex()}" }}\n")
            append("Raw C2 artifacts: ${facts.rawC2Artifacts.map(DisclosureEntry::path)}")
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@TraceboxDisclosureActivity).apply { text = details })
            addView(Button(this@TraceboxDisclosureActivity).apply {
                text = "Confirm package"
                setOnClickListener { confirmFreshGesture() }
            })
        })
    }

    /** The sole production approval creation point, reached only by the Confirm button callback. */
    private fun confirmFreshGesture() {
        val approved = stateMachine.confirmFreshGesture() ?: return
        val resultHandle = ApprovalResultRegistry.put(approved)
        setResult(RESULT_OK, Intent().putExtra(RESULT_HANDLE, resultHandle))
        finish()
    }

    private class DisclosureActivityStateMachine {
        private var materialized: MaterializedPackage? = null
        private var facts: DisclosureFacts? = null
        private var approval: ApprovedPackage? = null

        fun restore(materialized: MaterializedPackage) {
            val decoded = DisclosureRenderer.render(materialized) as? DisclosureDecodeResult.Decoded
                ?: throw IllegalArgumentException("finalized package cannot be disclosed")
            this.materialized = materialized
            facts = decoded.facts
            approval = null
        }

        fun facts(): DisclosureFacts? = facts

        fun confirmFreshGesture(): ApprovedPackage? {
            val currentMaterialized = materialized ?: return null
            val currentFacts = facts ?: return null
            return ActivityApprovedPackage(currentMaterialized, currentFacts).also { approval = it }
        }
    }

    /** Only the private Activity implementation below can mint this sealed approval capability. */
    sealed interface ApprovedPackage {
        fun approvedPlaintextDigest(): ByteArray
        fun exactBytes(): ByteArray
        fun matches(bytes: ByteArray): Boolean
        fun protectionMode(): ProtectionMode
        fun recipients(): RecipientSet
        fun transferQuotaReservation(destination: java.nio.file.Path): Boolean
        fun releaseQuotaReservation()
    }

    private class ActivityApprovedPackage(
        private val materialized: MaterializedPackage,
        private val facts: DisclosureFacts,
    ) : ApprovedPackage {
        private val token = ApprovalToken(
            facts.plaintextDigest.copyOf(),
            facts.policyEpoch,
            ProtectionMode.LOCAL_ONLY,
            RecipientSet.LocalOnly,
        )

        override fun approvedPlaintextDigest(): ByteArray = token.plaintextDigest.copyOf()
        override fun exactBytes(): ByteArray = materialized.exactBytes()
        override fun matches(bytes: ByteArray): Boolean = token.plaintextDigest.contentEquals(bytes)
        override fun protectionMode(): ProtectionMode = token.protectionMode
        override fun recipients(): RecipientSet = token.recipients
        override fun transferQuotaReservation(destination: java.nio.file.Path): Boolean =
            materialized.transferStagingQuota(destination)
        override fun releaseQuotaReservation() = materialized.releaseStagingQuota()
    }

    private class ApprovalToken(
        val plaintextDigest: ByteArray,
        val policyEpoch: Long,
        val protectionMode: ProtectionMode,
        val recipients: RecipientSet,
    )

    companion object {
        private const val HANDLE = "dev.tracebox.export.ui.package_handle"
        const val RESULT_HANDLE = "dev.tracebox.export.ui.approval_handle"

        internal fun intent(context: android.content.Context, materialized: MaterializedPackage): Intent {
            val handle = DisclosurePackageRegistry.put(materialized)
            return Intent(context, TraceboxDisclosureActivity::class.java).putExtra(HANDLE, handle)
        }
    }
}

internal object DisclosurePackageRegistry {
    private val packages = ConcurrentHashMap<String, MaterializedPackage>()
    fun put(materialized: MaterializedPackage): String = UUID.randomUUID().toString().also { packages[it] = materialized }
    fun find(handle: String): MaterializedPackage? = packages[handle]
}

internal object ApprovalResultRegistry {
    private val results = ConcurrentHashMap<String, TraceboxDisclosureActivity.ApprovedPackage>()
    fun put(approved: TraceboxDisclosureActivity.ApprovedPackage): String = UUID.randomUUID().toString().also { results[it] = approved }
    fun take(handle: String): TraceboxDisclosureActivity.ApprovedPackage? = results.remove(handle)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
