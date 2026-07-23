package dev.tracebox.api

/**
 * Platform-independent CRC-32C (Castagnoli) used by durable Tracebox frames and policy records.
 *
 * `java.util.zip.CRC32C` is not available on the API-23 baseline, including through the required
 * NIO desugaring profile, so storage must not depend on it.
 */
object Crc32c {
    private const val INITIAL = -1
    private const val POLYNOMIAL = 0x82f63b78.toInt()
    private val table = IntArray(256) { index ->
        var value = index
        repeat(8) {
            value = if ((value and 1) != 0) (value ushr 1) xor POLYNOMIAL else value ushr 1
        }
        value
    }

    fun value(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        var crc = INITIAL
        for (index in offset until offset + length) {
            crc = table[(crc xor bytes[index].toInt()) and 0xff] xor (crc ushr 8)
        }
        return crc.inv()
    }
}
