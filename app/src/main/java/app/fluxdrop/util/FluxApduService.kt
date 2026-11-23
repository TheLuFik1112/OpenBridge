package app.fluxdrop.util

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import app.fluxdrop.core.Diag
import java.nio.charset.StandardCharsets

class FluxApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return STATUS_FAILED

        if (isSelectAid(commandApdu)) {
            val payload = NfcHelper.currentPayload ?: "https://github.com/TheLuFik1112/FluxDrop/releases/latest"
            val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
            Diag.log("nfc", "HCE served payload: $payload")
            val result = ByteArray(payloadBytes.size + 2)
            System.arraycopy(payloadBytes, 0, result, 0, payloadBytes.size)
            result[result.size - 2] = 0x90.toByte()
            result[result.size - 1] = 0x00.toByte()
            return result
        }
        return STATUS_FAILED
    }

    override fun onDeactivated(reason: Int) {
        Diag.log("nfc", "HCE deactivated: $reason")
    }

    private fun isSelectAid(apdu: ByteArray): Boolean {
        if (apdu.size < 4) return false
        return apdu[0] == 0x00.toByte() &&
               apdu[1] == 0xA4.toByte() &&
               apdu[2] == 0x04.toByte() &&
               apdu[3] == 0x00.toByte()
    }

    companion object {
        val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        val SELECT_APDU = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(),
            0xF0.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte(),
            0x00.toByte(),
        )
    }
}
