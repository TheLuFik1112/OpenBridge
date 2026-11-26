<div align="center">

<img src="docs/icon.svg" width="96" height="96" alt="OpenBridge">

# OpenBridge

Прямая передача с устройства на устройство. Без интернета, облака и аккаунтов.

[![Версия](https://img.shields.io/badge/версия-0.6.0-C95D48?style=flat-square)](../../releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Лицензия](https://img.shields.io/badge/лицензия-MIT-121212?style=flat-square)](LICENSE)

[English](README.md) · **Русский**

</div>

## Обзор

OpenBridge передаёт файлы, фото, папки и текст между Android-устройствами по локальной сети или через прямое соединение Google Nearby. Данные не проходят через интернет, облачные сервисы и аккаунты. Если роутера рядом нет, телефоны всё равно находят друг друга и передают напрямую.

## Возможности

- **Сначала пакет** — соберите файлы, фото, папки или текст, затем выберите получателя.
- **NFC в одно касание** — свайп вниз на экране отправки и телефоны друг к другу.
- **QR для браузера** — откройте пакет на ПК или iPhone без установки приложения.
- **Работает без роутера** — LAN при наличии сети, Google Nearby без неё.
- **Приём в фоне** — поиск не останавливается, запросы показываются поверх других приложений, доверенные устройства принимаются автоматически.
- **Плоский технический интерфейс** — тёмная и светлая палитры, системная тема по умолчанию, русский и английский языки.
- **Плитка быстрых настроек** — отправка буфера обмена в один тап.
- **Докачка** — прерванная передача продолжается с того же места.

## Скриншоты

<p align="center">
  <img src="docs/screenshots/send.svg" width="200" alt="Пакет на отправку">
  <img src="docs/screenshots/nfc.svg" width="200" alt="Панель NFC">
  <img src="docs/screenshots/receive.svg" width="200" alt="Входящая передача">
  <img src="docs/screenshots/settings.svg" width="200" alt="Настройки">
</p>

Замените заглушки в [`docs/screenshots`](docs/screenshots) настоящими скриншотами, когда они будут готовы.

## Установка

1. Скачайте свежий `OpenBridge-*.apk` из [Releases](../../releases/latest).
2. Откройте файл на телефоне и разрешите установку из этого источника.
3. Выдайте разрешения, которые попросит приложение: уведомления, устройства рядом, память.

Требуется Android 8.0 или новее.

## Сборка из исходников

Нужны JDK 17 и Android SDK 35.

```bash
git clone https://github.com/TheLuFik1112/OpenBridge.git
cd OpenBridge
./gradlew assembleRelease
```

APK появится в `app/build/outputs/apk/release/`.

## Лицензия

Распространяется под [лицензией MIT](LICENSE).
