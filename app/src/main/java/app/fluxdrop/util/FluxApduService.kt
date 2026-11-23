package app.fluxdrop.util

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import app.fluxdrop.core.Diag
import java.util.Arrays

/**
 * Эмуляция стандартного NFC Forum Type 4 Tag (AID D2760000850101).
 * Позволяет любому современному Android-устройству (и iPhone) считать NDEF-сообщение
 * при простом касании, без необходимости запускать специальные режимы считывания.
 *
 * Если на втором телефоне есть FluxDrop — откроется приложение благодаря AAR (app.fluxdrop.p2p).
 * Если приложения нет — откроется браузер со ссылкой на скачивание.
 */
class FluxApduService : HostApduService() {

    private var selectedFile: SelectedFile = SelectedFile.NONE

    private enum class SelectedFile {
        NONE, CC, NDEF
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return STATUS_FAILED

        val cla = commandApdu[0]
        val ins = commandApdu[1]
        val p1 = commandApdu[2]
        val p2 = commandApdu[3]

        // 1. SELECT Application (AID D2760000850101)
        if (ins == 0xA4.toByte() && p1 == 0x04.toByte()) {
            selectedFile = SelectedFile.NONE
            Diag.log("nfc_hce", "SELECT Type 4 Tag AID")
            return STATUS_SUCCESS
        }

        // 2. SELECT File by ID (00 A4 00 0C 02 <FileID>)
        if (ins == 0xA4.toByte()) {
            if (commandApdu.size >= 7) {
                val fileId0 = commandApdu[5]
                val fileId1 = commandApdu[6]
                if (fileId0 == 0xE1.toByte() && fileId1 == 0x03.toByte()) {
                    selectedFile = SelectedFile.CC
                    return STATUS_SUCCESS
                } else if (fileId0 == 0xE1.toByte() && fileId1 == 0x04.toByte()) {
                    selectedFile = SelectedFile.NDEF
                    return STATUS_SUCCESS
                }
            }
            return STATUS_FILE_NOT_FOUND
        }

        // 3. READ BINARY (00 B0 <offset_high> <offset_low> <length>)
        if (ins == 0xB0.toByte()) {
            val offset = ((p1.toInt() and 0xFF) shl 8) or (p2.toInt() and 0xFF)
            val fileData = when (selectedFile) {
                SelectedFile.CC -> CC_FILE
                SelectedFile.NDEF -> NfcHelper.buildNdefFile()
                SelectedFile.NONE -> return STATUS_FAILED
            }

            if (offset >= fileData.size) {
                return STATUS_SUCCESS
            }

            val le = if (commandApdu.size >= 5) commandApdu[4].toInt() and 0xFF else 0
            val readLen = if (le == 0) fileData.size - offset else minOf(le, fileData.size - offset)
            val result = ByteArray(readLen + 2)
            System.arraycopy(fileData, offset, result, 0, readLen)
            result[result.size - 2] = 0x90.toByte()
            result[result.size - 1] = 0x00.toByte()
            return result
        }

        return STATUS_FAILED
    }

    override fun onDeactivated(reason: Int) {
        selectedFile = SelectedFile.NONE
        Diag.log("nfc_hce", "Deactivated: $reason")
    }

    companion object {
        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        private val STATUS_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        // Standard Capability Container (CC) file for Type 4 Tag v2.0
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F,             // CCLEN: 15 bytes
            0x20,                   // Mapping Version 2.0
            0x00, 0x7F.toByte(),    // MLe (Max R-APDU size)
            0x00, 0x7F.toByte(),    // MLc (Max C-APDU size)
            0x04, 0x06,             // NDEF File Control TLV (T=04, L=06)
            0xE1.toByte(), 0x04,    // File ID: E104
            0x0F, 0xFE.toByte(),    // Max NDEF size: 4094 bytes
            0x00,                   // Read access: Free read (00)
            0x00,                   // Write access: Free write (00)
        )
    }
}
