package dev.tracebox.phase0

/** Bounded positive-control result; exception messages and resolved addresses are never retained. */
data class NetworkProbeResult(
    val capability: NetworkCapability,
    val dnsAttempted: Boolean,
    val connectAttempted: Boolean,
    val connectSucceeded: Boolean,
)

enum class NetworkCapability { ABSENT, HOST_CONTROL }
