package dev.tracebox.phase0

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Host-owned positive control used to prove that the observer sees attempted DNS/TCP traffic. */
object HostNetworkControl {
    fun probe(host: String, port: Int): NetworkProbeResult {
        require(host.isNotBlank())
        require(port in 1..65535)
        var dnsAttempted = false
        var connectAttempted = false
        var connectSucceeded = false
        try {
            dnsAttempted = true
            val address = InetAddress.getByName(host)
            connectAttempted = true
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
                connectSucceeded = socket.isConnected
            }
        } catch (_: SecurityException) {
            // Expected when the runner blocks this UID.
        } catch (_: java.io.IOException) {
            // A refused/blocked connection still proves that the control attempted egress.
        }
        return NetworkProbeResult(
            capability = NetworkCapability.HOST_CONTROL,
            dnsAttempted = dnsAttempted,
            connectAttempted = connectAttempted,
            connectSucceeded = connectSucceeded,
        )
    }

    private const val CONNECT_TIMEOUT_MILLIS = 1_000
}
