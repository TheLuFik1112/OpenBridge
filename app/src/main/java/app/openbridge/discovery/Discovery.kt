package app.openbridge.discovery

import android.content.Context
import app.openbridge.core.PeerInfo
import kotlinx.coroutines.CoroutineScope

typealias PeerCallback = (PeerInfo, String, String) -> Unit

/**
 * Контур 1: обнаружение в локальной сети.
 *
 * Раньше здесь были три источника (mDNS через NsdManager, multicast и BLE).
 * NsdManager оказался ненадёжным, а BLE-реклама конфликтовала с Nearby.
 * Теперь один канал — активный UDP broadcast + unicast sweep с MulticastLock,
 * а поиск без роутера делает NearbyP2pManager.
 */
class PeerDiscovery(
    context: Context,
    scope: CoroutineScope,
    self: () -> PeerInfo,
    onPeer: PeerCallback,
) {
    private val lan = LanUdpDiscovery(context, scope, self, onPeer)

    fun start() = lan.start()

    fun stop() = lan.stop()
}
