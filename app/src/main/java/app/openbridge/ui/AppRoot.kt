package app.openbridge.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

private data class Tab(
    val title: (AppStrings) -> String,
    val label: (AppStrings) -> String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val tabs = listOf(
    Tab(title = { "OpenBridge" }, label = { it.tabSend }, icon = Icons.Rounded.Send),
    Tab(title = { it.tabHistory }, label = { it.tabHistory }, icon = Icons.Rounded.History),
    Tab(title = { it.tabSettings }, label = { it.tabSettings }, icon = Icons.Rounded.Settings),
)

@Composable
fun AppRoot(viewModel: AppViewModel) {
    val themeMode by viewModel.themeMode.collectAsState()
    val dark = isDarkTheme(themeMode)
    val view = LocalView.current
    val context = LocalContext.current

    // Чёрная тема по умолчанию: значки системных панелей должны быть светлыми,
    // а в светлой теме — тёмными, иначе они теряются на фоне.
    LaunchedEffect(dark) {
        val window = context.findActivity()?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    OpenBridgeTheme(mode = themeMode) {
        AppContent(viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(viewModel: AppViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var qrPayload by remember { mutableStateOf<String?>(null) }

    // На корне держим только то, что действительно нужно всему экрану:
    // строки, снекбар, шторки и диалоги поверх. Всё остальное (очередь,
    // пиры, отправка, история, доверенные) собирают сами экраны — иначе
    // любой маяк из сети перерисовывал весь pager целиком.
    val message by viewModel.message.collectAsState()
    val crash by viewModel.crash.collectAsState()
    val nearbyIncoming by viewModel.nearbyIncoming.collectAsState()
    val nearbyProgress by viewModel.nearbyProgress.collectAsState()
    val strings by viewModel.strings.collectAsState()
    val nfcSheetOpen by viewModel.nfcSheetOpen.collectAsState()
    val nfcState by viewModel.nfcState.collectAsState()

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

    val coroutineScope = rememberCoroutineScope()
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
                            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                            onPickPhotos = { photoPicker.launch("image/*") },
                            onPickFolder = { folderPicker.launch(null) },
                            onShowQr = { url -> qrPayload = url },
                        )

                        1 -> HistoryScreen(viewModel)

                        else -> SettingsScreen(viewModel = viewModel)
                    }
                }
            }
        }

        if (nfcSheetOpen) {
            NfcSheet(
                state = nfcState,
                onDismiss = viewModel::closeNfcSheet,
            )
        }

        nearbyIncoming?.let { request ->
            NearbyIncomingSheet(
                request = request,
                onAccept = viewModel::acceptNearby,
                onTrust = viewModel::trustNearby,
                onDecline = viewModel::declineNearby,
            )
        }

        // Прогресс Nearby во время NFC уже виден в шторке — второй диалог не нужен.
        if (!nfcSheetOpen) nearbyProgress?.let { progress ->
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
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
