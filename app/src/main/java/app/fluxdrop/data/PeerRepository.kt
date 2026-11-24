package app.fluxdrop.data

import android.content.Context
import androidx.compose.runtime.Immutable
import app.fluxdrop.core.PeerInfo
import app.fluxdrop.core.TrustedPeer
import app.fluxdrop.core.IdentityStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Immutable
data class DiscoveredPeer(
    val info: PeerInfo,
    val ip: String,
    val lastSeen: Long,
    val source: String,   // "udp" | "nsd" | "ble"
) {
    val canSend: Boolean get() = ip.isNotBlank() || source == "nearby"
}

class PeerRepository(context: Context, private val json: Json) {

    private val store = JsonFile(
        context = context,
        fileName = "trusted.json",
        serializer = ListSerializer(TrustedPeer.serializer()),
        json = json,
        default = emptyList(),
    )

    private val _trusted = MutableStateFlow(store.load())
    val trusted: StateFlow<List<TrustedPeer>> = _trusted

    private val _discovered = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discovered: StateFlow<List<DiscoveredPeer>> = _discovered

    /**
     * Живое время последнего пакета по каждому пиру. Хранится отдельно от
     * опубликованного списка: маяки приходят каждые 2 секунды, и если писать
     * новый lastSeen в StateFlow, весь UI перерисовывается 2 раза в секунду
     * без единого визуального изменения.
     */
    private val lastSeenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Synchronized
    fun onDiscovered(info: PeerInfo, ip: String, source: String) {
        val now = System.currentTimeMillis()
        lastSeenAt[info.id] = now

        val current = _discovered.value
        val existing = current.firstOrNull { it.info.id == info.id }
        val mergedIp = ip.ifBlank { existing?.ip.orEmpty() }
        val merged = DiscoveredPeer(
            info = info.copy(certFingerprint = info.certFingerprint.ifBlank { info.id }),
            ip = mergedIp,
            // Сохраняем первое появление: порядок списка не дрожит на каждом маяке.
            lastSeen = existing?.lastSeen ?: now,
            source = if (ip.isNotBlank()) source else existing?.source ?: source,
        )
        // Ничего видимого не изменилось — не трогаем StateFlow вообще.
        if (existing == merged) return

        _discovered.value = (current.filterNot { it.info.id == info.id } + merged)
            .sortedByDescending { it.lastSeen }
    }

    /** Убирает находку конкретного канала (например, Nearby-устройство ушло). */
    @Synchronized
    fun onPeerLost(id: String, source: String) {
        if (_discovered.value.none { it.info.id == id && it.source == source }) return
        lastSeenAt.remove(id)
        _discovered.value = _discovered.value.filterNot { it.info.id == id && it.source == source }
    }

    @Synchronized
    fun prune(ttlMs: Long = 20_000) {
        val cutoff = System.currentTimeMillis() - ttlMs
        val stale = lastSeenAt.filterValues { it <= cutoff }.keys.toSet()
        if (stale.isEmpty()) return
        stale.forEach { lastSeenAt.remove(it) }
        val filtered = _discovered.value.filterNot { it.info.id in stale }
        if (filtered.size != _discovered.value.size) _discovered.value = filtered
    }

    fun isTrusted(sender: PeerInfo, kind: String): Boolean {
        val peer = _trusted.value.firstOrNull { it.id == sender.id } ?: return false
        if (peer.revoked) return false
        val cert = runCatching { IdentityStore.certFromB64(sender.certB64) }.getOrNull() ?: return false
        if (peer.fingerprint != IdentityStore.fingerprintOf(cert)) return false
        return when (kind) {
            "text" -> peer.autoText
            "files" -> peer.autoFiles
            else -> peer.autoText || peer.autoFiles
        }
    }

    @Synchronized
    fun trust(sender: PeerInfo, autoText: Boolean = true, autoFiles: Boolean = true): TrustedPeer {
        val peer = TrustedPeer(
            id = sender.id,
            name = sender.name,
            platform = sender.platform,
            certB64 = sender.certB64,
            publicKeyB64 = sender.publicKeyB64,
            fingerprint = sender.certFingerprint,
            trustedAt = System.currentTimeMillis(),
            autoText = autoText,
            autoFiles = autoFiles,
        )
        _trusted.value = _trusted.value.filterNot { it.id == peer.id } + peer
        store.save(_trusted.value)
        return peer
    }

    @Synchronized
    fun revoke(id: String) {
        _trusted.value = _trusted.value.map {
            if (it.id == id) it.copy(revoked = true) else it
        }
        store.save(_trusted.value)
    }

    @Synchronized
    fun remove(id: String) {
        _trusted.value = _trusted.value.filterNot { it.id == id }
        store.save(_trusted.value)
    }

    @Synchronized
    fun rename(id: String, name: String) {
        _trusted.value = _trusted.value.map { if (it.id == id) it.copy(name = name) else it }
        store.save(_trusted.value)
    }

    fun pinnedFingerprints(): Set<String> =
        _trusted.value.filterNot { it.revoked }.map { it.fingerprint }.toSet()
}
