package app.fluxdrop.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fluxdrop.core.APP_VERSION
import app.fluxdrop.core.TrustedPeer
import app.fluxdrop.util.hasOverlayPermission
import app.fluxdrop.util.isIgnoringBatteryOptimizations
import app.fluxdrop.util.openAppNotificationSettings
import app.fluxdrop.util.requestIgnoreBatteryOptimizations
import app.fluxdrop.util.requestOverlayPermission

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    trusted: List<TrustedPeer>,
    portalUrl: String?,
    onShowQr: (String) -> Unit,
) {
    val context = LocalContext.current
    val deviceName by viewModel.deviceName.collectAsState()
    val serviceEnabled by viewModel.serviceEnabled.collectAsState()
    val autoAccept by viewModel.autoAccept.collectAsState()
    val portalEnabled by viewModel.portalEnabled.collectAsState()
    val developerMode by viewModel.developerMode.collectAsState()
    val autoSendReports by viewModel.autoSendReports.collectAsState()

    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    var logsOpen by rememberSaveable { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(hasOverlayPermission(context)) }
    var batteryOk by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // Пасхалка: 7 быстрых нажатий на номер версии открывают раздел для разработчиков.
    var taps by rememberSaveable { mutableStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "device") {
            SettingsCard("Устройство") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Имя в сети", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            deviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { renameOpen = true }) { Text("Изменить") }
                }
            }
        }

        item(key = "service") {
            SettingsCard("Фоновая работа") {
                SwitchRow(
                    title = "Служба обнаружения",
                    subtitle = "Приём передач, даже когда приложение свёрнуто",
                    checked = serviceEnabled,
                    onCheckedChange = viewModel::setServiceEnabled,
                )
                Spacer(Modifier.height(8.dp))
                ActionRow(
                    title = "Шторка поверх других приложений",
                    subtitle = if (overlayGranted) "Разрешено" else "Нужно для показа запроса поверх чужих приложений",
                    action = if (overlayGranted) "Настроить" else "Разрешить",
                ) {
                    requestOverlayPermission(context)
                    overlayGranted = hasOverlayPermission(context)
                }
                ActionRow(
                    title = "Не оптимизировать батарею",
                    subtitle = if (batteryOk) "Разрешено" else "Иначе Android может убивать приём в фоне",
                    action = "Открыть",
                ) {
                    requestIgnoreBatteryOptimizations(context)
                    batteryOk = isIgnoringBatteryOptimizations(context)
                }
                ActionRow(
                    title = "Уведомления",
                    subtitle = "Запросы на приём и прогресс",
                    action = "Открыть",
                ) {
                    openAppNotificationSettings(context)
                }
            }
        }

        item(key = "receive") {
            SettingsCard("Приём") {
                SwitchRow(
                    title = "Автоприём от доверенных",
                    subtitle = "Без шторки, только тихое уведомление о прогрессе",
                    checked = autoAccept,
                    onCheckedChange = viewModel::setAutoAccept,
                )
            }
        }

        item(key = "reports") {
            SettingsCard("Отчёты об ошибках") {
                SwitchRow(
                    title = "Автоматически отправлять отчёты об ошибках разработчику",
                    subtitle = if (autoSendReports) {
                        "При сбое отчёт уйдёт в фоне, без лишних окон"
                    } else {
                        "При сбое появится окно с предложением отправить логи"
                    },
                    checked = autoSendReports,
                    onCheckedChange = viewModel::setAutoSendReports,
                )
            }
        }

        item(key = "trusted") {
            SettingsCard("Доверенные устройства") {
                if (trusted.none { !it.revoked }) {
                    Text(
                        "Пока пусто. Поставьте галочку «Всегда принимать» во входящей шторке.",
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
                                Icon(Icons.Rounded.Delete, contentDescription = "Отозвать")
                            }
                        }
                    }
                }
            }
        }

        item(key = "portal") {
            SettingsCard("Веб-портал") {
                SwitchRow(
                    title = "Браузер без приложения",
                    subtitle = "Страница по QR-коду для скачивания и загрузки файлов",
                    checked = portalEnabled,
                    onCheckedChange = viewModel::setPortalEnabled,
                )
                if (portalEnabled && portalUrl != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        portalUrl,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onShowQr(portalUrl) }, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Показать QR")
                    }
                }
            }
        }

        item(key = "about") {
            SettingsCard("О приложении") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
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
                                        "Режим разработчика включён",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else if (taps >= 4) {
                                    Toast.makeText(context, "Ещё ${7 - taps}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("FluxDrop", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Версия $APP_VERSION",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (developerMode) {
                            Text(
                                "режим разработчика",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Локальная передача: TLS + подпись запросов, файлы чанками 16 МБ с SHA-256, " +
                        "доверенные устройства — по отпечатку ключа.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (developerMode) {
            item(key = "developer") {
                SettingsCard("Для разработчиков") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Технические проверки и журналы",
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
                        OutlinedButton(
                            onClick = { viewModel.checkServer() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) { Text("Проверить сервер") }
                        OutlinedButton(
                            onClick = { viewModel.saveLogs() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) { Text("Сохранить логи") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.restartServer() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) { Text("Перезапуск релея") }
                        OutlinedButton(
                            onClick = { statsOpen = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) { Text("Статистика") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { logsOpen = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Показать журнал") }
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = {
                            viewModel.setDeveloperMode(false)
                            Toast.makeText(context, "Режим разработчика выключен", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Скрыть раздел") }
                }
            }
        }
    }

    if (renameOpen) {
        var value by remember(deviceName) { mutableStateOf(deviceName) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Имя устройства") },
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
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Отмена") }
            },
        )
    }

    if (logsOpen) {
        LogsDialog(viewModel = viewModel, onDismiss = { logsOpen = false })
    }

    if (statsOpen) {
        AlertDialog(
            onDismissRequest = { statsOpen = false },
            title = { Text("Сетевая статистика") },
            text = {
                Text(
                    viewModel.serverStats(),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { statsOpen = false }) { Text("Закрыть") }
            },
        )
    }
}

/**
 * Журнал читается только пока открыт этот диалог: поток запускается в
 * LaunchedEffect и умирает вместе с композицией. Радар и настройки его не читают.
 */
@Composable
private fun LogsDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("Читаю журнал…") }

    LaunchedEffect(Unit) {
        viewModel.logsFlow().collect { lines ->
            text = lines.ifBlank { "Журнал пуст" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Журнал") },
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
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.saveLogs() }) { Text("Сохранить в файл") }
        },
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) {
            Text(action)
        }
    }
}