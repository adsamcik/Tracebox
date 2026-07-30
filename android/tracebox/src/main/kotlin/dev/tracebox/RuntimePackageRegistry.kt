package dev.tracebox

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.tracebox.api.PackagePreview
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

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
        val bytes: ByteArray,
        val expiresAtMillis: Long,
    )

    private data class ApprovedSlot(
        val nonce: ByteArray,
        val bytes: ByteArray,
        val expiresAtMillis: Long,
    )

    private var prepared: PreparedSlot? = null
    private var approved: ApprovedSlot? = null

    init {
        require(ttlMillis in 1..MAXIMUM_TTL_MILLIS)
        require(maximumPackageBytes in 1..DEFAULT_MAXIMUM_PACKAGE_BYTES)
    }

    @Synchronized
    fun put(preview: PackagePreview, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= maximumPackageBytes)
        expire(clockMillis())
        retire(prepared?.bytes)
        prepared = PreparedSlot(
            digestKey = key(preview.disclosure.plaintextDigestSha256),
            preview = PackagePreview(preview.disclosure),
            bytes = bytes.copyOf(),
            expiresAtMillis = expiry(clockMillis()),
        )
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
            retire(it.bytes)
            it.nonce.fill(0)
        }
        approved = ApprovedSlot(nonce, slot.bytes, expiry(now))
        prepared = null
        return nonce.copyOf()
    }

    @Synchronized
    fun take(nonce: ByteArray): ByteArray? {
        expire(clockMillis())
        val slot = approved ?: return null
        if (!MessageDigest.isEqual(slot.nonce, nonce)) return null
        val result = slot.bytes.copyOf()
        approved = null
        retire(slot.bytes)
        slot.nonce.fill(0)
        return result
    }

    @Synchronized
    fun clear() {
        retire(prepared?.bytes)
        retire(approved?.bytes)
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
            retire(it.bytes)
            prepared = null
        }
        approved?.takeIf { now >= it.expiresAtMillis }?.let {
            retire(it.bytes)
            it.nonce.fill(0)
            approved = null
        }
    }

    private fun expiry(now: Long): Long =
        if (now > Long.MAX_VALUE - ttlMillis) Long.MAX_VALUE else now + ttlMillis

    private fun retire(bytes: ByteArray?) {
        if (bytes == null) return
        bytes.fill(0)
        retiredObserver(bytes)
    }

    private fun key(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val NONCE_BYTES = 32
        val DEFAULT_TTL_MILLIS: Long = TimeUnit.MINUTES.toMillis(10)
        val MAXIMUM_TTL_MILLIS: Long = TimeUnit.HOURS.toMillis(1)
        const val DEFAULT_MAXIMUM_PACKAGE_BYTES = 64 * 1024 * 1024
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

    fun take(nonce: ByteArray): ByteArray? = registry.take(nonce)

    fun clear() = registry.clear()
}
