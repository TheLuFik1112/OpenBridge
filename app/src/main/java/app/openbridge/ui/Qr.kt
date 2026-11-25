package app.openbridge.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed class QrState {
    object Loading : QrState()
    class Ready(val bitmap: Bitmap) : QrState()
    object Failed : QrState()
}

@Composable
fun QrDialog(payload: String, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    // QR 720×720 — это ~0.5 млн пикселей. Генерация в главном потоке подвешивает
    // кадр на десятки миллисекунд, поэтому считаем в Default-диспетчере.
    val state by produceState<QrState>(initialValue = QrState.Loading, key1 = payload) {
        value = withContext(Dispatchers.Default) {
            generateQr(payload)?.let { QrState.Ready(it) } ?: QrState.Failed
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(0.dp),
                )
                .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp))
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    strings.qrScanTitle,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                when (val current = state) {
                    is QrState.Ready -> Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                    ) {
                        Image(
                            bitmap = current.bitmap.asImageBitmap(),
                            contentDescription = "QR",
                            modifier = Modifier.padding(10.dp).size(260.dp),
                        )
                    }

                    QrState.Loading -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 40.dp),
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text(
                            strings.statusPreparing,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    QrState.Failed -> Text(
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
    val pixels = IntArray(size * size)
    var index = 0
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[index++] = if (matrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}.getOrNull()
