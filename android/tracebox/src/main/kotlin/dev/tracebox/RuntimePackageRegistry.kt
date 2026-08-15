package dev.tracebox

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.tracebox.api.DiagnosticPackage
import dev.tracebox.api.PackagePreview
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** One bounded owner for finalized bytes; closing irreversibly zeroes the backing array. */
internal class ApprovedPackageBytes private constructor(
    private val bytes: ByteArray,
    private val retiredObserver: (ByteArray) -> Unit,
) : Closeable {
    private var closed = false

    val sizeBytes: Long
        @Synchronized get() = if (closed) 0L else bytes.size.toLong()

    @Synchronized
    fun <T> use(block: (ByteArray) -> T): T? = if (closed) null else block(bytes)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        bytes.fill(0)
        runCatching { retiredObserver(bytes) }
    }

    @Synchronized
    internal fun isClosed(): Boolean = closed

    companion object {
        const val MAX_BYTES: Int = DiagnosticPackage.MAX_APPROVED_PACKAGE_BYTES

        /** Copies into Tracebox ownership and wipes the caller-owned transfer buffer. */
        fun copyAndConsume(
            source: ByteArray,
            maximumBytes: Int = MAX_BYTES,
            retiredObserver: (ByteArray) -> Unit = {},
        ): ApprovedPackageBytes {
            val owned = try {
                require(maximumBytes in 1..MAX_BYTES)
                require(source.isNotEmpty() && source.size <= maximumBytes)
                source.copyOf()
            } finally {
                source.fill(0)
            }
            return ApprovedPackageBytes(owned, retiredObserver)
        }
    }
}

/**
 * Fixed-capacity ownership for finalized package bytes awaiting disclosure or one-time consume.
 *
 * The personal app needs at most one package in each state. Replacing, expiring, consuming, or
 * clearing a slot zeroes its owned byte array before releasing the reference.
 */
internal class BoundedRuntimePackageRegistry(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maximumPackageBytes: Int = DEFAULT_MAXIMUM_PACKAGE_BYTES,
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
    private val nonceFactory: () -> ByteArray = {
        ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
    },
    private val retiredObserver: (ByteArray) -> Unit = {},
) {
    private data class PreparedSlot(
        val digestKey: String,
        val preview: PackagePreview,
        val bytes: ApprovedPackageBytes,
        val expiresAtMillis: Long,
    )

    private data class ApprovedSlot(
        val nonce: ByteArray,
        val bytes: ApprovedPackageBytes,
        val expiresAtMillis: Long,
    )

    private var prepared: PreparedSlot? = null
    private var approved: ApprovedSlot? = null

    init {
        require(ttlMillis in 1..MAXIMUM_TTL_MILLIS)
        require(maximumPackageBytes in 1..ApprovedPackageBytes.MAX_BYTES)
    }

    @Synchronized
    fun put(preview: PackagePreview, bytes: ByteArray) {
        val ownedBytes = ApprovedPackageBytes.copyAndConsume(
            bytes,
            maximumPackageBytes,
            retiredObserver,
        )
        try {
            val now = clockMillis()
            expire(now)
            val replacement = PreparedSlot(
                digestKey = key(preview.disclosure.plaintextDigestSha256),
                preview = PackagePreview(preview.disclosure),
                bytes = ownedBytes,
                expiresAtMillis = expiry(now),
            )
            prepared?.bytes?.close()
            approved?.let {
                it.bytes.close()
                it.nonce.fill(0)
            }
            approved = null
            prepared = replacement
        } catch (failure: RuntimeException) {
            ownedBytes.close()
            throw failure
        }
    }

    @Synchronized
    fun hasPrepared(digest: ByteArray): Boolean {
        expire(clockMillis())
        return prepared?.digestKey == key(digest)
    }

    @Synchronized
    fun preview(digest: ByteArray): PackagePreview? {
        expire(clockMillis())
        val slot = prepared?.takeIf { it.digestKey == key(digest) } ?: return null
        return PackagePreview(slot.preview.disclosure)
    }

    @Synchronized
    fun approve(digest: ByteArray): ByteArray? {
        val now = clockMillis()
        expire(now)
        val slot = prepared?.takeIf { it.digestKey == key(digest) } ?: return null
        val nonce = nonceFactory().copyOf()
        require(nonce.size == NONCE_BYTES && nonce.any { it != 0.toByte() })

        approved?.let {
            it.bytes.close()
            it.nonce.fill(0)
        }
        approved = ApprovedSlot(nonce, slot.bytes, expiry(now))
        prepared = null
        return nonce.copyOf()
    }

    @Synchronized
    fun take(nonce: ByteArray): ApprovedPackageBytes? {
        expire(clockMillis())
        val slot = approved ?: return null
        if (!MessageDigest.isEqual(slot.nonce, nonce)) return null
        approved = null
        slot.nonce.fill(0)
        return slot.bytes
    }

    @Synchronized
    fun clear() {
        prepared?.bytes?.close()
        approved?.bytes?.close()
        approved?.nonce?.fill(0)
        prepared = null
        approved = null
    }

    @Synchronized
    internal fun activeSlotCounts(): Pair<Int, Int> {
        expire(clockMillis())
        return (if (prepared == null) 0 else 1) to (if (approved == null) 0 else 1)
    }

    private fun expire(now: Long) {
        prepared?.takeIf { now >= it.expiresAtMillis }?.let {
            it.bytes.close()
            prepared = null
        }
        approved?.takeIf { now >= it.expiresAtMillis }?.let {
            it.bytes.close()
            it.nonce.fill(0)
            approved = null
        }
    }

    private fun expiry(now: Long): Long =
        if (now > Long.MAX_VALUE - ttlMillis) Long.MAX_VALUE else now + ttlMillis

    private fun key(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val NONCE_BYTES = 32
        val DEFAULT_TTL_MILLIS: Long = TimeUnit.MINUTES.toMillis(10)
        val MAXIMUM_TTL_MILLIS: Long = TimeUnit.HOURS.toMillis(1)
        const val DEFAULT_MAXIMUM_PACKAGE_BYTES = ApprovedPackageBytes.MAX_BYTES
    }
}

/** Android intent boundary backed by the fixed-capacity byte owner above. */
internal object RuntimePackageRegistry {
    private val registry = BoundedRuntimePackageRegistry()

    fun put(preview: PackagePreview, bytes: ByteArray) = registry.put(preview, bytes)

    fun intent(context: Context, preview: PackagePreview): Intent? {
        val digest = preview.disclosure.plaintextDigestSha256
        if (!registry.hasPrepared(digest)) return null
        return Intent(context, TraceboxPackageDisclosureActivity::class.java)
            .putExtra(TraceboxPackageDisclosureActivity.EXTRA_DIGEST, digest.copyOf())
    }

    fun preview(digest: ByteArray): PackagePreview? = registry.preview(digest)

    fun approve(digest: ByteArray): ByteArray? = registry.approve(digest)

    fun take(nonce: ByteArray): ApprovedPackageBytes? = registry.take(nonce)

    fun clear() = registry.clear()
}
