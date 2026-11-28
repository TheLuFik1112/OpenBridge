package app.openbridge.domain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import app.openbridge.core.AppGraph
import app.openbridge.core.Diag
import app.openbridge.net.NfcPortal
import app.openbridge.text.AppStrings
import app.openbridge.util.NfcHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class NfcSession(
    private val context: Context,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val queue: PackageQueue,
    private val strings: StateFlow<AppStrings>,
    private val messages: MutableStateFlow<String?>,
) {
    private val _open = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _open.asStateFlow()

    private val _state = MutableStateFlow(NfcState())
    val state: StateFlow<NfcState> = _state.asStateFlow()

    init {
        syncTag()
    }

    fun openSheet() {
        if (!NfcHelper.isNfcEnabled(context)) {
            messages.value = strings.value.nfcNotAvailable
            return
        }

        if (queue.items.value.isEmpty()) {
            messages.value = strings.value.nfcNeedItems
            return
        }
        if (_state.value.phase != NfcPhase.RECEIVING &&
            _state.value.phase != NfcPhase.CONNECTING
        ) {
            _state.value = NfcState(hasItems = true)
        }
        _open.value = true
        syncTag()
    }

    fun close() {
        _open.value = false
        syncTag()
    }

    private fun syncTag() {
        NfcHelper.setTagActive(context, _open.value && queue.items.value.isNotEmpty())
    }

    fun handleReceived(raw: String) {
        Diag.log("nfc", "incoming: $raw")
        val sender = NfcHelper.parseSender(raw)
        if (sender != null) {
            pullFromSender(sender)
            return
        }
        if (NfcPortal.isPortalUrl(raw)) {
            receiveFromPortal(raw)
            return
        }
        messages.value = strings.value.nfcNothingToReceive
    }

    fun onNearbyProgress(fraction: Float) {
        val current = _state.value
        when (current.phase) {
            NfcPhase.CONNECTING, NfcPhase.RECEIVING ->
                _state.value = current.copy(phase = NfcPhase.RECEIVING, fraction = fraction)

            NfcPhase.TRANSFER ->
                _state.value = current.copy(fraction = fraction)

            else -> Unit
        }
    }

    fun attach() {
        scope.launch(Dispatchers.IO) {
            queue.items.collect { syncTag() }
        }

        scope.launch(Dispatchers.IO) {
            var read = NfcHelper.served.value
            NfcHelper.served.collect { count ->
                if (count <= read) {
                    read = count
                    return@collect
                }
                read = count
                if (_state.value.phase != NfcPhase.RECEIVING) {
                    _state.value = NfcState(
                        phase = NfcPhase.TRANSFER,
                        hasItems = true,
                        sending = true,
                    )
                    _open.value = true
                }
                NfcHelper.vibrate(context)

                delay(NFC_HANDOFF_TIMEOUT_MS)
                if (_state.value.phase == NfcPhase.TRANSFER &&
                    graph.nearby.progress.value == null
                ) {
                    _state.value = NfcState(phase = NfcPhase.SENT, fraction = 1f)
                }
                delay(1_800)
                if (_state.value.phase == NfcPhase.SENT) close()
            }
        }

        scope.launch(Dispatchers.IO) {
            var sent = graph.nearby.delivered.value
            graph.nearby.delivered.collect { count ->
                if (count <= sent) {
                    sent = count
                    return@collect
                }
                sent = count
                _state.value = NfcState(phase = NfcPhase.SENT, fraction = 1f)
                _open.value = true
                NfcHelper.vibrate(context)
                delay(2_400)
                if (_state.value.phase == NfcPhase.SENT) close()
            }
        }

        scope.launch(Dispatchers.IO) {
            var failures = graph.nearby.failed.value
            graph.nearby.failed.collect { count ->
                if (count <= failures) {
                    failures = count
                    return@collect
                }
                failures = count
                _state.value = NfcState(phase = NfcPhase.ERROR, sending = true)
                _open.value = true
                NfcHelper.vibrate(context)
                delay(5_000)
                if (_state.value.phase == NfcPhase.ERROR) close()
            }
        }
    }

    private fun pullFromSender(sender: NfcHelper.Sender) {
        if (_state.value.phase == NfcPhase.CONNECTING ||
            _state.value.phase == NfcPhase.RECEIVING
        ) return
        if (!runCatching { graph.nearby.isAvailable() }.getOrDefault(false)) {
            _state.value = NfcState(NfcPhase.ERROR)
            _open.value = true
            messages.value = strings.value.nfcNearbyUnavailable
            return
        }

        _state.value = NfcState(
            phase = NfcPhase.CONNECTING,
            peerName = sender.name.ifBlank { "отправитель" },
        )
        _open.value = true
        NfcHelper.vibrate(context)

        scope.launch(Dispatchers.IO) {
            try {
                val summary = graph.nearby.pull(sender.deviceId, sender.name) {
                    _state.value = _state.value.copy(phase = NfcPhase.RECEIVING)
                }.getOrThrow()

                _state.value = NfcState(
                    phase = NfcPhase.DONE,
                    fraction = 1f,
                    files = summary.files,
                    texts = summary.texts,
                )
                messages.value = when {
                    summary.files > 0 -> strings.value.nfcReceivedCount(summary.files)
                    summary.texts > 0 -> strings.value.nfcTextCopied
                    else -> strings.value.nfcEmptyPackage
                }
                delay(NFC_RESULT_DISPLAY_MS)
                if (_state.value.phase == NfcPhase.DONE) close()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Diag.log("nfc", "pull failed: $t")
                _state.value = NfcState(NfcPhase.ERROR)
                messages.value = when (t.message) {
                    "empty" -> strings.value.nfcEmptyPackage
                    "no-peer" -> if (NfcHelper.isBluetoothReady(context)) {
                        strings.value.nfcNoNearbyPeerRetry
                    } else {
                        strings.value.nfcNoNearbyPeer
                    }
                    "blocked" -> strings.value.nfcNearbyUnavailable
                    else -> strings.value.nfcReceiveFailed
                }
            }
        }
    }

    private fun receiveFromPortal(raw: String) {
        if (_state.value.phase == NfcPhase.RECEIVING) return

        _state.value = NfcState(NfcPhase.RECEIVING)
        _open.value = true
        NfcHelper.vibrate(context)

        scope.launch(Dispatchers.IO) {
            try {
                val received = NfcPortal.receive(context, raw) { fraction ->
                    _state.value = _state.value.copy(
                        phase = NfcPhase.RECEIVING,
                        fraction = fraction,
                    )
                }
                if (received.texts.isNotEmpty()) {
                    copyToClipboard(received.texts.joinToString("\n\n"))
                }
                _state.value = NfcState(
                    phase = NfcPhase.DONE,
                    fraction = 1f,
                    files = received.files,
                    texts = received.texts.size,
                )
                messages.value = when {
                    received.files > 0 -> strings.value.nfcReceivedCount(received.files)
                    received.texts.isNotEmpty() -> strings.value.nfcTextCopied
                    else -> strings.value.nfcEmptyPackage
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Diag.log("nfc", "receive failed: $t")
                _state.value = NfcState(NfcPhase.ERROR)
                messages.value = if (t.message == "empty") {
                    strings.value.nfcEmptyPackage
                } else {
                    strings.value.nfcReceiveFailed
                }
                delay(NFC_RESULT_DISPLAY_MS)
                if (_state.value.phase == NfcPhase.ERROR) close()
            }
        }
    }

    private suspend fun copyToClipboard(text: String) {
        withContext(Dispatchers.Main) {
            runCatching {
                val manager = context.getSystemService(ClipboardManager::class.java)
                manager?.setPrimaryClip(ClipData.newPlainText("OpenBridge", text))
            }
        }
    }

    private companion object {
        const val NFC_HANDOFF_TIMEOUT_MS = 25_000L
        const val NFC_RESULT_DISPLAY_MS = 1_800L
    }
}
