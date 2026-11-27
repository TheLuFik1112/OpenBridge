package app.openbridge.net

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import app.openbridge.core.AppGraph
import app.openbridge.core.HistoryEntry
import app.openbridge.core.IdentityStore
import app.openbridge.core.IncomingStage
import app.openbridge.core.OfferResponse
import app.openbridge.core.TransferOffer
import app.openbridge.core.TransferResult
import app.openbridge.core.filesOrEmpty
import app.openbridge.core.kind
import app.openbridge.core.summary
import app.openbridge.core.textOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class IncomingCoordinator(private val g: AppGraph) {
    private class Pending(
        val offer: TransferOffer,
        val decision: CompletableDeferred<Boolean>,
        val stage: MutableStateFlow<IncomingStage>,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val tokens = ConcurrentHashMap<String, String>()

    fun offer(id: String): TransferOffer? = pending[id]?.offer

    fun stage(id: String): StateFlow<IncomingStage> =
        pending[id]?.stage ?: MutableStateFlow(IncomingStage.Awaiting)

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun isValidToken(transferId: String, token: String?): Boolean =
        token != null && tokens[transferId] == token

    suspend fun handleOffer(offer: TransferOffer): OfferResponse {
        if (!IdentityStore.isSelfConsistent(offer.sender)) {
            return OfferResponse(false, "bad sender identity")
        }
        if (offer.expiresAt < System.currentTimeMillis()) {
            return OfferResponse(false, "offer expired")
        }
        if (!IdentityStore.verify(offer.sender.publicKeyB64, offer.signableBytes(), offer.signatureB64)) {
            return OfferResponse(false, "bad signature")
        }
        if (pending.containsKey(offer.transferId) || g.receiver.session(offer.transferId) != null) {
            return OfferResponse(false, "busy")
        }

        val item = Pending(
            offer = offer,
            decision = CompletableDeferred(),
            stage = MutableStateFlow(IncomingStage.Awaiting),
        )
        pending[offer.transferId] = item

        val autoAccept = g.settings.autoAcceptTrusted.value &&
            g.peers.isTrusted(offer.sender, offer.payload.kind)

        if (autoAccept) {
            g.notifier.autoAcceptStarted(offer)
            return acceptInternal(item, silent = true)
        }

        g.presenter.present(offer)

        val accepted = withTimeoutOrNull(120_000) { item.decision.await() } ?: false
        if (!accepted) {
            pending.remove(offer.transferId)
            tokens.remove(offer.transferId)
            g.receiver.abort(offer.transferId)
            g.presenter.dismiss(offer.transferId)
            g.history.add(
                HistoryEntry(
                    id = offer.transferId,
                    direction = "in",
                    peerName = offer.sender.name,
                    summary = offer.payload.summary(),
                    bytes = offer.payload.filesOrEmpty.sumOf { it.size },
                    files = offer.payload.filesOrEmpty.size,
                    timestamp = System.currentTimeMillis(),
                    status = "declined",
                )
            )
            return OfferResponse(false, "declined")
        }

        return acceptInternal(item, silent = false)
    }

    fun accept(transferId: String, always: Boolean) {
        val item = pending[transferId] ?: return
        if (always) {
            g.peers.trust(item.offer.sender, autoText = true, autoFiles = true)
        }
        item.stage.value = IncomingStage.Connecting
        g.presenter.onStage(transferId, IncomingStage.Connecting)
        item.decision.complete(true)
    }

    fun decline(transferId: String) {
        val item = pending[transferId] ?: return
        item.stage.value = IncomingStage.Failed("Отклонено")
        g.presenter.onStage(transferId, IncomingStage.Failed("Отклонено"))
        item.decision.complete(false)
    }

    private fun acceptInternal(item: Pending, silent: Boolean): OfferResponse {
        val transferId = item.offer.transferId
        val token = UUID.randomUUID().toString()
        tokens[transferId] = token

        val session = g.receiver.open(item.offer, token)
        item.stage.value = IncomingStage.Connecting
        g.presenter.onStage(transferId, IncomingStage.Connecting)

        session.onProgress = { done ->
            val stage = IncomingStage.Transferring(done, session.totalBytes)
            item.stage.value = stage
            g.presenter.onStage(transferId, stage)
            if (silent) g.notifier.progress(item.offer, done, session.totalBytes)
        }

        return OfferResponse(accepted = true, token = token, transferId = transferId)
    }

    fun onComplete(transferId: String, result: TransferResult) {
        val item = pending[transferId]
        val offer = item?.offer

        val stage = if (result.ok) IncomingStage.Done else IncomingStage.Failed(result.reason)
        item?.stage?.value = stage
        g.presenter.onStage(transferId, stage)
        g.notifier.clearProgress(transferId)

        if (offer != null) {
            offer.payload.textOrNull?.let { text ->
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        val cm = g.app.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("OpenBridge", text))
                    }
                }
                if (result.ok) {
                    g.pluginEngine.onTextReceived(text, offer.sender.name)
                }
            }

            g.history.add(
                HistoryEntry(
                    id = transferId,
                    direction = "in",
                    peerName = offer.sender.name,
                    summary = offer.payload.summary(),
                    bytes = offer.payload.filesOrEmpty.sumOf { it.size },
                    files = offer.payload.filesOrEmpty.size,
                    timestamp = System.currentTimeMillis(),
                    status = if (result.ok) "ok" else "failed",
                )
            )
        }

        tokens.remove(transferId)
        pending.remove(transferId)
    }

    fun onProgress(transferId: String) {
        val item = pending[transferId] ?: return
        val stage = IncomingStage.Transferring(
            doneBytes = g.receiver.progressBytes(transferId),
            totalBytes = item.offer.payload.filesOrEmpty.sumOf { it.size },
        )
        item.stage.value = stage
    }

    fun dismiss(transferId: String) {
        pending.remove(transferId)
        tokens.remove(transferId)
    }
}
