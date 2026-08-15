package dev.tracebox.export.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.tracebox.export.MaterializedPackage
import java.util.UUID

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

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            packageHandle?.let(DisclosurePackageRegistry::remove)
        }
        super.onDestroy()
    }

    private fun showDisclosure(facts: DisclosureFacts) {
        val details = buildString {
            append(getString(R.string.tracebox_export_disclosure_included, facts.includedCount, facts.includedBytes))
                .append('\n')
            append(
                getString(
                    R.string.tracebox_export_disclosure_privacy,
                    facts.privacyClasses.sortedBy { it.name },
                ),
            ).append('\n')
            append(
                getString(
                    R.string.tracebox_export_disclosure_transforms,
                    facts.transformations.sorted(),
                ),
            ).append('\n')
            append(getString(R.string.tracebox_export_disclosure_omissions, facts.omissions)).append('\n')
            append(getString(R.string.tracebox_export_disclosure_source_range, facts.sourceRangeMillis)).append('\n')
            append(getString(R.string.tracebox_export_disclosure_digest, facts.plaintextDigest.toHex())).append('\n')
            append(
                getString(
                    R.string.tracebox_export_disclosure_entry_hashes,
                    facts.entries.joinToString { "${it.path}:${it.sha256.toHex()}" },
                ),
            ).append('\n')
            append(
                getString(
                    R.string.tracebox_export_disclosure_raw_artifacts,
                    facts.rawC2Artifacts.map(DisclosureEntry::path),
                ),
            )
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@TraceboxDisclosureActivity).apply {
                text = getString(R.string.tracebox_export_disclosure_title)
            })
            addView(TextView(this@TraceboxDisclosureActivity).apply {
                text = getString(R.string.tracebox_export_disclosure_privacy_notice)
            })
            addView(TextView(this@TraceboxDisclosureActivity).apply { text = details })
            addView(Button(this@TraceboxDisclosureActivity).apply {
                text = getString(R.string.tracebox_export_disclosure_approve)
                setOnClickListener { confirmFreshGesture() }
            })
            addView(Button(this@TraceboxDisclosureActivity).apply {
                text = getString(R.string.tracebox_export_disclosure_cancel)
                setOnClickListener {
                    setResult(RESULT_CANCELED)
                    finish()
                }
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
        fun reserveStagingQuota(destination: java.nio.file.Path): (() -> Unit)?
        fun releaseQuotaReservation()
        fun retire()
    }

    private class ActivityApprovedPackage(
        materialized: MaterializedPackage,
        private val facts: DisclosureFacts,
    ) : ApprovedPackage {
        private var materialized: MaterializedPackage? = materialized
        private var token: ApprovalToken? = ApprovalToken(
            facts.plaintextDigest.copyOf(),
            facts.policyEpoch,
            ProtectionMode.LOCAL_ONLY,
            RecipientSet.LocalOnly,
        )

        override fun approvedPlaintextDigest(): ByteArray = synchronized(this) {
            activeToken().plaintextDigest.copyOf()
        }

        override fun exactBytes(): ByteArray = synchronized(this) {
            activeMaterialized().exactBytes()
        }

        override fun matches(bytes: ByteArray): Boolean = synchronized(this) {
            activeToken().plaintextDigest.contentEquals(bytes)
        }

        override fun protectionMode(): ProtectionMode = synchronized(this) {
            activeToken().protectionMode
        }

        override fun recipients(): RecipientSet = synchronized(this) {
            activeToken().recipients
        }

        override fun reserveStagingQuota(destination: java.nio.file.Path): (() -> Unit)? = synchronized(this) {
            activeMaterialized().reserveStagingQuota(destination)
        }

        override fun releaseQuotaReservation() {
            synchronized(this) {
                materialized?.releaseStagingQuota()
            }
        }

        override fun retire() {
            val retired = synchronized(this) {
                val current = materialized ?: return
                materialized = null
                token?.retire()
                token = null
                current
            }
            retired.releaseStagingQuota()
        }

        private fun activeMaterialized(): MaterializedPackage =
            checkNotNull(materialized) { "approved package has been retired" }

        private fun activeToken(): ApprovalToken =
            checkNotNull(token) { "approved package has been retired" }
    }

    private class ApprovalToken(
        val plaintextDigest: ByteArray,
        val policyEpoch: Long,
        val protectionMode: ProtectionMode,
        val recipients: RecipientSet,
    ) {
        fun retire() {
            plaintextDigest.fill(0)
        }
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
    private val store = DisclosurePackageRegistryStore()

    fun put(materialized: MaterializedPackage): String = store.put(materialized)
    fun find(handle: String): MaterializedPackage? = store.find(handle)
    fun remove(handle: String) = store.remove(handle)
    fun clear() = store.clear()
}

internal class DisclosurePackageRegistryStore(
    clock: ExportClock = ExportClock { SystemClock.elapsedRealtime() },
) {
    private val packages = SingleSlotExpiringRegistry(
        ttlMillis = REGISTRY_TTL_MILLIS,
        nowMillis = clock::nowMillis,
        retire = MaterializedPackage::releaseStagingQuota,
    )

    fun put(materialized: MaterializedPackage): String = packages.put(materialized)
    fun find(handle: String): MaterializedPackage? = packages.find(handle)
    fun remove(handle: String) = packages.remove(handle)
    fun clear() = packages.clear()
}

internal object ApprovalResultRegistry {
    private val results = SingleSlotExpiringRegistry(
        ttlMillis = REGISTRY_TTL_MILLIS,
        nowMillis = SystemClock::elapsedRealtime,
        retire = TraceboxDisclosureActivity.ApprovedPackage::retire,
    )

    fun put(approved: TraceboxDisclosureActivity.ApprovedPackage): String = results.put(approved)
    fun take(handle: String): TraceboxDisclosureActivity.ApprovedPackage? = results.take(handle)
    fun clear() = results.clear()
}

/**
 * A process-local capability handoff with a hard one-entry bound. Expired, replaced, removed, or
 * cleared values are retired exactly once; a successfully taken value transfers that responsibility
 * to the caller.
 */
internal class SingleSlotExpiringRegistry<T>(
    private val ttlMillis: Long,
    private val nowMillis: () -> Long,
    private val retire: (T) -> Unit,
    private val newHandle: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Entry<T>(val handle: String, val value: T, val expiresAtMillis: Long)

    private val lock = Any()
    private var entry: Entry<T>? = null

    init {
        require(ttlMillis > 0) { "registry TTL must be positive" }
    }

    fun put(value: T): String {
        val handle = newHandle()
        val now = nowMillis()
        val replacement = Entry(handle, value, saturatingAdd(now, ttlMillis))
        val retired = synchronized(lock) {
            entry.also { entry = replacement }
        }
        retired?.value?.let(retire)
        return handle
    }

    fun find(handle: String): T? {
        var expired: T? = null
        val found = synchronized(lock) {
            val current = entry ?: return@synchronized null
            if (isExpired(current)) {
                entry = null
                expired = current.value
                null
            } else {
                current.value.takeIf { current.handle == handle }
            }
        }
        expired?.let(retire)
        return found
    }

    fun take(handle: String): T? {
        var expired: T? = null
        val taken = synchronized(lock) {
            val current = entry ?: return@synchronized null
            when {
                isExpired(current) -> {
                    entry = null
                    expired = current.value
                    null
                }
                current.handle == handle -> {
                    entry = null
                    current.value
                }
                else -> null
            }
        }
        expired?.let(retire)
        return taken
    }

    fun remove(handle: String) {
        val retired = synchronized(lock) {
            val current = entry ?: return@synchronized null
            if (isExpired(current) || current.handle == handle) {
                entry = null
                current.value
            } else {
                null
            }
        }
        retired?.let(retire)
    }

    fun clear() {
        val retired = synchronized(lock) {
            entry?.value.also { entry = null }
        }
        retired?.let(retire)
    }

    internal fun activeCount(): Int = synchronized(lock) {
        if (entry == null) 0 else 1
    }

    private fun isExpired(current: Entry<T>): Boolean =
        nowMillis() >= current.expiresAtMillis

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

private const val REGISTRY_TTL_MILLIS = 10L * 60L * 1_000L

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
