package app.openbridge.data

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

class JsonFile<T>(
    context: Context,
    fileName: String,
    private val serializer: KSerializer<T>,
    private val json: Json,
    private val default: T,
) {
    private val file = File(context.filesDir, fileName)

    @Synchronized
    fun load(): T = runCatching {
        if (!file.exists()) default else json.decodeFromString(serializer, file.readText())
    }.getOrDefault(default)

    @Synchronized
    fun save(value: T) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
