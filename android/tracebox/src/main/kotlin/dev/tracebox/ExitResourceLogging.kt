package dev.tracebox

import dev.tracebox.anr.SyntheticApplicationExitInfo
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.TraceboxLogger
import dev.tracebox.api.public

private val OS_EXIT_RESOURCES = LogTemplate.of(
    "Historical Android exit timeMs={} reason={} status={} importance={} pssKb={} rssKb={}",
)

/** Numeric, historical OS facts; no free-form OS description or process identity crosses this API. */
internal fun logExitResources(logger: TraceboxLogger, exit: SyntheticApplicationExitInfo) {
    logger.info(OS_EXIT_RESOURCES, public(exit.timestampMillis), public(exit.reason), public(exit.status),
        public(exit.importance), public(exit.pssKilobytes), public(exit.rssKilobytes))
}
