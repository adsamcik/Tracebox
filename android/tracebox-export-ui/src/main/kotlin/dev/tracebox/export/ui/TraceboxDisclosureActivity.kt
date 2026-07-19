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
        showDisclosure(checkNotNull(stateMachine.facts()).facts)
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

    private fun confirmFreshGesture() {
        val approved = stateMachine.confirmFreshGesture() ?: return
        val resultHandle = ApprovalResultRegistry.put(approved)
        setResult(RESULT_OK, Intent().putExtra(RESULT_HANDLE, resultHandle))
        finish()
    }

    internal class DisclosureActivityStateMachine {
        private var rendered: RenderedDisclosure? = null
        private var approval: ApprovedPackage? = null

        fun restore(materialized: MaterializedPackage) {
            rendered = DisclosureRenderer.rendered(materialized)
            approval = null
        }

        fun facts(): RenderedDisclosure? = rendered

        fun confirmFreshGesture(): ApprovedPackage? {
            val current = rendered ?: return null
            return ApprovalIssuer.afterFreshConfirmation(current).also { approval = it }
        }

        internal fun approvalForTestOnly(): ApprovedPackage? = approval
    }

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
    private val results = ConcurrentHashMap<String, ApprovedPackage>()
    fun put(approved: ApprovedPackage): String = UUID.randomUUID().toString().also { results[it] = approved }
    fun take(handle: String): ApprovedPackage? = results.remove(handle)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
