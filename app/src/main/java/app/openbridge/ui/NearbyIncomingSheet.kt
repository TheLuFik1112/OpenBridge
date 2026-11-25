package app.openbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.openbridge.discovery.NearbyProgress
import app.openbridge.discovery.NearbyRequest

/**
 * Диалог входящего запроса Nearby — когда роутера рядом нет и устройства
 * соединяются напрямую (BLE + Wi-Fi Direct под капотом Google Nearby).
 * Flat-контейнер: 1.dp рамка, без скруглений и теней.
 */
@Composable
fun NearbyIncomingSheet(
    request: NearbyRequest,
    onAccept: () -> Unit,
    onTrust: () -> Unit,
    onDecline: () -> Unit,
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDecline) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(0.dp))
                .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(FlatSurfaceElevated, RoundedCornerShape(0.dp))
                        .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Devices,
                        contentDescription = null,
                        tint = FlatAccent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Входящая передача",
                        style = MaterialTheme.typography.labelMedium,
                        color = FlatTextSecondary,
                    )
                    Text(
                        request.name.ifBlank { "Устройство рядом" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = FlatTextPrimary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                strings.nearbyIncomingDesc,
                style = MaterialTheme.typography.bodyMedium,
                color = FlatTextSecondary,
            )

            Spacer(Modifier.height(20.dp))

            FlatSecondaryButton(
                text = strings.btnTrustAlways,
                onClick = onTrust,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FlatSecondaryButton(
                    text = strings.btnDecline,
                    onClick = onDecline,
                    modifier = Modifier.weight(1f),
                )
                FlatPrimaryButton(
                    text = strings.btnAccept,
                    onClick = onAccept,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Тихий прогресс приёма/отправки по Nearby, чтобы не терять обратную связь. */
@Composable
fun NearbyProgressDialog(
    progress: NearbyProgress,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Скрыть") }
        },
        title = { Text(progress.peerName.ifBlank { "Nearby" }) },
        text = {
            Column {
                Text(progress.text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
