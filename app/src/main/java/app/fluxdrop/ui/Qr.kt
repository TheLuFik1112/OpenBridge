package app.fluxdrop.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrDialog(payload: String, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    val bitmap = remember(payload) { generateQr(payload) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    strings.qrScanTitle,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                if (bitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR",
                            modifier = Modifier.padding(10.dp).size(260.dp),
                        )
                    }
                } else {
                    Text(
                        strings.qrError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    payload,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss) { Text(strings.btnCancel) }
            }
        }
    }
}

fun generateQr(text: String, size: Int = 720): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1),
    )
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(
                x, y,
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
            )
        }
    }
    bitmap
}.getOrNull()
