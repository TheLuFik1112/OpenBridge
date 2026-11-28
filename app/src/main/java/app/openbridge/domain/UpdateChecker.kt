package app.openbridge.domain

import app.openbridge.core.Diag
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val version: String,
    val name: String,
    val notes: String,
    val publishedAt: String,
    val pageUrl: String,
    val apkUrl: String?,
)

object UpdateChecker {
    const val RELEASES_PAGE = "https://github.com/TheLuFik1112/OpenBridge/releases"

    private const val API = "https://api.github.com/repos/TheLuFik1112/OpenBridge/releases"
    private const val USER_AGENT = "OpenBridge"

    fun latest(): ReleaseInfo? = fetch("$API/latest")

    fun byTag(tag: String): ReleaseInfo? = fetch("$API/tags/$tag")

    fun isNewer(candidate: String, current: String): Boolean {
        val left = numbers(candidate)
        val right = numbers(current)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun numbers(value: String): List<Int> =
        value.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split(Regex("[^0-9]+"))
            .mapNotNull { it.toIntOrNull() }

    private fun fetch(url: String): ReleaseInfo? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            parse(JSONObject(text))
        } finally {
            runCatching { connection.disconnect() }
        }
    }.onFailure {
        Diag.log("update", "check failed: ${it.javaClass.simpleName}")
    }.getOrNull()

    private fun parse(json: JSONObject): ReleaseInfo? {
        val tag = json.optString("tag_name").ifBlank { json.optString("name") }
        val version = tag.trim().removePrefix("v").removePrefix("V")
        if (version.isBlank()) return null

        val assets = json.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val item = assets.optJSONObject(index) ?: continue
                if (!item.optString("name").endsWith(".apk", ignoreCase = true)) continue
                apk = item.optString("browser_download_url").takeIf { it.isNotBlank() }
                if (apk != null) break
            }
        }

        return ReleaseInfo(
            version = version,
            name = json.optString("name").ifBlank { version },
            notes = json.optString("body").trim(),
            publishedAt = json.optString("published_at").take(10),
            pageUrl = json.optString("html_url").ifBlank { RELEASES_PAGE },
            apkUrl = apk,
        )
    }
}
