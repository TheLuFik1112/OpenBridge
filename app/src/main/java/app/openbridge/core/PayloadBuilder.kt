package app.openbridge.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile

object PayloadBuilder {

    fun build(context: Context, items: List<QueueItem>): BuiltPayload? {
        val texts = mutableListOf<String>()
        val metas = mutableListOf<FileMeta>()
        val sources = mutableListOf<SourceFile>()
        var index = 0

        items.forEach { item ->
            when (item.kind) {
                QueueKind.TEXT -> item.text?.takeIf { it.isNotBlank() }?.let { texts += it }

                QueueKind.FILE -> {
                    val uri = item.uri ?: return@forEach
                    val meta = FileMeta(
                        index = index++,
                        path = safeName(item.name),
                        size = item.size,
                        mime = item.mime ?: "application/octet-stream",
                    )
                    metas += meta
                    sources += SourceFile(meta, uri)
                }

                QueueKind.FOLDER -> {
                    val uri = item.uri ?: return@forEach
                    val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
                        ?: return@forEach
                    walk(root, "", metas, sources) { index++ }
                }
            }
        }

        val text = texts.joinToString("\n\n").ifBlank { null }
        val payload: Payload = when {
            text != null && metas.isNotEmpty() -> Payload.Mixed(text, metas)
            metas.isNotEmpty() -> Payload.Files(metas)
            text != null -> Payload.Text(text)
            else -> return null
        }
        return BuiltPayload(payload, sources)
    }

    private fun walk(
        dir: DocumentFile,
        prefix: String,
        metas: MutableList<FileMeta>,
        sources: MutableList<SourceFile>,
        nextIndex: () -> Int,
    ) {
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        children.forEach { child ->
            val name = child.name ?: return@forEach
            val path = if (prefix.isBlank()) name else "$prefix/$name"
            if (child.isDirectory) {
                walk(child, path, metas, sources, nextIndex)
            } else {
                val uri = child.uri
                val meta = FileMeta(
                    index = nextIndex(),
                    path = path,
                    size = child.length(),
                    mime = child.type ?: "application/octet-stream",
                )
                metas += meta
                sources += SourceFile(meta, uri)
            }
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun sizeOf(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    return cursor.getLong(sizeIndex)
                }
            }
        }
        return 0L
    }

    fun mimeOf(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream"

    private fun safeName(name: String): String =
        name.replace('/', '_').replace('\\', '_').take(180).ifBlank { "file" }
}
