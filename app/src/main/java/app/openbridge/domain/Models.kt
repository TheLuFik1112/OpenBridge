package app.openbridge.domain

data class SendState(
    val peerName: String,
    val fraction: Float,
    val text: String,
)

enum class NfcPhase { READY, CONNECTING, RECEIVING, DONE, TRANSFER, SENT, ERROR }

data class NfcState(
    val phase: NfcPhase = NfcPhase.READY,
    val fraction: Float = 0f,
    val files: Int = 0,
    val texts: Int = 0,
    val hasItems: Boolean = false,
    val peerName: String = "",
    val sending: Boolean = false,
)
