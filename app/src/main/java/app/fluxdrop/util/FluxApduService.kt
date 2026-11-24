package app.fluxdrop.util

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import app.fluxdrop.core.Diag

/**
 * Эмуляция стандартного NFC Forum Type 4 Tag (AID D2760000850101).
 * Позволяет любому современному Android-устройству (и iPhone) считать NDEF-сообщение
 * при простом касании, без необходимости запускать специальные режимы считывания.
 *
 * NDEF содержит два адреса: `fluxdrop://` с именем отправителя и страницу
 * скачивания. Телефон с FluxDrop открывает приложение и забирает пакет по Nearby,
 * телефон без приложения уходит в браузер на страницу скачивания.
 *
 * Работает всегда, когда включён NFC: reader mode не используется (см. [NfcHelper]).
 */
class FluxApduService : HostApduService() {

    private var selectedFile: SelectedFile = SelectedFile.NONE

    override fun onCreate() {
        super.onCreate()
        // Метку могут прочитать, когда приложение вообще не запускали:
        // заранее подставляем имя устройства (и в фоне — id сертификата).
        NfcHelper.prime(applicationContext)
    }

    /** Второй телефон уже дочитал NDEF до конца — сообщаем один раз за касание. */
    private var ndefServed = false

    private enum class SelectedFile { NONE, CC, NDEF }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return STATUS_FAILED

        val ins = commandApdu[1]
        val p1 = commandApdu[2]
        val p2 = commandApdu[3]

        // 1. SELECT по AID (00 A4 04 00 07 D2760000850101)
        if (ins == INS_SELECT && p1 == 0x04.toByte()) {
            selectedFile = SelectedFile.NONE
            Diag.log("nfc_hce", "SELECT Type 4 Tag AID")
            return STATUS_SUCCESS
        }

        // 2. SELECT файла по ID (00 A4 00 0C 02 <FID>)
        if (ins == INS_SELECT) {
            if (commandApdu.size >= 7) {
                val fid = ((commandApdu[5].toInt() and 0xFF) shl 8) or
                    (commandApdu[6].toInt() and 0xFF)
                selectedFile = when (fid) {
                    0xE103 -> SelectedFile.CC
                    0xE104 -> SelectedFile.NDEF
                    else -> return STATUS_FILE_NOT_FOUND
                }
                return STATUS_SUCCESS
            }
            return STATUS_FILE_NOT_FOUND
        }

        // 3. READ BINARY (00 B0 <offset_hi> <offset_lo> <Le>)
        if (ins == INS_READ_BINARY) {
            val offset = ((p1.toInt() and 0xFF) shl 8) or (p2.toInt() and 0xFF)
            val fileData = when (selectedFile) {
                SelectedFile.CC -> CC_FILE
                SelectedFile.NDEF -> NfcHelper.buildNdefFile()
                SelectedFile.NONE -> return STATUS_FAILED
            }

            if (offset > fileData.size) return STATUS_WRONG_OFFSET
            if (offset == fileData.size) {
                return byteArrayOf(0x90.toByte(), 0x00)
            }

            val rawLe = if (commandApdu.size >= 5) commandApdu[4].toInt() and 0xFF else 0
            val le = if (rawLe == 0) 256 else rawLe
            val readLen = minOf(le, fileData.size - offset)

            val result = ByteArray(readLen + 2)
            System.arraycopy(fileData, offset, result, 0, readLen)
            result[result.size - 2] = 0x90.toByte()
            result[result.size - 1] = 0x00

            if (selectedFile == SelectedFile.NDEF && !ndefServed &&
                offset + readLen >= fileData.size
            ) {
                ndefServed = true
                NfcHelper.notifyServed()
            }
            return result
        }

        return STATUS_INS_NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        selectedFile = SelectedFile.NONE
        ndefServed = false
        Diag.log("nfc_hce", "Deactivated: $reason")
    }

    companion object {
        private val INS_SELECT = 0xA4.toByte()
        private val INS_READ_BINARY = 0xB0.toByte()

        val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00)
        val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00)
        val STATUS_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        val STATUS_WRONG_OFFSET = byteArrayOf(0x6B.toByte(), 0x00)
        val STATUS_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)

        // Стандартный Capability Container (CC) для Type 4 Tag v2.0
        val CC_FILE = byteArrayOf(
            0x00, 0x0F,             // CCLEN: 15 байт
            0x20,                   // Mapping Version 2.0
            0x00, 0xFF.toByte(),    // MLe (максимальный размер R-APDU)
            0x00, 0xFF.toByte(),    // MLc (максимальный размер C-APDU)
            0x04, 0x06,             // NDEF File Control TLV (T=04, L=06)
            0xE1.toByte(), 0x04,    // File ID: E104
            0x0F, 0xFE.toByte(),    // Максимальный размер NDEF: 4094 байта
            0x00,                   // Доступ на чтение: свободный
            0x00,                   // Доступ на запись: свободный
        )
    }
}
