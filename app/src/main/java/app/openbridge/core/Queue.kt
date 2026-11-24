package app.openbridge.core

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.util.UUID

enum class QueueKind { TEXT, FILE, FOLDER }

@Immutable
data class QueueItem(
    val id: String = UUID.randomUUID().toString(),
    val kind: QueueKind,
    val uri: Uri? = null,
    val text: String? = null,
    val name: String,
    val size: Long = 0L,
    val mime: String? = null,
)

data class SourceFile(val meta: FileMeta, val uri: Uri)

data class BuiltPayload(val payload: Payload, val sources: List<SourceFile>)
