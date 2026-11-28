package app.openbridge.net

import android.content.Context
import android.net.Uri
import androidx.annotation.Keep
import app.openbridge.core.WEB_PORT
import app.openbridge.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonPrimitive
import java.net.Inet4Address
import java.net.NetworkInterface

@Keep
@Serializable
data class PortalFile(
    val id: String,
    val name: String,
    val size: Long,
    @Transient val uri: String? = null,
    val text: String? = null,
)

class Portal(
    private val context: Context,
    private val settings: SettingsRepository,
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

    fun html(token: String): String {
        val template = runCatching {
            context.assets.open("web/index.html")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull() ?: return fallbackHtml(token)
        return template.replace("__TOKEN_JSON__", JsonPrimitive(token).toString())
    }

    private fun fallbackHtml(token: String): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>OpenBridge</title>
<style>
body{margin:0;padding:24px;background:#0A0A0A;color:#F2F2F2;
font:14px/1.5 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
code{color:#C95D48}
</style>
</head>
<body>
<p>OpenBridge web interface is missing from this build.</p>
<p>Token: <code>${token.replace("<", "&lt;")}</code></p>
</body>
</html>
""".trimIndent()

    companion object {
        fun localIpAddress(): String {
            val interfaces = runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            }.getOrDefault(emptyList())

            val candidates = interfaces
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { nif ->
                    runCatching { nif.inetAddresses.toList() }.getOrDefault(emptyList())
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        .map { address ->
                            Candidate(
                                name = nif.name.orEmpty(),
                                address = address,
                                siteLocal = address.isSiteLocalAddress,
                            )
                        }
                }

            val chosen = candidates.firstOrNull {
                it.siteLocal && it.name.startsWith("wlan", ignoreCase = true)
            } ?: candidates.firstOrNull {
                it.siteLocal && (
                    it.name.startsWith("ap", ignoreCase = true) ||
                        it.name.contains("wifi", ignoreCase = true)
                    )
            } ?: candidates.firstOrNull { it.siteLocal }
                ?: candidates.firstOrNull()

            return chosen?.address?.hostAddress ?: "127.0.0.1"
        }

        private data class Candidate(
            val name: String,
            val address: Inet4Address,
            val siteLocal: Boolean,
        )
    }
}
