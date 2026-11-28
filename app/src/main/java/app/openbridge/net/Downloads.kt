package app.openbridge.net

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Downloads {
    fun save(context: Context, source: File, relativePath: String, mime: String): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val subDir = relativePath.substringBeforeLast('/', "")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "Download/OpenBridge" + if (subDir.isBlank()) "" else "/$subDir",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val target = File(root, "OpenBridge/$relativePath")
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            Uri.fromFile(target)
        }
    }.getOrNull()
}
