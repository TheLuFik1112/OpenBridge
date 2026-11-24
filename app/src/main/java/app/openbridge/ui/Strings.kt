package app.openbridge.ui

import androidx.compose.runtime.compositionLocalOf
import java.util.Locale

interface AppStrings {
    val tabSend: String
    val tabHistory: String
    val tabSettings: String

    val statusPreparing: String
    fun statusPeersCount(count: Int): String
    val statusWaitingPeers: String
    val btnQr: String
    val btnFiles: String
    val btnPhotos: String
    val btnFolder: String
    val btnText: String
    val sendPackageTitle: String
    val sendPackageEmpty: String
    val btnClear: String
    val btnRemove: String
    val devicesNearby: String
    val noDevicesYet: String
    val noDevicesHint: String
    val btnSend: String
    val peerNearbyDirect: String
    val peerSearching: String
    fun transferringTo(name: String): String
    val textDialogTitle: String
    val textDialogPlaceholder: String
    val btnAdd: String
    val btnCancel: String

    // NFC
    val nfcTitle: String
    val nfcHoldToSend: String
    val nfcSent: String
    val nfcReceiving: String
    val nfcReceived: String
    fun nfcReceivedCount(count: Int): String
    val nfcReceiveFailed: String
    fun nfcConnecting(name: String): String
    val nfcSending: String
    val nfcSendFailed: String
    val nfcNoNearbyPeer: String
    val nfcNearbyUnavailable: String
    val nfcNothingToReceive: String
    val nfcEmptyPackage: String
    val nfcTextCopied: String
    val nfcNotAvailable: String
    val nfcNeedItems: String

    // Settings
    val settingsTitle: String
    val deviceCard: String
    val deviceNameLabel: String
    val btnChange: String
    val renameDialogTitle: String
    val btnSave: String
    val themeCard: String
    val themeLabel: String
    val themeDark: String
    val themeLight: String
    val themeSystem: String
    val languageCard: String
    val languageLabel: String
    val langSystem: String
    val langEn: String
    val langRu: String
    val serviceCard: String
    val serviceTitle: String
    val serviceSubtitle: String
    val overlayTitle: String
    val overlaySubtitleAllowed: String
    val overlaySubtitleNeeded: String
    val btnConfigure: String
    val btnAllow: String
    val batteryTitle: String
    val batterySubtitleAllowed: String
    val batterySubtitleNeeded: String
    val btnOpen: String
    val notificationsTitle: String
    val notificationsSubtitle: String
    val receiveCard: String
    val autoAcceptTitle: String
    val autoAcceptSubtitle: String
    val reportsCard: String
    val reportsTitle: String
    val reportsSubtitleAuto: String
    val reportsSubtitleManual: String
    val trustedCard: String
    val trustedEmpty: String
    val btnRevoke: String
    val aboutCard: String
    val versionLabel: String
    val licenseLabel: String

    // Sheet
    val nearbyIncomingDesc: String
    val btnTrustAlways: String
    val btnDecline: String
    val btnAccept: String
    val qrScanTitle: String
    val qrError: String
}

object RuStrings : AppStrings {
    override val tabSend = "Отправить"
    override val tabHistory = "История"
    override val tabSettings = "Настройки"

    override val statusPreparing = "Готовим соединение…"
    override fun statusPeersCount(count: Int) = "Рядом устройств: $count"
    override val statusWaitingPeers = "Ожидаю устройства рядом"
    override val btnQr = "QR"
    override val btnFiles = "Файлы"
    override val btnPhotos = "Фото"
    override val btnFolder = "Папка"
    override val btnText = "Текст"
    override val sendPackageTitle = "Пакет отправки"
    override val sendPackageEmpty = "Добавьте файлы, фото, папку или текст — получателя можно выбрать потом."
    override val btnClear = "Очистить"
    override val btnRemove = "Убрать"
    override val devicesNearby = "Устройства рядом"
    override val noDevicesYet = "Пока никого не видно"
    override val noDevicesHint = "Откройте OpenBridge на втором устройстве рядом. " +
            "Общая Wi-Fi сеть не обязательна: без роутера устройства найдут друг друга через Google Nearby."
    override val btnSend = "Отправить"
    override val peerNearbyDirect = "Nearby • без роутера"
    override val peerSearching = "поиск…"
    override fun transferringTo(name: String) = "Передача: $name"
    override val textDialogTitle = "Текст в буфер получателя"
    override val textDialogPlaceholder = "Ссылка, пароль, заметка…"
    override val btnAdd = "Добавить"
    override val btnCancel = "Отмена"

    override val nfcTitle = "Приложите телефон"
    override val nfcHoldToSend = "Приложите телефон для отправки"
    override val nfcSent = "Отправлено!"
    override val nfcReceiving = "Получение пакета…"
    override val nfcReceived = "Готово"
    override fun nfcReceivedCount(count: Int) = "Получено файлов: $count"
    override val nfcReceiveFailed = "Не удалось получить пакет. Поднесите телефон ещё раз"
    override fun nfcConnecting(name: String) = "Соединяемся с $name…"
    override val nfcSending = "Передаём пакет…"
    override val nfcSendFailed = "Не удалось передать пакет. Поднесите телефон ещё раз"
    override val nfcNoNearbyPeer = "Отправитель не найден поблизости. Включите Bluetooth на обоих телефонах"
    override val nfcNearbyUnavailable = "Передача поблизости недоступна: нет Google Play Services"
    override val nfcNothingToReceive = "В метке нет пакета — обновите OpenBridge на обоих телефонах"
    override val nfcEmptyPackage = "В пакете отправителя пока ничего нет"
    override val nfcTextCopied = "Текст скопирован в буфер обмена"
    override val nfcNotAvailable = "NFC недоступен или выключен на этом телефоне"
    override val nfcNeedItems = "Сначала добавьте файлы, фото или текст в пакет"

    override val settingsTitle = "Настройки"
    override val deviceCard = "Устройство"
    override val deviceNameLabel = "Имя в сети"
    override val btnChange = "Изменить"
    override val renameDialogTitle = "Имя устройства"
    override val btnSave = "Сохранить"
    override val themeCard = "Оформление"
    override val themeLabel = "Тема"
    override val themeDark = "Чёрная (по умолчанию)"
    override val themeLight = "Светлая"
    override val themeSystem = "Как в системе"
    override val languageCard = "Язык / Language"
    override val languageLabel = "Язык интерфейса"
    override val langSystem = "Системный (по умолчанию)"
    override val langEn = "English"
    override val langRu = "Русский"
    override val serviceCard = "Фоновая работа"
    override val serviceTitle = "Служба обнаружения"
    override val serviceSubtitle = "Приём передач, даже когда приложение свёрнуто"
    override val overlayTitle = "Шторка поверх других приложений"
    override val overlaySubtitleAllowed = "Разрешено"
    override val overlaySubtitleNeeded = "Нужно для показа запроса поверх чужих приложений"
    override val btnConfigure = "Настроить"
    override val btnAllow = "Разрешить"
    override val batteryTitle = "Не оптимизировать батарею"
    override val batterySubtitleAllowed = "Разрешено"
    override val batterySubtitleNeeded = "Иначе Android может убивать приём в фоне"
    override val btnOpen = "Открыть"
    override val notificationsTitle = "Уведомления"
    override val notificationsSubtitle = "Запросы на приём и прогресс"
    override val receiveCard = "Приём"
    override val autoAcceptTitle = "Автоприём от доверенных"
    override val autoAcceptSubtitle = "Без шторки, только тихое уведомление о прогрессе"
    override val reportsCard = "Отчёты об ошибках"
    override val reportsTitle = "Автоматически отправлять отчёты об ошибках"
    override val reportsSubtitleAuto = "При сбое отчёт уйдёт в фоне, без лишних окон"
    override val reportsSubtitleManual = "При сбое появится окно с предложением отправить логи"
    override val trustedCard = "Доверенные устройства"
    override val trustedEmpty = "Пока пусто. Поставьте галочку «Всегда принимать» во входящей шторке."
    override val btnRevoke = "Отозвать"
    override val aboutCard = "О приложении"
    override val versionLabel = "Версия"
    override val licenseLabel = "Лицензия"

    override val nearbyIncomingDesc = "Телефон рядом хочет передать данные напрямую без роутера через Nearby / Wi-Fi Direct."
    override val btnTrustAlways = "Всегда принимать (Доверенное)"
    override val btnDecline = "Отклонить"
    override val btnAccept = "Принять"
    override val qrScanTitle = "Отсканируйте камерой телефона"
    override val qrError = "Не удалось построить QR-код"
}

object EnStrings : AppStrings {
    override val tabSend = "Send"
    override val tabHistory = "History"
    override val tabSettings = "Settings"

    override val statusPreparing = "Preparing connection…"
    override fun statusPeersCount(count: Int) = "Nearby devices: $count"
    override val statusWaitingPeers = "Looking for nearby devices"
    override val btnQr = "QR"
    override val btnFiles = "Files"
    override val btnPhotos = "Photos"
    override val btnFolder = "Folder"
    override val btnText = "Text"
    override val sendPackageTitle = "Send Package"
    override val sendPackageEmpty = "Add files, photos, folders, or text — you can select recipient later."
    override val btnClear = "Clear"
    override val btnRemove = "Remove"
    override val devicesNearby = "Nearby Devices"
    override val noDevicesYet = "No devices found yet"
    override val noDevicesHint = "Open OpenBridge on the nearby device. " +
            "A shared Wi-Fi network is not required: devices connect directly via Google Nearby."
    override val btnSend = "Send"
    override val peerNearbyDirect = "Nearby • direct"
    override val peerSearching = "searching…"
    override fun transferringTo(name: String) = "Transferring: $name"
    override val textDialogTitle = "Text to recipient clipboard"
    override val textDialogPlaceholder = "Link, note, password…"
    override val btnAdd = "Add"
    override val btnCancel = "Cancel"

    override val nfcTitle = "Hold devices together"
    override val nfcHoldToSend = "Hold your phone here to send"
    override val nfcSent = "Sent!"
    override val nfcReceiving = "Receiving the package…"
    override val nfcReceived = "Done"
    override fun nfcReceivedCount(count: Int) = "Files received: $count"
    override val nfcReceiveFailed = "Could not receive the package. Touch the phones together again"
    override fun nfcConnecting(name: String) = "Connecting to $name…"
    override val nfcSending = "Sending the package…"
    override val nfcSendFailed = "Could not send the package. Touch the phones together again"
    override val nfcNoNearbyPeer = "Sender not found nearby. Make sure Bluetooth is on for both phones"
    override val nfcNearbyUnavailable = "Nearby transfer is unavailable: Google Play Services is missing"
    override val nfcNothingToReceive = "The tag has no package — update OpenBridge on both phones"
    override val nfcEmptyPackage = "The sender's package is still empty"
    override val nfcTextCopied = "Text copied to clipboard"
    override val nfcNotAvailable = "NFC is not supported or disabled on this device"
    override val nfcNeedItems = "Add files, photos or text to the package first"

    override val settingsTitle = "Settings"
    override val deviceCard = "Device"
    override val deviceNameLabel = "Network Name"
    override val btnChange = "Change"
    override val renameDialogTitle = "Device Name"
    override val btnSave = "Save"
    override val themeCard = "Appearance"
    override val themeLabel = "Theme"
    override val themeDark = "Black (default)"
    override val themeLight = "Light"
    override val themeSystem = "Follow system"
    override val languageCard = "Language"
    override val languageLabel = "Interface Language"
    override val langSystem = "System (Default)"
    override val langEn = "English"
    override val langRu = "Русский"
    override val serviceCard = "Background Service"
    override val serviceTitle = "Discovery Service"
    override val serviceSubtitle = "Receive transfers even when the app is in background"
    override val overlayTitle = "Display over other apps"
    override val overlaySubtitleAllowed = "Allowed"
    override val overlaySubtitleNeeded = "Required to show incoming request over other apps"
    override val btnConfigure = "Settings"
    override val btnAllow = "Allow"
    override val batteryTitle = "Disable battery optimization"
    override val batterySubtitleAllowed = "Allowed"
    override val batterySubtitleNeeded = "Prevents Android from killing transfer service in background"
    override val btnOpen = "Open"
    override val notificationsTitle = "Notifications"
    override val notificationsSubtitle = "Transfer requests and progress"
    override val receiveCard = "Receiving"
    override val autoAcceptTitle = "Auto-accept from trusted"
    override val autoAcceptSubtitle = "Silent background transfer without confirmation sheet"
    override val reportsCard = "Error Reports"
    override val reportsTitle = "Automatically send error reports"
    override val reportsSubtitleAuto = "Crash reports sent silently in background"
    override val reportsSubtitleManual = "Prompt with log report on next launch"
    override val trustedCard = "Trusted Devices"
    override val trustedEmpty = "No trusted devices yet. Tap 'Always accept' on incoming transfer."
    override val btnRevoke = "Revoke"
    override val aboutCard = "About"
    override val versionLabel = "Version"
    override val licenseLabel = "License"

    override val nearbyIncomingDesc = "Nearby device wants to transfer data directly without a router via Nearby / Wi-Fi Direct."
    override val btnTrustAlways = "Always accept (Trusted)"
    override val btnDecline = "Decline"
    override val btnAccept = "Accept"
    override val qrScanTitle = "Scan with phone camera"
    override val qrError = "Failed to generate QR code"
}

fun resolveStrings(languageCode: String): AppStrings {
    return when (languageCode.lowercase()) {
        "en" -> EnStrings
        "ru" -> RuStrings
        else -> {
            val sysLang = Locale.getDefault().language
            if (sysLang.startsWith("ru")) RuStrings else EnStrings
        }
    }
}

val LocalStrings = compositionLocalOf<AppStrings> { RuStrings }
