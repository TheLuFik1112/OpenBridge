package app.openbridge.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class LocalInterface(
    val name: String,
    val address: String,
)

object NetInterfaces {
    fun list(): List<LocalInterface> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.interfaceAddresses.mapNotNull { iface ->
                    val ip = iface.address as? Inet4Address ?: return@mapNotNull null
                    val host = ip.hostAddress.orEmpty()
                    if (host.isBlank()) null else LocalInterface(nif.name, host)
                }
            }
            .distinctBy { it.address }
            .sortedWith(compareBy({ it.name }, { it.address }))
    }.getOrDefault(emptyList())
}
