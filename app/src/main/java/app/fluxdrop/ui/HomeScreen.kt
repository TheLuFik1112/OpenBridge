package app.fluxdrop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fluxdrop.core.QueueItem
import app.fluxdrop.core.QueueKind
import app.fluxdrop.core.formatBytes
import app.fluxdrop.data.DiscoveredPeer
import app.fluxdrop.util.NfcHelper

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    queue: List<QueueItem>,
    peers: List<DiscoveredPeer>,
    sending: SendState?,
    isEngineReady: Boolean,
    onPickFiles: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickFolder: () -> Unit,
    onShowQr: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var showTextDialog by remember { mutableStateOf(false) }
    var showNfcTopPanel by remember { mutableStateOf(false) }
    val portalUrl by viewModel.portalUrl.collectAsState()
    val trustedIds by viewModel.trustedIds.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()

    val sortedPeers = remember(peers) {
        peers.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.info.name })
    }

    LaunchedEffect(portalUrl) {
        NfcHelper.updatePayload(portalUrl)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount > 25f) {
                        showNfcTopPanel = true
                    }
                }
            }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showNfcTopPanel) {
                item {
                    NfcTopCard(
                        strings = strings,
                        onDismiss = { showNfcTopPanel = false },
                    )
                }
            }

            item {
                StatusCard(
                    strings = strings,
                    deviceName = deviceName.ifBlank { "FluxDrop" },
                    peerCount = peers.size,
                    portalUrl = portalUrl,
                    isEngineReady = isEngineReady,
                    onShowQr = { viewModel.ensurePortalUrl(onShowQr) },
                )
            }

            item {
                QueueCard(
                    strings = strings,
                    queue = queue,
                    onPickFiles = onPickFiles,
                    onPickPhotos = onPickPhotos,
                    onPickFolder = onPickFolder,
                    onAddText = { showTextDialog = true },
                    onRemove = viewModel::removeItem,
                    onClear = viewModel::clearQueue,
                )
            }

            if (sending != null) {
                item { SendingCard(strings, sending) }
            }

            item {
                Text(
                    strings.devicesNearby,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (peers.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(strings.noDevicesYet, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                strings.noDevicesHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(sortedPeers, key = { it.info.id }) { peer ->
                PeerRow(
                    strings = strings,
                    peer = peer,
                    trusted = peer.info.id in trustedIds,
                    enabled = queue.isNotEmpty() && sending == null,
                    onSend = { viewModel.sendTo(peer) },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showTextDialog) {
        TextDialog(
            strings = strings,
            onDismiss = { showTextDialog = false },
            onConfirm = { text ->
                viewModel.addText(text)
                showTextDialog = false
            },
        )
    }
}

@Composable
private fun NfcTopCard(
    strings: AppStrings,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Nfc,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        strings.nfcTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = strings.btnCancel,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            val transition = rememberInfiniteTransition(label = "nfc_top_waves")
            val wave by transition.animateFloat(
                initialValue = 0.82f,
                targetValue = 1.25f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "top_wave",
            )

            Box(
                modifier = Modifier.size(90.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(wave)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                )
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Nfc,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                strings.nfcSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StatusCard(
    strings: AppStrings,
    deviceName: String,
    peerCount: Int,
    portalUrl: String?,
    isEngineReady: Boolean,
    onShowQr: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isEngineReady) {
                        Icon(
                            Icons.Rounded.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        deviceName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        when {
                            !isEngineReady -> strings.statusPreparing
                            peerCount > 0 -> strings.statusPeersCount(peerCount)
                            else -> strings.statusWaitingPeers
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
                FilledTonalButton(
                    onClick = onShowQr,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Rounded.QrCode2,
                        contentDescription = "QR",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(strings.btnQr, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (portalUrl != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    portalUrl,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun QueueCard(
    strings: AppStrings,
    queue: List<QueueItem>,
    onPickFiles: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickFolder: () -> Unit,
    onAddText: () -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.sendPackageTitle,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                val totalBytes = remember(queue) { queue.sumOf { it.size } }
                if (queue.isNotEmpty()) {
                    Text(
                        formatBytes(totalBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = onClear) {
                        Icon(Icons.Rounded.Delete, contentDescription = strings.btnClear)
                    }
                }
            }

            if (queue.isEmpty()) {
                Text(
                    strings.sendPackageEmpty,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                queue.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (item.kind) {
                                QueueKind.TEXT -> Icons.Rounded.ContentCopy
                                QueueKind.FOLDER -> Icons.Rounded.Folder
                                QueueKind.FILE -> Icons.Rounded.Description
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.size > 0) {
                                Text(
                                    formatBytes(item.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onRemove(item.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = strings.btnRemove,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalButton(
                    onClick = onPickFiles,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(strings.btnFiles, style = MaterialTheme.typography.labelSmall)
                    }
                }
                FilledTonalButton(
                    onClick = onPickPhotos,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(strings.btnPhotos, style = MaterialTheme.typography.labelSmall)
                    }
                }
                FilledTonalButton(
                    onClick = onPickFolder,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(strings.btnFolder, style = MaterialTheme.typography.labelSmall)
                    }
                }
                FilledTonalButton(
                    onClick = onAddText,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(strings.btnText, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SendingCard(strings: AppStrings, state: SendState) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                strings.transferringTo(state.peerName),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                state.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun PeerRow(
    strings: AppStrings,
    peer: DiscoveredPeer,
    trusted: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    peer.info.name.trim().take(1).uppercase().ifEmpty { "?" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        peer.info.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (trusted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    when {
                        peer.ip.isNotBlank() -> "${peer.ip} • ${peer.source.uppercase()}"
                        peer.source == "nearby" -> strings.peerNearbyDirect
                        else -> strings.peerSearching
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.btnSend)
            }
        }
    }
}

@Composable
private fun TextDialog(strings: AppStrings, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.textDialogTitle) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(strings.textDialogPlaceholder) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(strings.btnAdd)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.btnCancel) }
        },
    )
}
