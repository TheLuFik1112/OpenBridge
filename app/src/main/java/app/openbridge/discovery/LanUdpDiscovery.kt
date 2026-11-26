package app.openbridge.discovery

import android.content.Context
import android.net.wifi.WifiManager
import app.openbridge.core.Beacon
import app.openbridge.core.Diag
import app.openbridge.core.PeerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections

class LanUdpDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val self: () -> PeerInfo,
    private val onPeer: (PeerInfo, String, String) -> Unit,
) {
    private companion object {
        const val PORT = 53531
        const val BEACON_INTERVAL_MS = 2_000L
        const val SWEEP_EVERY_TICKS = 5
        const val MAX_SWEEP_HOSTS = 254
        const val GLOBAL_BROADCAST = "255.255.255.255"

        const val QUIET_BEFORE_SWEEP_MS = 15_000L
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val lastPeerAt = java.util.concurrent.atomic.AtomicLong(0L)

    private var job: Job? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (job != null) return
        acquireMulticastLock()
        job = scope.launch(Dispatchers.IO) {
            val sock = runCatching { openSocket() }.getOrElse { error ->
                Diag.log("lan", "сокет не открылся: $error")
                return@launch
            }
            socket = sock

            launch { beaconLoop(sock) }
            launch { receiveLoop(sock) }
        }
        Diag.log("lan", "LAN-обнаружение запущено на UDP $PORT")
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        releaseMulticastLock()
        Diag.log("lan", "LAN-обнаружение остановлено")
    }

    private fun openSocket(): DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        bind(InetSocketAddress(PORT))
    }

    private suspend fun beaconLoop(sock: DatagramSocket) {
        var tick = 0
        while (scope.isActive) {
            val me = runCatching { self() }.getOrNull()
            if (me != null) {
                val packet = runCatching { buildBeacon(me) }.getOrNull()
                if (packet != null) {
                    val targets = buildList {
                        add(GLOBAL_BROADCAST)
                        addAll(broadcastAddresses().map { it.hostAddress.orEmpty() })
                    }.filter { it.isNotBlank() }.distinct()

                    targets.forEach { address ->
                        runCatching {
                            sock.send(
                                DatagramPacket(
                                    packet,
                                    packet.size,
                                    InetAddress.getByName(address),
                                    PORT,
                                )
                            )
                        }
                    }

                    if (tick % SWEEP_EVERY_TICKS == 0 &&
                        System.currentTimeMillis() - lastPeerAt.get() > QUIET_BEFORE_SWEEP_MS
                    ) {
                        unicastSweep(sock, packet)
                    }
                }
            }
            tick++
            delay(BEACON_INTERVAL_MS)
        }
    }

    private fun buildBeacon(me: PeerInfo): ByteArray {
        val beacon = Beacon(
            id = me.id,
            name = me.name,
            platform = me.platform,
            port = me.port,
            fp = me.certFingerprint,
        )
        return json.encodeToString(Beacon.serializer(), beacon).encodeToByteArray()
    }

    private fun unicastSweep(sock: DatagramSocket, payload: ByteArray) {
        hostAddresses().take(MAX_SWEEP_HOSTS * 2).forEach { address ->
            runCatching {
                sock.send(DatagramPacket(payload, payload.size, address, PORT))
            }
        }
    }

    private fun receiveLoop(sock: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (scope.isActive && !sock.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                val text = String(packet.data, 0, packet.length)
                val beacon = runCatching {
                    json.decodeFromString(Beacon.serializer(), text)
                }.getOrNull() ?: continue
                if (beacon.id.isBlank()) continue
                if (beacon.id == runCatching { self().id }.getOrNull()) continue

                val peer = PeerInfo(
                    id = beacon.id,
                    name = beacon.name,
                    platform = beacon.platform,
                    port = beacon.port,
                    certFingerprint = beacon.fp.ifBlank { beacon.id },
                )
                onPeer(peer, packet.address?.hostAddress.orEmpty(), "udp")
                lastPeerAt.set(System.currentTimeMillis())
            } catch (_: Exception) {
                if (sock.isClosed) break
            }
        }
    }

    private fun interfaces(): List<NetworkInterface> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
    }.getOrDefault(emptyList()).filter { nif ->
        nif.isUp && !nif.isLoopback && isUsable(nif.name)
    }

    private fun isUsable(name: String): Boolean {
        val lower = name.lowercase()

        return !lower.startsWith("rmnet") &&
            !lower.startsWith("ccmni") &&
            !lower.startsWith("dummy") &&
            !lower.startsWith("tun") &&
            !lower.startsWith("p2p")
    }

    private fun broadcastAddresses(): List<Inet4Address> = interfaces().flatMap { nif ->
        nif.interfaceAddresses.mapNotNull { it.broadcast as? Inet4Address }
    }.distinct()

    private fun hostAddresses(): List<InetAddress> = interfaces().flatMap { nif ->
        nif.interfaceAddresses.flatMap { iface ->
            val ip = iface.address as? Inet4Address ?: return@flatMap emptyList<InetAddress>()
            val bytes = ip.address
            (1..MAX_SWEEP_HOSTS).mapNotNull { host ->
                runCatching {
                    InetAddress.getByAddress(byteArrayOf(bytes[0], bytes[1], bytes[2], host.toByte()))
                }.getOrNull()
            }
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("openbridge_multicast").apply {
                setReferenceCounted(false)
                acquire()
            }
            Diag.log("lan", "MulticastLock захвачен")
        }.onFailure { Diag.log("lan", "MulticastLock failed: $it") }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
}
