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
| `navBarStyle` | `top_line`, `pill`, `floating`, `minimal`, `detached_round` | `top_line` |
| `navBarShowLabels` | `true`, `false` | `true` |
| `cardBorderWidthDp` | 0–3 | 1 |
| `cardElevationDp` | 0–8 | 0 |

`detached_round` draws the three tabs as separate rounded buttons floating above
the bottom edge, with space between them. With `navBarShowLabels: false` they
become circles with only icons.

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
my-plugin.obp
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
  "id": "my-plugin",
  "name": "My Plugin",
  "description": "What it does",
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
| `effects` | `ob.playEffect` |
| `vibrate` | `ob.vibrate` |
| `ui` | `ob.ui` |

`ob.toast` and `ob.log` always work because they cannot change anything outside
the app. Unknown permission names are ignored.

### main.js

```js
function onTextReceived(text, senderName, transport) {
  var value = String(text || '').trim();
  if (/^https?:\/\/\S+$/.test(value)) {
    ob.openUrl(value);
  }
}

function onFileReceived(fileName, filePath, mimeType, senderName, transport) {
  ob.log('file from ' + senderName + ': ' + fileName + ' via ' + transport);
}

function onTransferSent(fileNameOrText, recipientName, transport) {
  ob.log('sent to ' + recipientName + ' via ' + transport);
}
```

Hooks are optional. Define only the ones you need.

| Hook | Arguments |
| --- | --- |
| `onInit` | none |
| `onButtonClick` | `buttonId` |
| `onTextReceived` | `text`, `senderName`, `transport` |
| `onFileReceived` | `fileName`, `filePath`, `mimeType`, `senderName`, `transport` |
| `onTransferSent` | `fileNameOrText`, `recipientName`, `transport` |

`onInit` runs at startup and after the plugin is enabled or imported. It is the
place to register UI overrides and custom buttons. `onButtonClick` runs when a
button registered with `onClick: true` is pressed.

`onTextReceived` runs when a text package arrives, including the text part of a
mixed package. `onFileReceived` runs once per saved file. `onTransferSent` runs
after an outgoing package or clipboard text was delivered: `fileNameOrText` is
the text, the file name, or a comma-separated list of names for a package.

`transport` is one of:

| Value | Transfer |
| --- | --- |
| `lan` | direct Wi-Fi connection |
| `nearby` | Google Nearby |
| `nfc` | started by an NFC tap |
| `qr` | upload through the browser page |

The argument is optional: a plugin that declares only the older parameters
keeps working, and `transport` is then simply `undefined`.

### ob API

| Method | Does |
| --- | --- |
| `ob.openUrl(url)` | opens an `http://` or `https://` link in the browser |
| `ob.copyToClipboard(text)` | copies text to the clipboard |
| `ob.toast(message)` | shows a short toast |
| `ob.log(message)` | writes to Logcat with the `OBPlugin` tag |
| `ob.playEffect(type, options)` | plays a full-screen animation |
| `ob.vibrate(durationMs)` | vibrates for `durationMs`, clamped to 10–200 ms |

`ob.playEffect` draws over the app. When the app holds the overlay permission it
draws over other apps too; without the permission it stays inside OpenBridge.
The animation does not block touches.

| `type` | Animation |
| --- | --- |
| `wave` | radial wave with soft glow, starting from `origin` |
| `glow` | glowing pulse along the screen edges |

`options` is an object or a JSON string, both optional:

| Field | Values | Default |
| --- | --- | --- |
| `color` | `#RRGGBB` or `#AARRGGBB` | theme accent |
| `durationMs` | 300–2000 | 900 |
| `origin` | `top`, `center`, `bottom` (only `wave`) | `top` |

```js
ob.playEffect('wave', { origin: 'top', durationMs: 900, color: '#C95D48' });
ob.vibrate(35);
```

### ob.ui

Requires the `ui` permission. It changes the app's action buttons and adds
buttons to fixed slots.

| Method | Does |
| --- | --- |
| `ob.ui.overrideButton(id, config)` | changes a built-in button |
| `ob.ui.addButton(slotId, config)` | adds a button to a slot |
| `ob.ui.clear()` | removes everything this plugin registered |

`config` is an object or a JSON string. For `overrideButton`:

| Field | Values | Meaning |
| --- | --- | --- |
| `text` | string, up to 64 characters | label |
| `style` | `filled`, `outlined`, `tonal` | button style |
| `cornerRadiusDp` | 0–50 | corner radius |
| `color` | `#RRGGBB` or `#AARRGGBB` | accent color |
| `hidden` | `true`, `false` | hides the button |
| `onClick` | `true`, `false` | sends clicks to `onButtonClick` |

`addButton` uses the same fields and requires `id`, unique inside the plugin.
`text` falls back to the id. A custom button does nothing unless `onClick` is
`true`.

Button IDs:

| ID | Where |
| --- | --- |
| `send_files` | Send, add files |
| `send_photos` | Send, add photos |
| `send_folder` | Send, add a folder |
| `send_text` | Send, add text |
| `send_clear` | Send, clear the package |
| `send_qr` | Send, open the browser page |
| `settings_themes` | Settings, Themes |
| `settings_plugins` | Settings, Plugins |

Slot IDs:

| ID | Where |
| --- | --- |
| `send_toolbar` | Send, under the add buttons |
| `send_package_actions` | Send, above the package footer |
| `settings_extra` | Settings, after the About card |

For a built-in button with `onClick: true`, `onButtonClick(buttonId)` runs first
and the default action still runs. Overrides belong to the plugin that set them:
disabling, deleting or replacing the plugin clears its UI, and `ob.ui.clear()`
does the same from inside the plugin.

```js
function onInit() {
  ob.ui.overrideButton('send_text', {
    text: 'Note',
    style: 'tonal',
    cornerRadiusDp: 12
  });
  ob.ui.addButton('send_toolbar', {
    id: 'paste',
    text: 'Paste',
    style: 'outlined',
    onClick: true
  });
}

function onButtonClick(buttonId) {
  if (buttonId === 'paste') ob.toast('Paste pressed');
}
```

### How plugins run

Every plugin is executed in its own short-lived QuickJS context. A hook gets
2.5 seconds; after that it is stopped and the error is written to Logcat. A
broken plugin is skipped and never blocks a transfer.

Plugins see only the `ob` object. There is no file access, no network access
and no Android API.
