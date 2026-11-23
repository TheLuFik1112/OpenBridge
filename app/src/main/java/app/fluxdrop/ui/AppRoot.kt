package app.fluxdrop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

private data class Tab(
    val title: (AppStrings) -> String,
    val label: (AppStrings) -> String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val tabs = listOf(
    Tab(title = { "FluxDrop" }, label = { it.tabSend }, icon = Icons.Rounded.Send),
    Tab(title = { it.tabHistory }, label = { it.tabHistory }, icon = Icons.Rounded.History),
    Tab(title = { it.tabSettings }, label = { it.tabSettings }, icon = Icons.Rounded.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: AppViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var qrPayload by remember { mutableStateOf<String?>(null) }

    val peers by viewModel.peers.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val message by viewModel.message.collectAsState()
    val trusted by viewModel.trusted.collectAsState()
    val history by viewModel.history.collectAsState()
    val portalUrl by viewModel.portalUrl.collectAsState()
    val crash by viewModel.crash.collectAsState()
    val engineReady by viewModel.isEngineReady.collectAsState()
    val nearbyIncoming by viewModel.nearbyIncoming.collectAsState()
    val nearbyProgress by viewModel.nearbyProgress.collectAsState()
    val strings by viewModel.strings.collectAsState()
    val nfcIncoming by viewModel.nfcIncoming.collectAsState()

    // Движок стартует только после того, как кадр уже нарисован.
    LaunchedEffect(Unit) {
        viewModel.startEngine()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) {
            viewModel.restartDiscovery()
        }
    }

    LaunchedEffect(Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT < 29) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addUris(uris) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> viewModel.addUris(uris) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.addFolder(it) } }

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val pagerState = rememberPagerState { tabs.size }
    val selectedTab = pagerState.currentPage

    CompositionLocalProvider(LocalStrings provides strings) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(tabs[selectedTab].title(strings)) })
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label(strings)) },
                            label = { Text(tab.label(strings)) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 0,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        0 -> HomeScreen(
                            viewModel = viewModel,
                            queue = queue,
                            peers = peers,
                            sending = sending,
                            isEngineReady = engineReady,
                            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                            onPickPhotos = { photoPicker.launch("image/*") },
                            onPickFolder = { folderPicker.launch(null) },
                            onShowQr = { url -> qrPayload = url },
                        )

                        1 -> HistoryScreen(history)

                        else -> SettingsScreen(
                            viewModel = viewModel,
                            trusted = trusted,
                        )
                    }
                }
            }
        }

        nearbyIncoming?.let { request ->
            NearbyIncomingSheet(
                request = request,
                onAccept = viewModel::acceptNearby,
                onTrust = viewModel::trustNearby,
                onDecline = viewModel::declineNearby,
            )
        }

        nearbyProgress?.let { progress ->
            NearbyProgressDialog(
                progress = progress,
                onDismiss = viewModel::dismissNearbyProgress,
            )
        }

        qrPayload?.let { payload ->
            QrDialog(payload = payload, onDismiss = { qrPayload = null })
        }

        if (nfcIncoming != null) {
            AlertDialog(
                onDismissRequest = viewModel::declineNfc,
                title = { Text(if (strings is RuStrings) "Входящая передача по NFC" else "Incoming NFC Transfer") },
                text = {
                    Text(
                        if (strings is RuStrings) "Телефон рядом передал файлы через NFC. Принять передачу?"
                        else "Nearby phone shared files via NFC. Accept transfer?"
                    )
                },
                confirmButton = {
                    Button(onClick = viewModel::acceptNfc) {
                        Text(strings.btnAccept)
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::declineNfc) {
                        Text(strings.btnDecline)
                    }
                },
            )
        }

        crash?.let { pending ->
            CrashDialog(
                crash = pending,
                onSend = viewModel::sendCrash,
                onDismiss = viewModel::dismissCrash,
            )
        }
    }
}