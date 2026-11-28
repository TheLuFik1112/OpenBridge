package app.openbridge.discovery

import android.content.Context
import android.net.ConnectivityManager
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
    private val forceSweep = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var preferredAddress: String? = null

    private var job: Job? = null
    private var socket: DatagramSocket? = null
    private var sendSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun setPreferredAddress(address: String?) {
        preferredAddress = address?.trim()?.takeIf { it.isNotBlank() }
    }

    fun restart() {
        stop()
        start()
    }

    fun start() {
        if (job != null) return
        forceSweep.set(true)
        acquireMulticastLock()
        val receive = runCatching { openReceiveSocket() }.getOrElse { error ->
            Diag.log("lan", "socket failed to open: $error")
            releaseMulticastLock()
            return
        }
        socket = receive
        val sender = openSendSocket(receive)
        sendSocket = sender
        job = scope.launch(Dispatchers.IO) {
            launch { beaconLoop(sender) }
            launch { receiveLoop(receive) }
        }
        Diag.log("lan", "LAN discovery started on UDP $PORT, interface=${preferredAddress ?: "auto"}")
    }

    fun stop() {
        job?.cancel()
        job = null
        val receive = socket
        val sender = sendSocket
        socket = null
        sendSocket = null
        runCatching { if (sender !== receive) sender?.close() }
        runCatching { receive?.close() }
        releaseMulticastLock()
        Diag.log("lan", "LAN discovery stopped")
    }

    private fun openReceiveSocket(): DatagramSocket {
        val sock = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
        }
        bindToNetwork(sock)
        sock.bind(InetSocketAddress(PORT))
        return sock
    }

    private fun openSendSocket(receive: DatagramSocket): DatagramSocket {
        val target = preferredAddress ?: return receive
        val sock = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
        }
        return runCatching {
            bindToNetwork(sock)
            sock.bind(InetSocketAddress(InetAddress.getByName(target), 0))
            sock
        }.getOrElse { error ->
            runCatching { sock.close() }
            Diag.log("lan", "send socket bind to $target failed: ${error.javaClass.simpleName}")
            receive
        }
    }

    private fun bindToNetwork(sock: DatagramSocket) {
        val target = preferredAddress ?: return
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val network = runCatching {
            manager.allNetworks.firstOrNull { net ->
                val props = manager.getLinkProperties(net) ?: return@firstOrNull false
                props.linkAddresses.any { it.address.hostAddress == target }
            }
        }.getOrNull()
        if (network == null) {
            Diag.log("lan", "no network owns $target, network bind skipped")
            return
        }
        runCatching { network.bindSocket(sock) }
            .onFailure { Diag.log("lan", "network bind failed: ${it.javaClass.simpleName}") }
    }

    private suspend fun beaconLoop(sock: DatagramSocket) {
        var tick = 0
        while (scope.isActive && !sock.isClosed) {
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

                    if (forceSweep.getAndSet(false) ||
                        (tick % SWEEP_EVERY_TICKS == 0 &&
                            System.currentTimeMillis() - lastPeerAt.get() > QUIET_BEFORE_SWEEP_MS)
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
        nif.isUp && !nif.isLoopback && allowed(nif)
    }

    private fun allowed(nif: NetworkInterface): Boolean {
        val target = preferredAddress
        if (target == null) return isUsable(nif.name)
        return nif.interfaceAddresses.any { iface ->
            (iface.address as? Inet4Address)?.hostAddress == target
        }
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
            Diag.log("lan", "MulticastLock acquired")
        }.onFailure { Diag.log("lan", "MulticastLock failed: $it") }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
}
