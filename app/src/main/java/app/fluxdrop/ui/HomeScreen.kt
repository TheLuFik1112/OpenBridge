package app.fluxdrop.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fluxdrop.core.QueueItem
import app.fluxdrop.core.QueueKind
import app.fluxdrop.core.formatBytes
import app.fluxdrop.data.DiscoveredPeer

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onPickFiles: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickFolder: () -> Unit,
    onShowQr: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var showTextDialog by remember { mutableStateOf(false) }
    val portalUrl by viewModel.portalUrl.collectAsState()
    val trustedIds by viewModel.trustedIds.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val isEngineReady by viewModel.isEngineReady.collectAsState()

    val sortedPeers = remember(peers) {
        peers.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.info.name })
    }

    // Свайп ВНИЗ в начале списка открывает NFC-окно. NestedScrollConnection
    // видит ровно тот остаток прокрутки, который LazyColumn уже не смог съесть,
    // поэтому обычный скролл срабатывание не ломает.
    val accumulatedPullPx = remember { mutableStateOf(0f) }
    val lastNfcOpenAt = remember { mutableStateOf(0L) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // available.y > 0: список в самом верху, палец тянет вниз.
                if (available.y > 0f) {
                    accumulatedPullPx.value += available.y
                    val now = System.currentTimeMillis()
                    if (accumulatedPullPx.value >= 120f && now - lastNfcOpenAt.value > 900) {
                        accumulatedPullPx.value = 0f
                        lastNfcOpenAt.value = now
                        viewModel.openNfcSheet()
                    }
                } else if (available.y < 0f) {
                    accumulatedPullPx.value = 0f
                }
                return Offset.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status", contentType = "card") {
                StatusCard(
                    strings = strings,
                    deviceName = deviceName.ifBlank { "FluxDrop" },
                    peerCount = peers.size,
                    portalUrl = portalUrl,
                    isEngineReady = isEngineReady,
                    onShowQr = { viewModel.ensurePortalUrl(onShowQr) },
                )
            }

            item(key = "queue", contentType = "card") {
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

            sending?.let { state ->
                item(key = "sending", contentType = "card") { SendingCard(strings, state) }
            }

            item(key = "devices-title", contentType = "title") {
                Text(
                    strings.devicesNearby,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (peers.isEmpty()) {
                item(key = "devices-empty", contentType = "card") {
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

            items(sortedPeers, key = { it.info.id }, contentType = { "peer" }) { peer ->
                PeerRow(
                    strings = strings,
                    peer = peer,
                    trusted = peer.info.id in trustedIds,
                    enabled = queue.isNotEmpty() && sending == null,
                    onSend = { viewModel.sendTo(peer) },
                )
            }

            item(key = "bottom-spacer", contentType = "spacer") { Spacer(Modifier.height(16.dp)) }
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
