package dev.tracebox.phase0

/** Deterministic call chain retained by the lab so release R8 mapping/retrace has a known target. */
object R8Scenario {
    fun optimizedFrame(seed: Int): Int = outer(seed)

    private fun outer(seed: Int): Int = middle(seed + 1)

    private fun middle(seed: Int): Int = leaf(seed * 3)

    private fun leaf(seed: Int): Int = seed xor MASK

    private const val MASK = 0x5a5a
}
