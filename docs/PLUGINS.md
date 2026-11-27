# Themes and plugins

OpenBridge accepts two kinds of add-ons: themes and script plugins. Both are
installed from Settings and stored inside the app, so no root or extra app is
needed.

- A **theme** changes colors, shapes, component styles and the font.
- A **script plugin** runs a small JavaScript file when something is received.

## Themes

A theme is a JSON object. You can import it in two ways:

- a plain `.json` file;
- an `.obp` archive (a ZIP) with `theme.json` inside, plus an optional font.

Import: **Settings → Themes → Import**. The same screen lists installed themes
and lets you switch or delete them.

A `.json` file cannot carry a font. If you want a custom font, use `.obp`.

### Minimal theme

```json
{
  "id": "my-theme",
  "name": "My Theme",
  "author": "You",
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

`id`, `name`, `dark` and `light` are required. Everything else has a default.

### Fields

`shapes`

| Field | Range | Default | Meaning |
| --- | --- | --- | --- |
| `cardRadiusDp` | 0–28 | 0 | corner radius of cards |
| `buttonRadiusDp` | 0–50 | 0 | corner radius of buttons |
| `badgeRadiusDp` | 0–28 | 0 | corner radius of small labels |

`components`

| Field | Values | Default |
| --- | --- | --- |
| `buttonStyle` | `filled`, `outlined`, `tonal` | `filled` |
| `switchStyle` | `flat`, `pill`, `ios` | `flat` |
| `navBarStyle` | `top_line`, `pill`, `floating`, `minimal` | `top_line` |
| `cardBorderWidthDp` | 0–3 | 1 |
| `cardElevationDp` | 0–8 | 0 |

`typography`

| Field | Values | Default |
| --- | --- | --- |
| `font` | `mono`, `sans`, `serif`, `custom` | `mono` |
| `fontFile` | `.ttf` or `.otf` file name, required for `custom` | none |
| `uppercaseLabels` | `true`, `false` | `true` |
| `letterSpacingSp` | 0–4 | 1.2 |

`dark` and `light` accept the same seven colors: `background`, `surface`,
`surfaceElevated`, `border`, `accent`, `textPrimary`, `textSecondary`.
Colors are written as `#RRGGBB` or `#AARRGGBB`.

### Custom font

Pack the font next to `theme.json`:

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

File names may contain letters, digits, dot, dash and underscore, and must end
with `.ttf` or `.otf`.

### Limits

- `theme.json` up to 512 KB;
- font up to 4 MB;
- archive up to 8 MB.

Themes made for older versions (`controlsStyle`, `cornerRadiusDp`,
`borderWidthDp`, `font`) still load and are converted automatically.

## Script plugins

A script plugin is an `.obp` archive with two files:

```text
auto-open-links.obp
  manifest.json
  main.js
```

Import: **Settings → Plugins → Import**. The plugin list lets you turn a plugin
on or off and delete it.

Only `manifest.json` and the entry file are extracted from the archive.
Everything else is ignored.

### manifest.json

```json
{
  "id": "auto-open-links",
  "name": "Auto Open Links",
  "description": "Opens received links in the browser",
  "author": "You",
  "version": "1.0",
  "entry": "main.js",
  "permissions": ["open_url"]
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | unique name, letters, digits, dot, dash, underscore |
| `name` | yes | shown in the plugin list |
| `description` | no | short line under the name |
| `author` | no | defaults to `Unknown` |
| `version` | no | defaults to `1.0` |
| `entry` | no | JavaScript file, defaults to `main.js` |
| `permissions` | no | list of permissions, see below |

Permissions:

| Permission | Allows |
| --- | --- |
| `open_url` | `ob.openUrl` |
| `clipboard` | `ob.copyToClipboard` |

`ob.toast` and `ob.log` always work because they cannot change anything outside
the app. Unknown permission names are ignored.

### main.js

```js
function onTextReceived(text, senderName) {
  var value = String(text || '').trim();
  if (/^https?:\/\/\S+$/.test(value)) {
    ob.openUrl(value);
  }
}

function onFileReceived(fileName, filePath, mimeType, senderName) {
  ob.log('file from ' + senderName + ': ' + fileName);
}
```

Hooks are optional. Define only the ones you need.

| Hook | Arguments |
| --- | --- |
| `onTextReceived` | `text`, `senderName` |
| `onFileReceived` | `fileName`, `filePath`, `mimeType`, `senderName` |

`onTextReceived` runs when a text package arrives, including the text part of a
mixed package. `onFileReceived` runs once per saved file.

### ob API

| Method | Does |
| --- | --- |
| `ob.openUrl(url)` | opens an `http://` or `https://` link in the browser |
| `ob.copyToClipboard(text)` | copies text to the clipboard |
| `ob.toast(message)` | shows a short toast |
| `ob.log(message)` | writes to Logcat with the `OBPlugin` tag |

### How plugins run

Every plugin is executed in its own short-lived QuickJS context. A hook gets
2.5 seconds; after that it is stopped and the error is written to Logcat. A
broken plugin is skipped and never blocks a transfer.

Plugins see only the `ob` object. There is no file access, no network access
and no Android API.

### Complete example

The [`plugins/auto-open-links`](../plugins/auto-open-links) folder contains a
working plugin that opens links in the browser, including bare domains such as
`google.com`. The packaged file is
[`plugins/auto-open-links.obp`](../plugins/auto-open-links.obp).
