# FluxDrop

[English](#english) | [Русский](#русский)

---

<a name="english"></a>
## English

Fast peer-to-peer file, photo, folder, and text transfer between devices: no internet required, no cloud, no accounts, and no registration. Devices discover each other on the local Wi-Fi network or connect directly via Google Nearby / Wi-Fi Direct when no router is available.

**Current version: 0.4.0** · Android 8.0+ (minSdk 26) · Kotlin + Jetpack Compose (Material 3)

### Features

- **Send Package:** Queue files, photos, folders, or clipboard text before selecting the recipient.
- **Dedicated Photo Picker:** Fast selection of pictures with the dedicated "Photos" button.
- **NFC Tap & Share:** Tap phones together to share data. If the receiver has FluxDrop installed, it automatically launches via Android Application Record (`app.fluxdrop.p2p`). If not, it opens the download page.
- **One-Tap QR Code on Home Screen:** Instant QR code generation on the main screen to open the Web Portal in any browser (PC, iPhone, Android) without installing an app.
- **Dual Discovery Channels:**
  - **LAN:** UDP broadcast every 2 seconds with `MulticastLock`; works on any shared network or mobile hotspot.
  - **Nearby:** Google Nearby Connections (BLE + Wi-Fi Direct) connects phones directly without internet or router.
- **Enhanced Nearby Security:**
  - Untrusted devices are disconnected after transfer so that incoming confirmation sheets re-appear for every new transfer.
  - One-tap "Always Accept (Trusted)" button in the incoming sheet saves trusted devices.
- **Incoming Sheet Overlay:** Incoming prompts appear over any app even when FluxDrop is minimized.
- **High-Speed Resumable Transfers:** 16 MB chunks with SHA-256 integrity checks; interrupted transfers resume seamlessly.
- **Quick Settings Tile:** Send clipboard contents to the nearest trusted peer with a single tap.
- **Crash Diagnostics:** Error logs and crash reports can be inspected or exported in developer settings.

### Installation

1. Go to [Releases](../../releases) and download the latest `FluxDrop-*.apk`.
2. Open the file on your device and allow installation from this source.
3. Grant necessary permissions (Notifications, Nearby devices / Location, Storage).

### License

Distributed under the MIT License. See [LICENSE](LICENSE) for details.

---

<a name="русский"></a>
## Русский

Быстрая передача файлов, фото, папок и текста между устройствами напрямую: без интернета, без облака, без аккаунтов и без регистрации. Устройства находят друг друга в локальной Wi-Fi сети или соединяются напрямую через Google Nearby / Wi-Fi Direct, если роутера рядом нет.

**Текущая версия: 0.4.0** · Android 8.0+ (minSdk 26) · Kotlin + Jetpack Compose (Material 3)

### Возможности

- **Пакет отправки:** Собирайте файлы, фото, папки или текст, затем выбирайте получателя.
- **Кнопка «Фото»:** Быстрый выбор изображений отдельной кнопкой рядом с файлами и папками.
- **Передача по NFC:** Приложите телефон к другому устройству: при наличии FluxDrop открывается приложение через AAR (`app.fluxdrop.p2p`), а если приложения нет — ссылка на страницу скачивания.
- **Кнопка QR-кода на главном экране:** Быстрый показ QR-кода веб-портала прямо со статусной карточки главного экрана — удобно для передачи на ПК или iPhone без установки приложения.
- **Два канала передачи:**
  - **LAN:** Активный UDP-broadcast каждые 2 секунды с `MulticastLock`; работает в локальной сети и точке доступа (хотспоте).
  - **Nearby:** Google Nearby Connections (BLE + Wi-Fi Direct) для связи напрямую без роутера.
- **Безопасность Nearby:**
  - Для недоверенных устройств сессия сбрасывается после отправки, поэтому запрос на приём вылезает при каждой новой передаче.
  - В шторку добавлена кнопка «Всегда принимать (Доверенное)» для добавления в белый список.
- **Входящая шторка:** Показывается поверх любых приложений, даже когда FluxDrop свёрнут.
- **Высокая скорость и докачка:** Чанки по 16 МБ с SHA-256; передача продолжается с места обрыва.
- **Плитка в шторке Android:** Отправка буфера обмена в один тап.

### Установка

1. Откройте [Releases](../../releases) и скачайте свежий `FluxDrop-*.apk`.
2. Откройте файл на телефоне и подтвердите установку.
3. Выдайте разрешения (Уведомления, Устройства рядом, Память).

### Лицензия

Распространяется под лицензией MIT. Подробности в файле [LICENSE](LICENSE).
