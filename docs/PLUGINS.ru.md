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
| `navBarStyle` | `top_line`, `pill`, `floating`, `minimal` | `top_line` |
| `cardBorderWidthDp` | 0–3 | 1 |
| `cardElevationDp` | 0–8 | 0 |

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
auto-open-links.obp
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
  "id": "auto-open-links",
  "name": "Auto Open Links",
  "description": "Открывает присланные ссылки в браузере",
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

`ob.toast` и `ob.log` работают всегда: они ничего не меняют вне приложения.
Неизвестные разрешения игнорируются.

### main.js

```js
function onTextReceived(text, senderName) {
  var value = String(text || '').trim();
  if (/^https?:\/\/\S+$/.test(value)) {
    ob.openUrl(value);
  }
}

function onFileReceived(fileName, filePath, mimeType, senderName) {
  ob.log('файл от ' + senderName + ': ' + fileName);
}
```

Хуки необязательны. Описывайте только нужные.

| Хук | Аргументы |
| --- | --- |
| `onTextReceived` | `text`, `senderName` |
| `onFileReceived` | `fileName`, `filePath`, `mimeType`, `senderName` |

`onTextReceived` вызывается, когда пришёл текстовый пакет, в том числе
текстовая часть смешанного. `onFileReceived` вызывается на каждый
сохранённый файл.

### API `ob`

| Метод | Что делает |
| --- | --- |
| `ob.openUrl(url)` | открывает ссылку `http://` или `https://` в браузере |
| `ob.copyToClipboard(text)` | копирует текст в буфер обмена |
| `ob.toast(message)` | показывает короткое сообщение |
| `ob.log(message)` | пишет в Logcat с тегом `OBPlugin` |

### Как выполняется плагин

Каждый плагин запускается в отдельном короткоживущем контексте QuickJS. На
хук даётся 2,5 секунды, потом он останавливается, а ошибка пишется в Logcat.
Сломанный плагин пропускается и не мешает передаче.

Плагину доступен только объект `ob`. Нет доступа к файлам, сети и Android
API.

### Готовый пример

В папке [`plugins/auto-open-links`](../plugins/auto-open-links) лежит рабочий
плагин, который открывает ссылки в браузере, включая короткие адреса вроде
`google.com`. Собранный файл —
[`plugins/auto-open-links.obp`](../plugins/auto-open-links.obp).
