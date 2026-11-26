package app.openbridge.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.openbridge.text.AppStrings
import kotlinx.coroutines.launch

private data class Tab(
    val label: (AppStrings) -> String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val tabs = listOf(
    Tab(label = { it.tabSend }, icon = Icons.Rounded.Send),
    Tab(label = { it.tabHistory }, icon = Icons.Rounded.History),
    Tab(label = { it.tabSettings }, icon = Icons.Rounded.Settings),
)

@Composable
fun AppRoot(viewModel: AppViewModel) {
    val themeMode by viewModel.themeMode.collectAsState()
    val dark = isDarkTheme(themeMode)
    val view = LocalView.current
    val context = LocalContext.current

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

    val message by viewModel.message.collectAsState()
    val crash by viewModel.crash.collectAsState()
    val nearbyIncoming by viewModel.nearbyIncoming.collectAsState()
    val nearbyProgress by viewModel.nearbyProgress.collectAsState()
    val strings by viewModel.strings.collectAsState()

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
    LaunchedEffect(pagerState.currentPage) {
        viewModel.closeNfcSheet()
    }

    CompositionLocalProvider(LocalStrings provides strings) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = FlatBackground,
                        titleContentColor = FlatTextPrimary,
                    ),
                    title = {
                        Text(
                            text = tabs[selectedTab].label(strings).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FlatBackground),
                ) {
                    HorizontalDivider(thickness = 1.dp, color = FlatBorder)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            val selected = selectedTab == index
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .background(
                                            if (selected) FlatAccent else Color.Transparent,
                                            RoundedCornerShape(0.dp),
                                        ),
                                )
                                Spacer(Modifier.height(8.dp))
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label(strings),
                                    tint = if (selected) FlatAccent else FlatTextMuted,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = tab.label(strings).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = if (selected) FlatAccent else FlatTextMuted,
                                )
                            }
                        }
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
