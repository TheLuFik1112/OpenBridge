# FluxDrop

[Russian / Русский](readme/readme.ru.md)

---

Fast peer-to-peer file, photo, folder, and text transfer between devices: no internet required, no cloud, no accounts, and no registration. Devices discover each other on the local Wi-Fi network or connect directly via Google Nearby / Wi-Fi Direct when no router is available.

**Current version: 0.5.0** · Android 8.0+ (minSdk 26) · Kotlin + Jetpack Compose (Material 3)

## Features

- **Send Package:** Queue files, photos, folders, or clipboard text before selecting the recipient.
- **Dedicated Photo Picker:** Fast selection of pictures with the dedicated "Photos" button.
- **One-Tap NFC Transfer:** Swipe down on the home screen to open the "Hold devices together" panel and touch the phones back-to-back. The tag carries the sender's name and id: FluxDrop on the other phone finds that device over Nearby, connects and pulls the package by itself, so nothing is published on the local network. Without FluxDrop the tag falls through to the download page.
- **Black by Default:** A true-black Material 3 theme, with a light option in Settings → Appearance. No dynamic color.
- **One-Tap QR Code on Home Screen:** Instant QR code generation on the main screen to open the Web Portal in any browser (PC, iPhone, Android) without installing an app.
- **Dual Discovery Channels:**
  - **LAN:** UDP broadcast every 2 seconds with `MulticastLock`; works on any shared network or mobile hotspot.
  - **Nearby:** Google Nearby Connections (BLE + Wi-Fi Direct) connects phones directly without internet or router.
- **Enhanced Nearby Security:**
  - Untrusted devices are disconnected after transfer so that incoming confirmation sheets re-appear for every new transfer.
  - One-tap "Always Accept (Trusted)" button in the incoming sheet saves trusted devices.
- **Incoming Sheet Overlay:** Incoming prompts appear over any app even when FluxDrop is minimized.
- **High-Speed Resumable Transfers:** 16 MB chunks with SHA-256 integrity checks; interrupted transfers resume seamlessly.
- **Full Localization:** English and Russian languages supported with automatic system language detection and manual language switching in Settings.
- **Quick Settings Tile:** Send clipboard contents to the nearest trusted peer with a single tap.
- **Crash Diagnostics:** Error logs and crash reports can be inspected or exported in developer settings.

## Installation

1. Go to [Releases](../../releases) and download the latest `FluxDrop-*.apk`.
2. Open the file on your device and allow installation from this source.
3. Grant necessary permissions (Notifications, Nearby devices / Location, Storage).

## License

Distributed under the MIT License. See [LICENSE](LICENSE) for details.
