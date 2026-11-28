package app.openbridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbridge.core.LocalInterface
import app.openbridge.core.QueueItem
import app.openbridge.core.QueueKind
import app.openbridge.core.TransferDirection
import app.openbridge.core.TransferProgress
import app.openbridge.core.formatBytes
import app.openbridge.core.formatSpeed
import app.openbridge.data.DiscoveredPeer
import app.openbridge.domain.NfcPhase
import app.openbridge.domain.NfcState
import app.openbridge.domain.SendState
import app.openbridge.text.AppStrings
import app.openbridge.theme.BUTTON_OUTLINED

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
    val transfer by viewModel.transfer.collectAsState()
    val isEngineReady by viewModel.isEngineReady.collectAsState()
    val nfcSheetOpen by viewModel.nfcSheetOpen.collectAsState()
    val nfcState by viewModel.nfcState.collectAsState()
    val localInterfaces by viewModel.localInterfaces.collectAsState()
    val preferredInterface by viewModel.preferredInterface.collectAsState()
    val refreshing by viewModel.discoveryRefreshing.collectAsState()
    val scope = rememberCoroutineScope()

    var panelShown by remember { mutableStateOf(nfcSheetOpen) }
    var panelVisible by remember { mutableStateOf(nfcSheetOpen) }

    LaunchedEffect(nfcSheetOpen) {
        if (nfcSheetOpen) {
            panelShown = true
            panelVisible = true
        } else if (panelShown) {
            panelVisible = false
            kotlinx.coroutines.delay(220)
            if (!panelVisible) panelShown = false
        }
    }

    val sortedPeers = remember(peers) {
        peers.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.info.name })
    }

    val listState = rememberLazyListState()
    val thresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val nestedScrollConnection = remember(listState, thresholdPx, viewModel) {
        object : NestedScrollConnection {
            var accumulatedPullPx = 0f
            var lastNfcOpenAt = 0L

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val down = available.y > 0f
                if (!down || listState.canScrollBackward) {
                    accumulatedPullPx = 0f
                    return Offset.Zero
                }
                accumulatedPullPx += available.y
                val now = System.currentTimeMillis()
                if (accumulatedPullPx >= thresholdPx && now - lastNfcOpenAt > 700) {
                    accumulatedPullPx = 0f
                    lastNfcOpenAt = now
                    viewModel.openNfcSheet()
                }
                return Offset(0f, available.y)
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
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (panelShown) {
                item(key = "nfc-panel", contentType = "card") {
                    AnimatedVisibility(
                        visible = panelVisible,
                        enter = fadeIn(animationSpec = tween(180)) +
                            expandVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ),
                        exit = fadeOut(animationSpec = tween(140)) +
                            shrinkVertically(animationSpec = tween(200)),
                    ) {
                        NfcPanel(
                            state = nfcState,
                            strings = strings,
                            onCollapse = {
                                panelVisible = false
                                viewModel.closeNfcSheet()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                item(key = "nfc-hint", contentType = "title") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openNfcSheet() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "↓",
                            style = MaterialTheme.typography.labelMedium,
                            color = FlatAccent,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = labelCase(strings.nfcPullHint),
                            style = MaterialTheme.typography.labelSmall,
                            color = FlatTextMuted,
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

            if (transfer.isTransferring) {
                item(key = "transfer", contentType = "card") { TransferCard(strings, transfer) }
            } else {
                sending?.let { state ->
                    item(key = "sending", contentType = "card") { SendingCard(strings, state) }
                }
            }

            item(key = "devices-title", contentType = "title") {
                DevicesHeader(
                    strings = strings,
                    interfaces = localInterfaces,
                    selected = preferredInterface,
                    refreshing = refreshing,
                    onSelect = viewModel::selectInterface,
                    onRefreshNearby = viewModel::refreshNearby,
                )
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
private fun DevicesHeader(
    strings: AppStrings,
    interfaces: List<LocalInterface>,
    selected: String?,
    refreshing: Boolean,
    onSelect: (String?) -> Unit,
    onRefreshNearby: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val transition = rememberInfiniteTransition(label = "refresh-spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "refresh-angle",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(
            index = "02",
            title = strings.devicesNearby,
            modifier = Modifier.weight(1f),
        )

        Box {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(FlatShape)
                    .flatBorder()
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = strings.btnRefreshDevices,
                    tint = if (refreshing) FlatAccent else FlatTextSecondary,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(if (refreshing) angle else 0f),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = strings.interfacesAuto,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onSelect(null)
                    },
                )
                interfaces.forEach { iface ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = iface.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (iface.address == selected) FlatAccent else FlatTextPrimary,
                                )
                                Text(
                                    text = iface.address,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FlatTextSecondary,
                                )
                            }
                        },
                        onClick = {
                            menuOpen = false
                            onSelect(iface.address)
                        },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = strings.nearbyScan,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onRefreshNearby()
                    },
                )
            }
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
    val clipboard = LocalClipboardManager.current
    var copied by remember(portalUrl) { mutableStateOf(false) }
    val url = portalUrl

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1400)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlatSurface, FlatShape)
            .flatBorder()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(FlatSurfaceElevated, FlatShape)
                    .flatBorder(),
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
                    text = labelCase(deviceName),
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
            OBButton(
                id = "send_qr",
                text = strings.btnQr,
                onClick = onShowQr,
                enabled = isEngineReady,
                style = BUTTON_OUTLINED,
                icon = Icons.Rounded.QrCode2,
                height = 38.dp,
            )
        }

        if (url != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(FlatBorder),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = FlatTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Row(
                    modifier = Modifier
                        .clip(FlatShape)
                        .clickable {
                            clipboard.setText(AnnotatedString(url))
                            copied = true
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = strings.btnCopy,
                        tint = if (copied) FlatAccent else FlatTextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = labelCase(if (copied) strings.btnCopied else strings.btnCopy),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (copied) FlatAccent else FlatTextSecondary,
                    )
                }
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
    FlatCard {
        SectionHeader("01", strings.sendPackageTitle)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OBButton(
                id = "send_files",
                text = strings.btnFiles,
                onClick = onPickFiles,
                modifier = Modifier.weight(1f),
                look = OBButtonLook.Tile,
                icon = Icons.Rounded.Add,
            )
            OBButton(
                id = "send_photos",
                text = strings.btnPhotos,
                onClick = onPickPhotos,
                modifier = Modifier.weight(1f),
                look = OBButtonLook.Tile,
                icon = Icons.Rounded.Image,
            )
            OBButton(
                id = "send_folder",
                text = strings.btnFolder,
                onClick = onPickFolder,
                modifier = Modifier.weight(1f),
                look = OBButtonLook.Tile,
                icon = Icons.Rounded.Folder,
            )
            OBButton(
                id = "send_text",
                text = strings.btnText,
                onClick = onAddText,
                modifier = Modifier.weight(1f),
                look = OBButtonLook.Tile,
                icon = Icons.Rounded.ContentCopy,
            )
        }

        PluginSlot("send_toolbar", Modifier.padding(top = 8.dp))

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

        PluginSlot("send_package_actions", Modifier.padding(top = 8.dp))

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
            OBButton(
                id = "send_clear",
                text = strings.btnClear,
                onClick = onClear,
                look = OBButtonLook.Text,
            )
        }
    }
}

@Composable
private fun TransferCard(strings: AppStrings, state: TransferProgress) {
    val receiving = state.direction == TransferDirection.RECEIVING
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlatSurface, FlatShape)
            .flatBorder()
            .padding(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = labelCase(
                        if (receiving) {
                            strings.receivingFrom(state.peerName)
                        } else {
                            strings.transferringTo(state.peerName)
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = FlatTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = FlatAccent,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (state.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = FlatAccent,
                    trackColor = FlatSurfaceElevated,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = FlatAccent,
                    trackColor = FlatSurfaceElevated,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (state.currentFileName.isNotBlank()) {
                Text(
                    text = state.currentFileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlatTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.totalBytes > 0L) {
                        "${formatBytes(state.bytesTransferred)} / ${formatBytes(state.totalBytes)}"
                    } else {
                        formatBytes(state.bytesTransferred)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = FlatTextSecondary,
                )
                Text(
                    text = formatSpeed(state.speedBytesPerSec),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = FlatTextMuted,
                )
            }
        }
    }
}

@Composable
private fun SendingCard(strings: AppStrings, state: SendState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlatSurface, FlatShape)
            .flatBorder()
            .padding(16.dp),
    ) {
        Column {
            Text(
                text = labelCase(strings.transferringTo(state.peerName)),
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
            .background(FlatSurface, FlatShape)
            .flatBorder()
            .clickable(enabled = enabled, onClick = onSend)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(FlatSurfaceElevated, FlatShape)
                    .flatBorder(),
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
                    .flatBorder(if (enabled) FlatAccent else FlatBorder)
                    .clickable(enabled = enabled, onClick = onSend)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Send, contentDescription = null, tint = FlatAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = labelCase(strings.btnSend),
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
        shape = FlatShape,
        containerColor = FlatSurface,
        onDismissRequest = onDismiss,
        title = { Text(strings.textDialogTitle) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                shape = FlatShape,
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
