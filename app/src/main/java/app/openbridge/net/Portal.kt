package app.openbridge.net

import android.content.Context
import android.net.Uri
import app.openbridge.core.WEB_PORT
import app.openbridge.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface

@Serializable
data class PortalFile(
    val id: String,
    val name: String,
    val size: Long,
    val uri: String? = null,
    val text: String? = null,
)

class Portal(
    @Suppress("unused") private val context: Context,
    private val settings: SettingsRepository,
    @Suppress("unused") private val json: Json,
) {
    private val _files = MutableStateFlow<List<PortalFile>>(emptyList())
    val files: StateFlow<List<PortalFile>> = _files

    fun publish(files: List<PortalFile>) {
        _files.value = files
    }

    fun file(id: String): PortalFile? = _files.value.firstOrNull { it.id == id }

    fun uri(file: PortalFile): Uri? = file.uri?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun token(): String = settings.portalToken()

    fun rotateToken(): String = settings.newToken()

    fun check(token: String?): Boolean =
        !token.isNullOrBlank() && token == settings.portalToken()

    fun url(): String {
        val ip = localIpAddress()
        return "http://$ip:$WEB_PORT/web?t=${token()}"
    }

    fun html(token: String): String = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>OpenBridge</title>
<style>
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body { margin:0; font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
         background:#f4f5f9; color:#12151c; padding:20px; }
  @media (prefers-color-scheme: dark) { body { background:#111318; color:#e7e9ef; } }
  .wrap { max-width:560px; margin:0 auto; }
  h1 { font-size:24px; margin:0 0 4px; }
  .sub { opacity:.65; margin:0 0 20px; font-size:14px; }
  .card { background:#fff; border-radius:18px; padding:16px; margin-bottom:14px;
          box-shadow:0 1px 3px rgba(0,0,0,.08); }
  @media (prefers-color-scheme: dark) { .card { background:#1c1f27; } }
  ul { list-style:none; margin:0; padding:0; }
  li { display:flex; align-items:center; justify-content:space-between; gap:12px;
       padding:10px 0; border-bottom:1px solid rgba(128,128,128,.18); }
  li:last-child { border-bottom:none; }
  .name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .meta { opacity:.55; font-size:12px; }
  a.btn, label.btn { background:#3d6bff; color:#fff; text-decoration:none; border-radius:12px;
       padding:9px 14px; font-size:14px; cursor:pointer; display:inline-block; }
  input[type=file] { display:none; }
  .empty { opacity:.6; font-size:14px; }
  .ok { color:#1a7f37; font-size:14px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>OpenBridge</h1>
  <p class="sub">Файлы с телефона. Скачивание и загрузка — в этой же сети.</p>
  <div class="card"><ul id="list"><li class="empty">Загрузка списка…</li></ul></div>
  <div class="card">
    <label class="btn" for="f">Отправить файл на телефон</label>
    <input id="f" type="file" multiple>
    <p id="status" class="ok"></p>
  </div>
</div>
<script>
const token = ${'$'}{JSON.stringify(token)};
const list = document.getElementById('list');
const human = n => n < 1024 ? n + ' Б' : n < 1048576 ? (n/1024).toFixed(1) + ' КБ'
  : n < 1073741824 ? (n/1048576).toFixed(1) + ' МБ' : (n/1073741824).toFixed(2) + ' ГБ';
async function refresh() {
  try {
    const res = await fetch('/web/list?t=' + encodeURIComponent(token));
    const items = await res.json();
    if (!items.length) { list.innerHTML = '<li class="empty">Пока нет файлов для скачивания</li>'; return; }
    const esc = s => s.replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
    list.innerHTML = items.map(f => '<li><span class="name">' + esc(f.name) +
      (f.text ? '<br><span class="meta">' + esc(f.text.slice(0, 140)) + '</span>' : '') +
      '<br><span class="meta">' + (f.text ? 'Текст' : human(f.size)) + '</span></span>' +
      '<a class="btn" href="/web/get?t=' + encodeURIComponent(token) +
      '&id=' + encodeURIComponent(f.id) + '">' + (f.text ? 'Открыть' : 'Скачать') + '</a></li>').join('');
  } catch (e) {
    list.innerHTML = '<li class="empty">Не удалось получить список</li>';
  }
}
document.getElementById('f').onchange = async e => {
  const status = document.getElementById('status');
  for (const file of e.target.files) {
    status.textContent = 'Отправка ' + file.name + '…';
    const res = await fetch('/web/up?t=' + encodeURIComponent(token) +
      '&name=' + encodeURIComponent(file.name), { method:'PUT', body: file });
    status.textContent = res.ok ? 'Готово: ' + file.name : 'Ошибка: ' + file.name;
  }
};
refresh();
setInterval(refresh, 5000);
</script>
</body>
</html>
""".trimIndent()

    companion object {
        fun localIpAddress(): String {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList().asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull() ?: "127.0.0.1"
        }
    }
}
