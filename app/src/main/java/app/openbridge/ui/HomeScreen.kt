package app.openbridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbridge.core.QueueItem
import app.openbridge.core.QueueKind
import app.openbridge.core.formatBytes
import app.openbridge.data.DiscoveredPeer

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
    val nfcSheetOpen by viewModel.nfcSheetOpen.collectAsState()

    var nfcExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(nfcSheetOpen) {
        if (nfcSheetOpen) nfcExpanded = true
    }

    val sortedPeers = remember(peers) {
        peers.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.info.name })
    }

    // Свайп ВНИЗ в начале списка открывает NFC-панель. NestedScrollConnection
    // видит ровно тот остаток прокрутки, который LazyColumn уже не смог съесть,
    // поэтому обычный скролл не ломает управление.
    val accumulatedPullPx = remember { mutableStateOf(0f) }
    val lastNfcOpenAt = remember { mutableStateOf(0L) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
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
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(nestedScrollConnection),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (nfcExpanded) {
                item(key = "nfc-panel", contentType = "card") {
                    AnimatedVisibility(
                        visible = nfcExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut(),
                    ) {
                        NfcPanel(
                            state = viewModel.nfcState.value,
                            strings = strings,
                            onCollapse = {
                                nfcExpanded = false
                                viewModel.closeNfcSheet()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item(key = "status", contentType = "card") {
                StatusCard(
                    strings = strings,
                    deviceName = deviceName.ifBlank { "OpenBridge" },
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
                SectionHeader("02", strings.devicesNearby)
            }

            if (peers.isEmpty()) {
                item(key = "devices-empty", contentType = "card") {
                    AccentCallout(text = strings.noDevicesHint)
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlatSurface, RoundedCornerShape(0.dp))
            .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(FlatSurfaceElevated, RoundedCornerShape(0.dp))
                    .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (isEngineReady) {
                    Icon(
                        Icons.Rounded.Wifi,
                        contentDescription = null,
                        tint = FlatAccent,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = FlatAccent,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = deviceName.uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = FlatTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        !isEngineReady -> strings.statusPreparing
                        peerCount > 0 -> strings.statusPeersCount(peerCount)
                        else -> strings.statusWaitingPeers
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = FlatTextSecondary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = onShowQr,
                enabled = isEngineReady,
                shape = RoundedCornerShape(0.dp),
            ) {
                Icon(
                    Icons.Rounded.QrCode2,
                    contentDescription = "QR",
                    tint = FlatTextPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = strings.btnQr.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = FlatTextPrimary,
                )
            }
        }
        if (portalUrl != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                portalUrl,
                style = MaterialTheme.typography.labelSmall,
                color = FlatTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    FlatCard {
        SectionHeader("01", strings.sendPackageTitle)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction(
                icon = Icons.Rounded.Add,
                label = strings.btnFiles,
                onClick = onPickFiles,
            )
            QuickAction(
                icon = Icons.Rounded.Image,
                label = strings.btnPhotos,
                onClick = onPickPhotos,
            )
            QuickAction(
                icon = Icons.Rounded.Folder,
                label = strings.btnFolder,
                onClick = onPickFolder,
            )
            QuickAction(
                icon = Icons.Rounded.ContentCopy,
                label = strings.btnText,
                onClick = onAddText,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (queue.isEmpty()) {
            Text(
                text = strings.sendPackageEmpty,
                style = MaterialTheme.typography.bodySmall,
                color = FlatTextSecondary,
            )
        } else {
            val totalBytes = queue.sumOf { it.size }
            queue.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when (item.kind) {
                            QueueKind.TEXT -> Icons.Rounded.ContentCopy
                            QueueKind.FOLDER -> Icons.Rounded.Folder
                            QueueKind.FILE -> Icons.Rounded.Description
                        },
                        contentDescription = null,
                        tint = FlatAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FlatTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.size > 0) {
                            Text(
                                text = formatBytes(item.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = FlatTextSecondary,
                            )
                        }
                    }
                    IconButton(onClick = { onRemove(item.id) }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = strings.btnRemove,
                            tint = FlatTextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatBytes(queue.sumOf { it.size }),
                style = MaterialTheme.typography.labelMedium,
                color = FlatTextSecondary,
            )
            androidx.compose.material3.TextButton(onClick = onClear) {
                Text(text = strings.btnClear.uppercase(), style = MaterialTheme.typography.labelMedium, color = FlatAccent)
            }
        }
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .weight(1f)
            .height(56.dp)
            .background(FlatSurfaceElevated, RoundedCornerShape(0.dp))
            .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp))
            .clip(RoundedCornerShape(0.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = FlatTextPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = FlatTextPrimary,
            )
        }
    }
}

@Composable
private fun SendingCard(strings: AppStrings, state: SendState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlatSurface, RoundedCornerShape(0.dp))
            .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp))
            .padding(16.dp),
    ) {
        Column {
            Text(
                text = strings.transferringTo(state.peerName).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = FlatTextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = FlatAccent,
                trackColor = FlatSurfaceElevated,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                state.text,
                style = MaterialTheme.typography.labelSmall,
                color = FlatTextSecondary,
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlatSurface, RoundedCornerShape(0.dp))
            .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(FlatSurfaceElevated, RoundedCornerShape(0.dp))
                    .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = peer.info.name.trim().take(1).uppercase().ifEmpty { "?" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FlatAccent,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        peer.info.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = FlatTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (trusted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = FlatAccent,
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
                    color = FlatTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .border(
                        androidx.compose.foundation.BorderStroke(1.dp, FlatBorder),
                        RoundedCornerShape(0.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Send, contentDescription = null, tint = FlatAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = strings.btnSend.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (enabled) FlatAccent else FlatTextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun TextDialog(strings: AppStrings, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.textDialogTitle) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(strings.textDialogPlaceholder) },
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(strings.btnAdd)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(strings.btnCancel) }
        },
    )
}
