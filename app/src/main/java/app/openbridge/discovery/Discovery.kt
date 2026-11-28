package app.openbridge.discovery

import android.content.Context
import app.openbridge.core.PeerInfo
import kotlinx.coroutines.CoroutineScope

typealias PeerCallback = (PeerInfo, String, String) -> Unit

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
