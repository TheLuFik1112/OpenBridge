package app.openbridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbridge.core.FileMeta
import app.openbridge.core.IncomingStage
import app.openbridge.core.Payload
import app.openbridge.core.TransferOffer
import app.openbridge.core.formatBytes
import app.openbridge.core.totalBytes

/**
 * Общий контент входящей шторки.
 * Хостится либо в ModalBottomSheet (прозрачная Activity), либо в overlay-окне.
 */
@Composable
fun IncomingSheetContent(
    offer: TransferOffer,
    stage: IncomingStage,
    onAccept: (always: Boolean) -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    showHandle: Boolean = true,
) {
    var alwaysAccept by rememberSaveable(offer.transferId) { mutableStateOf(false) }
    val busy = stage is IncomingStage.Connecting || stage is IncomingStage.Transferring
    val failed = stage is IncomingStage.Failed

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        if (showHandle) {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 2.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    .align(Alignment.CenterHorizontally),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = offer.sender.name.trim().take(1).uppercase().ifEmpty { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = offer.sender.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${offer.sender.platform.uppercase()} • рядом • ${offer.sender.id.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onDecline, enabled = !busy) {
                Icon(Icons.Rounded.Close, contentDescription = "Отклонить")
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = when (val payload = offer.payload) {
                is Payload.Text -> "Хочет отправить текст из буфера"
                is Payload.Files -> "Хочет отправить файлы (${payload.files.size})"
                is Payload.Mixed -> "Хочет отправить файлы и текст"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))

        PreviewCard(offer.payload, stage)

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .toggleable(
                    value = alwaysAccept,
                    enabled = !busy,
                    role = Role.Checkbox,
                    onValueChange = { alwaysAccept = it },
                )
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = alwaysAccept, onCheckedChange = null, enabled = !busy)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Всегда принимать от «${offer.sender.name}»",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Ключ устройства сохранится, следующие передачи — без подтверждения",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }

        Row(Modifier.padding(top = 14.dp, bottom = 16.dp)) {
            OutlinedButton(
                onClick = onDecline,
                enabled = !busy,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Отклонить", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { if (failed) onDecline() else onAccept(alwaysAccept) },
                enabled = !busy,
                modifier = Modifier.weight(1.45f).height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                AnimatedContent(targetState = stage, label = "accept") { current ->
                    when (current) {
                        IncomingStage.Awaiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Принять", style = MaterialTheme.typography.titleSmall)
                        }
                        IncomingStage.Done -> Text("Готово", style = MaterialTheme.typography.titleSmall)
                        is IncomingStage.Failed -> Text("Закрыть", style = MaterialTheme.typography.titleSmall)
                        else -> Text("Отмена", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(payload: Payload, stage: IncomingStage) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            when (payload) {
                is Payload.Text -> TextPreview(payload.text)
                is Payload.Files -> FilesPreview(payload.files)
                is Payload.Mixed -> {
                    FilesPreview(payload.files, compact = true)
                    Spacer(Modifier.height(10.dp))
                    TextPreview(payload.text)
                }
            }

            if (stage is IncomingStage.Transferring) {
                Spacer(Modifier.height(12.dp))
                val fraction = if (stage.totalBytes > 0) {
                    (stage.doneBytes.toFloat() / stage.totalBytes).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${formatBytes(stage.doneBytes)} из ${formatBytes(stage.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (stage is IncomingStage.Failed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stage.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TextPreview(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Буфер обмена • ${text.length} симв.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilesPreview(files: List<FileMeta>, compact: Boolean = false) {
    if (!compact) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files.take(8)) { file -> FileThumb(file) }
        }
        Spacer(Modifier.height(10.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${files.size} файл(ов) • ${formatBytes(files.sumOf { it.size })}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (!compact) {
        Spacer(Modifier.height(4.dp))
        Text(
            files.take(3).joinToString(" · ") { it.name },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FileThumb(file: FileMeta) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = iconFor(file),
                contentDescription = file.name,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

fun iconFor(file: FileMeta): ImageVector = when {
    file.mime.startsWith("image/") -> Icons.Rounded.Image
    file.mime.startsWith("video/") -> Icons.Rounded.Movie
    file.mime.startsWith("audio/") -> Icons.Rounded.MusicNote
    else -> Icons.Rounded.Description
}

@Suppress("unused")
private fun payloadBytes(payload: Payload): Long = payload.totalBytes
