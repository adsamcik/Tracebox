package dev.tracebox.api

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32cTest {
    @Test
    fun castagnoli_known_vector_and_sliced_input_match() {
        val bytes = "x123456789y".encodeToByteArray()

        assertEquals(0xe3069283.toInt(), Crc32c.value(bytes, 1, 9))
    }
}
