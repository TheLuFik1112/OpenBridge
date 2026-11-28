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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbridge.core.FileMeta
import app.openbridge.core.IncomingStage
import app.openbridge.core.formatBytes
import app.openbridge.core.formatSpeed
import app.openbridge.service.IncomingPrompt

@Composable
fun IncomingSheetContent(
    prompt: IncomingPrompt,
    stage: IncomingStage,
    onAccept: (always: Boolean) -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    showHandle: Boolean = true,
) {
    var alwaysAccept by rememberSaveable(prompt.id) { mutableStateOf(false) }
    val busy = stage is IncomingStage.Connecting || stage is IncomingStage.Transferring
    val failed = stage is IncomingStage.Failed

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, FlatShape)
            .flatBorder()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 0.dp)
                .height(2.dp)
                .background(FlatAccent, FlatShape),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(FlatSurfaceElevated, FlatShape)
                    .flatBorder(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = prompt.peerName.trim().take(1).uppercase().ifEmpty { "?" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FlatAccent,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "ВХОДЯЩАЯ ПЕРЕДАЧА",
                    style = MaterialTheme.typography.labelSmall,
                    color = FlatTextSecondary,
                )
                Text(
                    text = prompt.peerName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FlatTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${prompt.platform.uppercase()} · ${prompt.transport}",
                    style = MaterialTheme.typography.labelSmall,
                    color = FlatTextMuted,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onDecline, enabled = !busy) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Отклонить",
                    tint = FlatTextSecondary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = prompt.headline,
            style = MaterialTheme.typography.bodyLarge,
            color = FlatTextPrimary,
        )
        Spacer(Modifier.height(12.dp))

        PreviewCard(prompt, stage)

        if (prompt.allowAlways) {
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = alwaysAccept,
                        enabled = !busy,
                        role = Role.Checkbox,
                        onValueChange = { alwaysAccept = it },
                    )
                    .padding(vertical = 8.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlatCheckbox(checked = alwaysAccept, enabled = !busy)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Всегда принимать от «${prompt.peerName}»",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlatTextPrimary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = FlatTextMuted,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Ключ устройства сохранится, следующие передачи — без подтверждения",
                            style = MaterialTheme.typography.labelSmall,
                            color = FlatTextMuted,
                            maxLines = 2,
                        )
                    }
                }
            }
        }

        Row(Modifier.padding(top = 14.dp, bottom = 16.dp)) {
            FlatSecondaryButton(
                text = "Отклонить",
                onClick = onDecline,
                enabled = !busy,
                modifier = Modifier.weight(1f).height(52.dp),
            )
            Spacer(Modifier.width(10.dp))
            FlatPrimaryButton(
                text = when (stage) {
                    IncomingStage.Awaiting -> "Принять"
                    IncomingStage.Done -> "Готово"
                    is IncomingStage.Failed -> "Закрыть"
                    else -> "Отмена"
                },
                onClick = { if (failed) onDecline() else onAccept(alwaysAccept) },
                enabled = !busy,
                modifier = Modifier.weight(1.45f).height(52.dp),
            )
        }
    }
}

@Composable
private fun PreviewCard(prompt: IncomingPrompt, stage: IncomingStage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlatSurfaceElevated, FlatShape)
            .flatBorder(),
    ) {
        Column(Modifier.padding(14.dp)) {
            val text = prompt.text
            if (prompt.files.isNotEmpty()) {
                FilesPreview(prompt.files, compact = text != null)
                if (text != null) Spacer(Modifier.height(10.dp))
            }
            if (text != null) {
                TextPreview(text)
            } else if (prompt.files.isEmpty()) {
                Text(
                    text = "Отправитель рядом. Подтвердите приём, чтобы начать.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlatTextSecondary,
                )
            }

            if (stage is IncomingStage.Transferring) {
                Spacer(Modifier.height(12.dp))
                val fraction = if (stage.totalBytes > 0) {
                    (stage.doneBytes.toFloat() / stage.totalBytes).coerceIn(0f, 1f)
                } else 0f
                FlatProgress(fraction = fraction)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "${formatBytes(stage.doneBytes)} / ${formatBytes(stage.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = FlatTextSecondary,
                    )
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = FlatAccent,
                    )
                }
                if (stage.speedBytesPerSec > 0L) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formatSpeed(stage.speedBytesPerSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = FlatTextMuted,
                    )
                }
            }

            if (stage is IncomingStage.Failed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stage.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = FlatError,
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
            tint = FlatAccent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = FlatTextPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "БУФЕР ОБМЕНА · ${text.length} СИМВ.",
                style = MaterialTheme.typography.labelSmall,
                color = FlatTextMuted,
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
            tint = FlatAccent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${files.size} ФАЙЛ(ОВ) · ${formatBytes(files.sumOf { it.size })}",
            style = MaterialTheme.typography.bodyMedium,
            color = FlatTextPrimary,
        )
    }
    if (!compact) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = files.take(3).joinToString(" · ") { it.name },
            style = MaterialTheme.typography.labelSmall,
            color = FlatTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FileThumb(file: FileMeta) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(FlatSurface, FlatShape)
            .flatBorder(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = iconFor(file),
            contentDescription = file.name,
            tint = FlatTextSecondary,
            modifier = Modifier.size(24.dp),
        )
    }
}

fun iconFor(file: FileMeta): ImageVector = when {
    file.mime.startsWith("image/") -> Icons.Rounded.Image
    file.mime.startsWith("video/") -> Icons.Rounded.Movie
    file.mime.startsWith("audio/") -> Icons.Rounded.MusicNote
    else -> Icons.Rounded.Description
}
