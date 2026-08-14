package dev.tracebox.phase0

/** The release claim variant has no host-owned network capability or network implementation. */
object HostNetworkControl {
    fun probe(host: String, port: Int): NetworkProbeResult {
        require(host.isNotBlank())
        require(port in 1..65535)
        return NetworkProbeResult(
            capability = NetworkCapability.ABSENT,
            dnsAttempted = false,
            connectAttempted = false,
            connectSucceeded = false,
        )
    }
}
