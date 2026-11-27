package app.openbridge.util

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import app.openbridge.core.Diag

/**
 * NFC Forum Type 4 Tag emulation (NDEF).
 *
 * The tag holds a single well-known URI record pointing at
 * https://github.com/TheLuFik1112/OpenBridge#ob=…, so a phone without
 * OpenBridge opens the page in a browser and a phone with OpenBridge starts
 * the transfer.
 */
class OpenBridgeApduService : HostApduService() {
    private var selectedFile: SelectedFile = SelectedFile.NONE

    private var ndefServed = false

    override fun onCreate() {
        super.onCreate()
        NfcHelper.prime(applicationContext)
    }

    private enum class SelectedFile { NONE, CC, NDEF }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return STATUS_WRONG_LENGTH
        if (!NfcHelper.isTagActive()) return STATUS_FILE_NOT_FOUND

        val ins = commandApdu[1]
        val p1 = commandApdu[2]
        val p2 = commandApdu[3]

        return when {
            ins == INS_SELECT -> select(commandApdu, p1, p2)
            ins == INS_READ_BINARY -> readBinary(commandApdu, p1, p2)
            else -> {
                Diag.log("nfc_hce", "INS ${ins.toInt() and 0xFF} не поддержан")
                STATUS_INS_NOT_SUPPORTED
            }
        }
    }

    private fun select(commandApdu: ByteArray, p1: Byte, p2: Byte): ByteArray {
        // SELECT by DF name: the NDEF application AID D2760000850101.
        if (p1 == 0x04.toByte()) {
            selectedFile = SelectedFile.NONE
            Diag.log("nfc_hce", "SELECT Type 4 Tag AID")
            return STATUS_SUCCESS
        }

        // SELECT by file identifier: 00 A4 00 0C 02 <FID>.
        if (commandApdu.size >= 7) {
            val fid = ((commandApdu[5].toInt() and 0xFF) shl 8) or
                (commandApdu[6].toInt() and 0xFF)
            selectedFile = when (fid) {
                FID_CC -> SelectedFile.CC
                FID_NDEF -> SelectedFile.NDEF
                else -> {
                    Diag.log("nfc_hce", "SELECT неизвестного файла ${fid.toString(16)}")
                    return STATUS_FILE_NOT_FOUND
                }
            }
            return STATUS_SUCCESS
        }

        return STATUS_WRONG_LENGTH
    }

    private fun readBinary(commandApdu: ByteArray, p1: Byte, p2: Byte): ByteArray {
        val offset = ((p1.toInt() and 0xFF) shl 8) or (p2.toInt() and 0xFF)
        val fileData = when (selectedFile) {
            SelectedFile.CC -> CC_FILE
            SelectedFile.NDEF -> NfcHelper.buildNdefFile()
            SelectedFile.NONE -> return STATUS_FILE_NOT_FOUND
        }

        if (offset > fileData.size) return STATUS_WRONG_OFFSET
        if (offset == fileData.size) return STATUS_SUCCESS

        val rawLe = if (commandApdu.size >= 5) commandApdu[4].toInt() and 0xFF else 0
        val le = if (rawLe == 0) 256 else rawLe
        val readLen = minOf(le, fileData.size - offset)

        val result = ByteArray(readLen + 2)
        System.arraycopy(fileData, offset, result, 0, readLen)
        result[result.size - 2] = 0x90.toByte()
        result[result.size - 1] = 0x00

        if (selectedFile == SelectedFile.NDEF && !ndefServed &&
            offset + readLen >= fileData.size && NfcHelper.isTagActive()
        ) {
            ndefServed = true
            NfcHelper.notifyServed()
        }
        return result
    }

    override fun onDeactivated(reason: Int) {
        selectedFile = SelectedFile.NONE
        ndefServed = false
        Diag.log("nfc_hce", "Deactivated: $reason")
    }

    companion object {
        private val INS_SELECT = 0xA4.toByte()
        private val INS_READ_BINARY = 0xB0.toByte()

        private const val FID_CC = 0xE103
        private const val FID_NDEF = 0xE104

        /** Largest NDEF file the CC advertises, NLEN header included. */
        private const val NDEF_MAX_FILE = 0x0FFE

        val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00)
        val STATUS_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        val STATUS_WRONG_OFFSET = byteArrayOf(0x6B.toByte(), 0x00)
        val STATUS_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        val STATUS_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)

        /**
         * Capability Container, exactly 15 bytes as required by the Type 4 Tag
         * spec: CCLEN, mapping version 2.0, MLe, MLc, then one NDEF File
         * Control TLV (T=04, L=06, FID=E104, max size, free read/write).
         */
        val CC_FILE = byteArrayOf(
            0x00, 0x0F,                                  // CCLEN = 15
            0x20,                                        // Mapping version 2.0
            0x00, 0xFF.toByte(),                         // MLe = 255
            0x00, 0xFF.toByte(),                         // MLc = 255
            0x04, 0x06,                                  // NDEF File Control TLV
            0xE1.toByte(), 0x04,                         // File ID = E104
            (NDEF_MAX_FILE ushr 8).toByte(),             // Max NDEF file size, hi
            (NDEF_MAX_FILE and 0xFF).toByte(),           // Max NDEF file size, lo
            0x00,                                        // Read access: free
            0x00,                                        // Write access: free
        )
    }
}
