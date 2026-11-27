# Script plugins

An `.obp` file is a ZIP with `manifest.json` and a JavaScript entry file
(`main.js` by default).

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

`permissions` can contain `open_url` and `clipboard`. `toast` and `log` are
always available.

`main.js` can define these hooks:

```js
function onTextReceived(text, senderName) {}
function onFileReceived(fileName, filePath, mimeType, senderName) {}
```

The sandbox exposes one object:

```js
ob.openUrl(url);              // http:// or https:// only
ob.copyToClipboard(text);
ob.toast(message);
ob.log(message);              // Logcat tag: OBPlugin
```

`auto-open-links/` is a complete example; `auto-open-links.obp` is the same
plugin packaged for import from Settings → Plugins.
