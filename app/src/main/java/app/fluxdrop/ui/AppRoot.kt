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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

private data class Tab(
    val title: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val tabs = listOf(
    Tab("Отправить", "Отправить", Icons.Rounded.Send),
    Tab("История", "История", Icons.Rounded.History),
    Tab("Настройки", "Настройки", Icons.Rounded.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: AppViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
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

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.addFolder(it) } }

    // Вкладки живут в HorizontalPager: все три страницы остаются в композиции,
    // переключение — мгновенный scrollToPage, без пересоздания экранов.
    val pagerState = rememberPagerState { tabs.size }

    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) pagerState.scrollToPage(selectedTab)
    }
    LaunchedEffect(pagerState.currentPage) {
        if (selectedTab != pagerState.currentPage) selectedTab = pagerState.currentPage
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(tabs[selectedTab].title) })
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = tabs.lastIndex,
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
                        onPickFolder = { folderPicker.launch(null) },
                        onShowQr = { url -> qrPayload = url },
                    )

                    1 -> HistoryScreen(history)

                    else -> SettingsScreen(
                        viewModel = viewModel,
                        trusted = trusted,
                        portalUrl = portalUrl,
                        onShowQr = { url -> qrPayload = url },
                    )
                }
            }
        }
    }

    nearbyIncoming?.let { request ->
        NearbyIncomingSheet(
            request = request,
            onAccept = viewModel::acceptNearby,
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

    crash?.let { pending ->
        CrashDialog(
            crash = pending,
            onSend = viewModel::sendCrash,
            onDismiss = viewModel::dismissCrash,
        )
    }
}