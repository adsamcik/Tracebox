package dev.tracebox

/**
 * Owns at most one started-and-bound lifetime token.
 *
 * The start step runs before bind because Android requires the handler's existing start intent to
 * carry its one-time authorization. A failed bind compensates by stopping that exact token. The
 * held token is cleared before unbind so re-entrant callbacks cannot release it twice.
 */
internal class SingleHeldLifetime<T>(
    private val start: (T) -> Boolean,
    private val bind: (T) -> Boolean,
    private val stop: (T) -> Unit,
    private val unbind: (T) -> Unit,
) {
    private val lock = Any()
    private var held: T? = null

    fun startAndHold(candidate: T): Boolean = synchronized(lock) {
        if (held != null) return false

        var started = false
        var bound = false
        try {
            started = start(candidate)
            if (!started) return false
            bound = bind(candidate)
            if (bound) held = candidate
            return bound
        } finally {
            if (!bound) {
                if (started) runCatching { stop(candidate) }
            }
        }
    }

    fun release(): Boolean = synchronized(lock) {
        val current = held.also { held = null } ?: return true
        runCatching { unbind(current) }.isSuccess
    }

    internal fun isHeld(): Boolean = synchronized(lock) { held != null }
}
