package dev.tracebox.phase0

import dev.tracebox.anr.AnrWatchdog

object Phase0WatchdogRegistry {
    @Volatile
    var watchdog: AnrWatchdog? = null
}
