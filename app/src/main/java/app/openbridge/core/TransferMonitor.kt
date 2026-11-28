package app.openbridge.core

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TransferDirection { SENDING, RECEIVING }

@Immutable
data class TransferProgress(
    val isTransferring: Boolean = false,
    val direction: TransferDirection = TransferDirection.SENDING,
    val transport: String = "",
    val peerName: String = "",
    val currentFileName: String = "",
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
) {
    val progress: Float
        get() = if (totalBytes > 0L) {
            (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }
}

class TransferMonitor {
    private val _state = MutableStateFlow(TransferProgress())
    val state: StateFlow<TransferProgress> = _state.asStateFlow()

    private val lock = Any()
    private var lastBytes = 0L
    private var lastAt = 0L
    private var smoothedSpeed = 0.0

    fun start(
        direction: TransferDirection,
        transport: String,
        peerName: String,
        totalBytes: Long,
        currentFileName: String = "",
        filesTotal: Int = 0,
        filesDone: Int = 0,
        initialBytes: Long = 0L,
    ) {
        synchronized(lock) {
            lastBytes = initialBytes.coerceAtLeast(0L)
            lastAt = SystemClock.elapsedRealtime()
            smoothedSpeed = 0.0
            _state.value = TransferProgress(
                isTransferring = true,
                direction = direction,
                transport = transport,
                peerName = peerName,
                currentFileName = currentFileName,
                bytesTransferred = lastBytes,
                totalBytes = totalBytes.coerceAtLeast(0L),
                speedBytesPerSec = 0L,
                filesDone = filesDone,
                filesTotal = filesTotal,
            )
        }
    }

    fun update(
        bytesTransferred: Long,
        currentFileName: String? = null,
        filesDone: Int? = null,
        totalBytes: Long? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val current = _state.value
            if (!current.isTransferring) return

            val done = bytesTransferred.coerceAtLeast(0L)
            val name = currentFileName ?: current.currentFileName
            val finished = filesDone ?: current.filesDone
            val total = totalBytes?.takeIf { it > 0L } ?: current.totalBytes

            val elapsed = now - lastAt
            val metadataChanged = name != current.currentFileName || finished != current.filesDone
            if (elapsed < THROTTLE_MS && !metadataChanged) return

            if (elapsed >= THROTTLE_MS) {
                val delta = done - lastBytes
                if (delta >= 0L) {
                    val instant = delta * 1000.0 / elapsed
                    smoothedSpeed = if (smoothedSpeed <= 0.0) {
                        instant
                    } else {
                        smoothedSpeed * 0.65 + instant * 0.35
                    }
                }
                lastBytes = done
                lastAt = now
            }

            _state.value = current.copy(
                bytesTransferred = done,
                currentFileName = name,
                filesDone = finished,
                totalBytes = total,
                speedBytesPerSec = smoothedSpeed.toLong().coerceAtLeast(0L),
            )
        }
    }

    fun tick() {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val current = _state.value
            if (!current.isTransferring) return
            if (current.speedBytesPerSec == 0L) return
            if (now - lastAt < STALL_MS) return
            _state.value = current.copy(speedBytesPerSec = 0L)
        }
    }

    fun reset() {
        synchronized(lock) {
            lastBytes = 0L
            lastAt = 0L
            smoothedSpeed = 0.0
            if (_state.value != TransferProgress()) {
                _state.value = TransferProgress()
            }
        }
    }

    private companion object {
        const val THROTTLE_MS = 250L
        const val STALL_MS = 1_500L
    }
}
