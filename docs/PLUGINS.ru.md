# Темы и плагины

OpenBridge принимает два вида дополнений: темы и скриптовые плагины. И то и
другое устанавливается из настроек и хранится внутри приложения, root и
сторонние программы не нужны.

- **Тема** меняет цвета, скругления, стили элементов и шрифт.
- **Скриптовый плагин** запускает небольшой JavaScript-файл, когда что-то
  приходит.

## Темы

Тема — это JSON-объект. Импортировать можно двумя способами:

- обычный файл `.json`;
- архив `.obp` (ZIP) с файлом `theme.json` внутри и, при желании, шрифтом.

Импорт: **Настройки → Оформление → + Установить (.OBP / .JSON)**. Там же
список установленных тем, переключение и удаление.

В `.json` нельзя положить шрифт. Если нужен свой шрифт, собирайте `.obp`.

### Минимальная тема

```json
{
  "id": "my-theme",
  "name": "Моя тема",
  "author": "Вы",
  "version": "1.0",
  "dark": {
    "background": "#0E0E0E",
    "surface": "#171717",
    "surfaceElevated": "#1F1F1F",
    "border": "#2A2A2A",
    "accent": "#C95D48",
    "textPrimary": "#F2F2F2",
    "textSecondary": "#9A9A9A"
  },
  "light": {
    "background": "#F5F5F5",
    "surface": "#FFFFFF",
    "surfaceElevated": "#FAFAFA",
    "border": "#DDDDDD",
    "accent": "#C95D48",
    "textPrimary": "#121212",
    "textSecondary": "#5F5F5F"
  }
}
```

Обязательны `id`, `name`, `dark` и `light`. Остальное имеет значения по
умолчанию.

### Поля

`shapes`

| Поле | Диапазон | По умолчанию | Что задаёт |
| --- | --- | --- | --- |
| `cardRadiusDp` | 0–28 | 0 | скругление карточек |
| `buttonRadiusDp` | 0–50 | 0 | скругление кнопок |
| `badgeRadiusDp` | 0–28 | 0 | скругление мелких плашек |

`components`

| Поле | Значения | По умолчанию |
| --- | --- | --- |
| `buttonStyle` | `filled`, `outlined`, `tonal` | `filled` |
| `switchStyle` | `flat`, `pill`, `ios` | `flat` |
| `navBarStyle` | `top_line`, `pill`, `floating`, `minimal`, `detached_round` | `top_line` |
| `navBarShowLabels` | `true`, `false` | `true` |
| `cardBorderWidthDp` | 0–3 | 1 |
| `cardElevationDp` | 0–8 | 0 |

`detached_round` рисует три вкладки отдельными скруглёнными кнопками над нижним
краем экрана, с промежутками между ними. При `navBarShowLabels: false` это
круги только с иконками.

`typography`

| Поле | Значения | По умолчанию |
| --- | --- | --- |
| `font` | `mono`, `sans`, `serif`, `custom` | `mono` |
| `fontFile` | имя файла `.ttf` или `.otf`, нужно для `custom` | нет |
| `uppercaseLabels` | `true`, `false` | `true` |
| `letterSpacingSp` | 0–4 | 1.2 |

В `dark` и `light` одинаковые семь цветов: `background`, `surface`,
`surfaceElevated`, `border`, `accent`, `textPrimary`, `textSecondary`.
Цвета записываются как `#RRGGBB` или `#AARRGGBB`.

### Свой шрифт

Положите шрифт рядом с `theme.json`:

```text
my-theme.obp
  theme.json
  Inter-Regular.ttf
```

```json
"typography": {
  "font": "custom",
  "fontFile": "Inter-Regular.ttf"
}
```

В имени файла можно использовать буквы, цифры, точку, дефис и подчёркивание,
окончание — `.ttf` или `.otf`.

### Ограничения

- `theme.json` до 512 КБ;
- шрифт до 4 МБ;
- архив до 8 МБ.

Темы старых версий (`controlsStyle`, `cornerRadiusDp`, `borderWidthDp`,
`font`) тоже загружаются и переводятся в новый формат автоматически.

## Скриптовые плагины

Плагин — это архив `.obp` с двумя файлами:

```text
my-plugin.obp
  manifest.json
  main.js
```

Импорт: **Настройки → Плагины → + Установить (.OBP)**. В списке плагин можно
включить, выключить и удалить.

Из архива распаковываются только `manifest.json` и файл входа. Остальное
игнорируется.

### manifest.json

```json
{
  "id": "my-plugin",
  "name": "My Plugin",
  "description": "Что делает плагин",
  "author": "Вы",
  "version": "1.0",
  "entry": "main.js",
  "permissions": ["open_url"]
}
```

| Поле | Обязательно | Что значит |
| --- | --- | --- |
| `id` | да | уникальное имя: буквы, цифры, точка, дефис, подчёркивание |
| `name` | да | показывается в списке плагинов |
| `description` | нет | короткая строка под названием |
| `author` | нет | по умолчанию `Unknown` |
| `version` | нет | по умолчанию `1.0` |
| `entry` | нет | файл JavaScript, по умолчанию `main.js` |
| `permissions` | нет | список разрешений, см. ниже |

Разрешения:

| Разрешение | Что открывает |
| --- | --- |
| `open_url` | `ob.openUrl` |
| `clipboard` | `ob.copyToClipboard` |
| `effects` | `ob.playEffect` |
| `vibrate` | `ob.vibrate` |
| `ui` | `ob.ui` |

`ob.toast` и `ob.log` работают всегда: они ничего не меняют вне приложения.
Неизвестные разрешения игнорируются.

### main.js

```js
function onTextReceived(text, senderName, transport) {
  var value = String(text || '').trim();
  if (/^https?:\/\/\S+$/.test(value)) {
    ob.openUrl(value);
  }
}

function onFileReceived(fileName, filePath, mimeType, senderName, transport) {
  ob.log('файл от ' + senderName + ': ' + fileName + ' через ' + transport);
}

function onTransferSent(fileNameOrText, recipientName, transport) {
  ob.log('отправлено ' + recipientName + ' через ' + transport);
}
```

Хуки необязательны. Описывайте только нужные.

| Хук | Аргументы |
| --- | --- |
| `onInit` | нет |
| `onButtonClick` | `buttonId` |
| `onTextReceived` | `text`, `senderName`, `transport` |
| `onFileReceived` | `fileName`, `filePath`, `mimeType`, `senderName`, `transport` |
| `onTransferSent` | `fileNameOrText`, `recipientName`, `transport` |

`onInit` вызывается при запуске приложения, а также после включения или
установки плагина. В нём регистрируются изменения кнопок и свои кнопки.
`onButtonClick` вызывается, когда нажата кнопка с `onClick: true`.

`onTextReceived` вызывается, когда пришёл текстовый пакет, в том числе
текстовая часть смешанного. `onFileReceived` вызывается на каждый
сохранённый файл. `onTransferSent` вызывается после успешной отправки пакета
или текста из буфера: в `fileNameOrText` лежит текст, имя файла или список имён
через запятую для пакета.

`transport` принимает значения:

| Значение | Передача |
| --- | --- |
| `lan` | прямое соединение по Wi-Fi |
| `nearby` | Google Nearby |
| `nfc` | запущено касанием NFC |
| `qr` | загрузка через страницу в браузере |

Аргумент необязательный: плагин со старыми параметрами продолжает работать,
а `transport` в нём будет `undefined`.

### API `ob`

| Метод | Что делает |
| --- | --- |
| `ob.openUrl(url)` | открывает ссылку `http://` или `https://` в браузере |
| `ob.copyToClipboard(text)` | копирует текст в буфер обмена |
| `ob.toast(message)` | показывает короткое сообщение |
| `ob.log(message)` | пишет в Logcat с тегом `OBPlugin` |
| `ob.playEffect(type, options)` | проигрывает анимацию на весь экран |
| `ob.vibrate(durationMs)` | вибрирует `durationMs`, ограничено 10–200 мс |

`ob.playEffect` рисует поверх приложения. Если у приложения есть разрешение на
наложение, анимация видна и в других программах; без разрешения она остаётся
внутри OpenBridge. Касания анимация не перехватывает.

| `type` | Анимация |
| --- | --- |
| `wave` | круговая волна с мягким свечением из точки `origin` |
| `glow` | пульсирующее свечение по краям экрана |

`options` — объект или JSON-строка, оба необязательны:

| Поле | Значения | По умолчанию |
| --- | --- | --- |
| `color` | `#RRGGBB` или `#AARRGGBB` | акцент темы |
| `durationMs` | 300–2000 | 900 |
| `origin` | `top`, `center`, `bottom` (только `wave`) | `top` |

```js
ob.playEffect('wave', { origin: 'top', durationMs: 900, color: '#C95D48' });
ob.vibrate(35);
```

### `ob.ui`

Нужно разрешение `ui`. Меняет кнопки действий в приложении и добавляет свои
кнопки в фиксированные слоты.

| Метод | Что делает |
| --- | --- |
| `ob.ui.overrideButton(id, config)` | меняет встроенную кнопку |
| `ob.ui.addButton(slotId, config)` | добавляет кнопку в слот |
| `ob.ui.clear()` | убирает всё, что зарегистрировал плагин |

`config` — объект или JSON-строка. Для `overrideButton`:

| Поле | Значения | Что задаёт |
| --- | --- | --- |
| `text` | строка до 64 символов | подпись |
| `style` | `filled`, `outlined`, `tonal` | стиль кнопки |
| `cornerRadiusDp` | 0–50 | скругление |
| `color` | `#RRGGBB` или `#AARRGGBB` | цвет акцента |
| `hidden` | `true`, `false` | скрыть кнопку |
| `onClick` | `true`, `false` | отправлять нажатия в `onButtonClick` |

В `addButton` те же поля и обязательный `id`, уникальный внутри плагина. Если
`text` не задан, берётся `id`. Своя кнопка ничего не делает без `onClick:
true`.

ID кнопок:

| ID | Где |
| --- | --- |
| `send_files` | Отправка, добавить файлы |
| `send_photos` | Отправка, добавить фото |
| `send_folder` | Отправка, добавить папку |
| `send_text` | Отправка, добавить текст |
| `send_clear` | Отправка, очистить пакет |
| `send_qr` | Отправка, открыть страницу в браузере |
| `settings_themes` | Настройки, темы |
| `settings_plugins` | Настройки, плагины |

ID слотов:

| ID | Где |
| --- | --- |
| `send_toolbar` | Отправка, под кнопками добавления |
| `send_package_actions` | Отправка, над нижней строкой пакета |
| `settings_extra` | Настройки, после карточки «О приложении» |

Для встроенной кнопки с `onClick: true` сначала вызывается
`onButtonClick(buttonId)`, затем выполняется обычное действие. Изменения
принадлежат плагину: выключение, удаление или замена плагина убирает его UI,
`ob.ui.clear()` делает то же изнутри плагина.

```js
function onInit() {
  ob.ui.overrideButton('send_text', {
    text: 'Заметка',
    style: 'tonal',
    cornerRadiusDp: 12
  });
  ob.ui.addButton('send_toolbar', {
    id: 'paste',
    text: 'Вставить',
    style: 'outlined',
    onClick: true
  });
}

function onButtonClick(buttonId) {
  if (buttonId === 'paste') ob.toast('Нажато');
}
```

### Как выполняется плагин

Каждый плагин запускается в отдельном короткоживущем контексте QuickJS. На
хук даётся 2,5 секунды, потом он останавливается, а ошибка пишется в Logcat.
Сломанный плагин пропускается и не мешает передаче.

Плагину доступен только объект `ob`. Нет доступа к файлам, сети и Android
API.
