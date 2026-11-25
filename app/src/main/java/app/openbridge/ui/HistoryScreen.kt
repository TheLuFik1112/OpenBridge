package app.openbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openbridge.core.HistoryEntry
import app.openbridge.core.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(viewModel: AppViewModel) {
    val strings = LocalStrings.current
    val entries by viewModel.history.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(FlatSurfaceElevated, RoundedCornerShape(0.dp))
                            .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = FlatAccent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (strings is RuStrings) "История пуста" else "History is empty",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = FlatTextPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (strings is RuStrings) "Здесь появятся отправленные и принятые передачи"
                        else "Sent and received transfers will appear here",
                        style = MaterialTheme.typography.bodySmall,
                        color = FlatTextSecondary,
                    )
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.id + it.timestamp }) { entry ->
                HistoryRow(entry)
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    val strings = LocalStrings.current
    val incoming = entry.direction == "in"
    val ok = entry.status == "ok"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(0.dp))
            .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp))
            .padding(14.dp),
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (incoming) Icons.Rounded.CallReceived else Icons.Rounded.CallMade,
                    contentDescription = null,
                    tint = FlatAccent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.peerName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = FlatTextPrimary,
                        maxLines = 1,
                    )
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlatTextSecondary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        timeLabel(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = FlatTextSecondary,
                    )
                    Text(
                        text = if (!ok) {
                            if (strings is RuStrings) "не удалось" else "failed"
                        } else if (entry.bytes > 0) {
                            formatBytes(entry.bytes)
                        } else {
                            if (strings is RuStrings) "готово" else "done"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (ok) FlatAccent else FlatError,
                    )
                }
            }
            Text(
                text = if (ok) "[OK]" else "[ERR]",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (ok) FlatAccent else FlatError,
            )
        }
    }
}

private fun timeLabel(timestamp: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
