package app.openbridge.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbridge.core.APP_VERSION
import app.openbridge.text.AppStrings
import app.openbridge.util.hasOverlayPermission
import app.openbridge.util.isIgnoringBatteryOptimizations
import app.openbridge.util.openAppNotificationSettings
import app.openbridge.util.requestIgnoreBatteryOptimizations
import app.openbridge.util.requestOverlayPermission

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val deviceName by viewModel.deviceName.collectAsState()
    val trusted by viewModel.trusted.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val serviceEnabled by viewModel.serviceEnabled.collectAsState()
    val autoAccept by viewModel.autoAccept.collectAsState()
    val developerMode by viewModel.developerMode.collectAsState()
    val autoSendReports by viewModel.autoSendReports.collectAsState()

    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var langOpen by rememberSaveable { mutableStateOf(false) }
    var themeOpen by rememberSaveable { mutableStateOf(false) }
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    var logsOpen by rememberSaveable { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(hasOverlayPermission(context)) }
    var batteryOk by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    var taps by rememberSaveable { mutableStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "general") {
            SettingsCard(strings.generalCard) {
                ValueRow(
                    title = strings.themeLabel,
                    value = when (themeMode) {
                        THEME_LIGHT -> strings.themeLight
                        THEME_SYSTEM -> strings.themeSystem
                        else -> strings.themeDark
                    },
                    action = strings.btnChange,
                    onClick = { themeOpen = true },
                )
                RowDivider()
                ValueRow(
                    title = strings.languageLabel,
                    value = when (appLanguage) {
                        "en" -> strings.langEn
                        "ru" -> strings.langRu
                        else -> strings.langSystem
                    },
                    action = strings.btnChange,
                    onClick = { langOpen = true },
                )
            }
        }

        item(key = "receive") {
            SettingsCard(strings.receiveCard) {
                SwitchRow(
                    title = strings.autoAcceptTitle,
                    subtitle = strings.autoAcceptSubtitle,
                    checked = autoAccept,
                    onCheckedChange = viewModel::setAutoAccept,
                )
                RowDivider()
                GroupLabel(strings.trustedCard)
                if (trusted.none { !it.revoked }) {
                    Text(
                        strings.trustedEmpty,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    trusted.filterNot { it.revoked }.forEach { peer ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(peer.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    peer.fingerprint.take(16),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.revoke(peer.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = strings.btnRevoke)
                            }
                        }
                    }
                }
                RowDivider()
                SwitchRow(
                    title = strings.serviceTitle,
                    subtitle = strings.serviceSubtitle,
                    checked = serviceEnabled,
                    onCheckedChange = viewModel::setServiceEnabled,
                )
                RowDivider()
                ActionRow(
                    title = strings.overlayTitle,
                    subtitle = if (overlayGranted) strings.overlaySubtitleAllowed else strings.overlaySubtitleNeeded,
                    action = if (overlayGranted) strings.btnConfigure else strings.btnAllow,
                ) {
                    requestOverlayPermission(context)
                    overlayGranted = hasOverlayPermission(context)
                }
                RowDivider()
                ActionRow(
                    title = strings.batteryTitle,
                    subtitle = if (batteryOk) strings.batterySubtitleAllowed else strings.batterySubtitleNeeded,
                    action = strings.btnOpen,
                ) {
                    requestIgnoreBatteryOptimizations(context)
                    batteryOk = isIgnoringBatteryOptimizations(context)
                }
                RowDivider()
                ActionRow(
                    title = strings.notificationsTitle,
                    subtitle = strings.notificationsSubtitle,
                    action = strings.btnOpen,
                ) {
                    openAppNotificationSettings(context)
                }
            }
        }

        item(key = "network") {
            SettingsCard(strings.networkCard) {
                ValueRow(
                    title = strings.deviceNameLabel,
                    value = deviceName,
                    action = strings.btnChange,
                    onClick = { renameOpen = true },
                )
            }
        }

        item(key = "other") {
            SettingsCard(strings.otherCard) {
                SwitchRow(
                    title = strings.reportsTitle,
                    subtitle = if (autoSendReports) strings.reportsSubtitleAuto else strings.reportsSubtitleManual,
                    checked = autoSendReports,
                    onCheckedChange = viewModel::setAutoSendReports,
                )
                RowDivider()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(0.dp))
                        .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp))
                        .clickable {
                            if (developerMode) return@clickable
                            val now = System.currentTimeMillis()
                            taps = if (now - lastTapAt > 1_500) 1 else taps + 1
                            lastTapAt = now
                            if (taps >= 7) {
                                taps = 0
                                viewModel.setDeveloperMode(true)
                                Toast.makeText(
                                    context,
                                    "Developer mode enabled",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else if (taps >= 4) {
                                Toast.makeText(context, "${7 - taps}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "OpenBridge",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${strings.versionLabel} $APP_VERSION",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (developerMode) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "dev",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                AboutLink(label = strings.aboutCard, url = REPO_URL)
                AboutLink(label = strings.licenseLabel, url = LICENSE_URL)
            }
        }

        if (developerMode) {
            item(key = "developer") {
                SettingsCard("Developer Options") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Technical diagnostics & logs",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FlatSecondaryButton(
                            text = "Check server",
                            onClick = { viewModel.checkServer() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlatSecondaryButton(
                            text = "Save logs",
                            onClick = { viewModel.saveLogs() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FlatSecondaryButton(
                            text = "Restart server",
                            onClick = { viewModel.restartServer() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlatSecondaryButton(
                            text = "Statistics",
                            onClick = { statsOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    FlatSecondaryButton(
                        text = "Show logs",
                        onClick = { logsOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = {
                            viewModel.setDeveloperMode(false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Hide section") }
                }
            }
        }
    }

    if (themeOpen) {
        ThemeDialog(
            current = themeMode,
            strings = strings,
            onDismiss = { themeOpen = false },
            onSelect = { mode ->
                viewModel.setThemeMode(mode)
                themeOpen = false
            },
        )
    }

    if (langOpen) {
        LanguageDialog(
            current = appLanguage,
            strings = strings,
            onDismiss = { langOpen = false },
            onSelect = { code ->
                viewModel.setAppLanguage(code)
                langOpen = false
            },
        )
    }

    if (renameOpen) {
        var value by remember(deviceName) { mutableStateOf(deviceName) }
        AlertDialog(
            shape = RoundedCornerShape(0.dp),
            containerColor = FlatSurface,
            onDismissRequest = { renameOpen = false },
            title = { Text(strings.renameDialogTitle) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(40) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setDeviceName(value)
                        renameOpen = false
                    },
                    enabled = value.isNotBlank(),
                ) { Text(strings.btnSave) }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text(strings.btnCancel) }
            },
        )
    }

    if (logsOpen) {
        LogsDialog(viewModel = viewModel, onDismiss = { logsOpen = false })
    }

    if (statsOpen) {
        AlertDialog(
            shape = RoundedCornerShape(0.dp),
            containerColor = FlatSurface,
            onDismissRequest = { statsOpen = false },
            title = { Text("Server Stats") },
            text = {
                Text(
                    viewModel.serverStats(),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { statsOpen = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun ThemeDialog(
    current: String,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        shape = RoundedCornerShape(0.dp),
        containerColor = FlatSurface,
        onDismissRequest = onDismiss,
        title = { Text(strings.themeCard) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    THEME_DARK to strings.themeDark,
                    THEME_LIGHT to strings.themeLight,
                    THEME_SYSTEM to strings.themeSystem,
                ).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(mode) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlatCheckbox(checked = current == mode)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.btnCancel) }
        },
    )
}

@Composable
private fun LanguageDialog(
    current: String,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        shape = RoundedCornerShape(0.dp),
        containerColor = FlatSurface,
        onDismissRequest = onDismiss,
        title = { Text(strings.languageCard) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "system" to strings.langSystem,
                    "en" to strings.langEn,
                    "ru" to strings.langRu,
                ).forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(code) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlatCheckbox(checked = current == code)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.btnCancel) }
        },
    )
}

@Composable
private fun LogsDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("Reading logs…") }

    LaunchedEffect(Unit) {
        viewModel.logsFlow().collect { lines ->
            text = lines.ifBlank { "Logs are empty" }
        }
    }

    AlertDialog(
        shape = RoundedCornerShape(0.dp),
        containerColor = FlatSurface,
        onDismissRequest = onDismiss,
        title = { Text("Logs") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.saveLogs() }) { Text("Save to file") }
        },
    )
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    FlatCard {
        SectionHeader(title = title)
        content()
    }
}

@Composable
private fun ValueRow(
    title: String,
    value: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlatTextAction(text = action, onClick = onClick)
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        FlatToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = action.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = FlatAccent,
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

@Composable
private fun AboutLink(label: String, url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

private const val REPO_URL = "https://github.com/TheLuFik1112/OpenBridge"
private const val LICENSE_URL = "https://opensource.org/license/mit"
