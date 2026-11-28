package app.openbridge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.openbridge.discovery.NearbyProgress

@Composable
fun NearbyProgressDialog(
    progress: NearbyProgress,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        shape = FlatShape,
        containerColor = FlatSurface,
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
