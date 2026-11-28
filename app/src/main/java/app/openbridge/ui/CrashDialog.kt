package app.openbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.openbridge.core.PendingCrash

@Composable
fun CrashDialog(
    crash: PendingCrash,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        shape = FlatShape,
        containerColor = FlatSurface,
        onDismissRequest = onDismiss,
        icon = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
        title = {
            Text(
                text = "В работе приложения произошел сбой",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column {
                Text(
                    text = "OpenBridge закрылся ${crash.timeLabel}. " +
                        "Отчёт с техническими деталями поможет всё починить.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = crash.shortError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSend) { Text("Отправить логи") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}
