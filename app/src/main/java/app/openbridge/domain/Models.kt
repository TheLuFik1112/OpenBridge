package app.openbridge.domain

/**
 * Состояние отправки: кому, насколько далеко и что происходит сейчас.
 * Рисуется карточкой в списке, поэтому это просто данные.
 */
data class SendState(
    val peerName: String,
    val fraction: Float,
    val text: String,
)

/** Этап NFC-передачи. Режимов нет — только ход. */
enum class NfcPhase { READY, CONNECTING, RECEIVING, DONE, TRANSFER, SENT, ERROR }

/** Что показывает верхняя NFC-панель. */
data class NfcState(
    val phase: NfcPhase = NfcPhase.READY,
    val fraction: Float = 0f,
    val files: Int = 0,
    val texts: Int = 0,
    val hasItems: Boolean = false,
    /** Имя отправителя, с которым соединяемся (для строки «Соединяемся с …»). */
    val peerName: String = "",
    /** Панель про нашу отправку, а не про приём: от этого зависит текст ошибки. */
    val sending: Boolean = false,
)
