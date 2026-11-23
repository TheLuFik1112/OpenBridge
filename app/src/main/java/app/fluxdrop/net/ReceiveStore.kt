package app.fluxdrop.net

import android.content.Context
import app.fluxdrop.core.FileMeta
import app.fluxdrop.core.IdentityStore
import app.fluxdrop.core.TransferOffer
import app.fluxdrop.core.TransferResult
import app.fluxdrop.core.filesOrEmpty
import app.fluxdrop.core.totalBytes
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Приём файлов чанками.
 *
 *  - `.part` пишется по случайному смещению, поэтому чанки можно слать в любом порядке;
 *  - индексы принятых чанков лежат в `<index>.idx`, переживают убийство процесса;
 *  - SHA-256 каждого чанка и файла целиком берутся из манифеста отправителя;
 *  - готовый файл атомарно переезжает в out/ и затем копируется в Download/FluxDrop.
 */
class ReceiveStore(
    private val context: Context,
    @Suppress("unused") private val identity: IdentityStore,
) {

    class Session(val offer: TransferOffer, val token: String, val dir: File) {
        val files = ConcurrentHashMap<Int, ReceivingFile>()
        val totalBytes: Long = offer.payload.totalBytes

        @Volatile
        var onProgress: ((Long) -> Unit)? = null
    }

    class ReceivingFile(val meta: FileMeta, val part: File, val indexFile: File) {
        val handle = RandomAccessFile(part, "rw")
        val received: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

        fun loadProgress() {
            if (!indexFile.exists() || !part.exists()) return
            indexFile.readText()
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 0 until meta.chunkCount }
                .forEach { received.add(it) }
        }

        fun persistProgress() {
            runCatching {
                val snapshot = synchronized(received) { received.toList() }
                indexFile.writeText(snapshot.joinToString(","))
            }
        }

        fun close() {
            runCatching { handle.close() }
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    fun open(offer: TransferOffer, token: String): Session {
        val dir = File(context.filesDir, "incoming/${offer.transferId}")
            .apply { mkdirs() }
        val session = Session(offer, token, dir)
        sessions[offer.transferId] = session
        return session
    }

    fun session(transferId: String): Session? = sessions[transferId]

    fun progressBytes(transferId: String): Long {
        val session = sessions[transferId] ?: return 0
        return session.files.values.sumOf { file ->
            val complete = file.received.size.toLong() * file.meta.chunkSize
            minOf(complete, file.meta.size)
        }
    }

    fun registerMeta(transferId: String, index: Int, meta: FileMeta): Boolean {
        val session = sessions[transferId] ?: return false
        if (index != meta.index) return false
        if (meta.chunkHashes.isEmpty() && meta.size > 0) return false

        val safePath = sanitize(meta.path)
        if (safePath.isBlank()) return false

        val existing = session.files[index]
        if (existing != null) return true

        val partDir = File(session.dir, "part").apply { mkdirs() }
        val part = File(partDir, "$index.part")
        val indexFile = File(partDir, "$index.idx")
        val receiving = ReceivingFile(meta.copy(path = safePath), part, indexFile)
        receiving.loadProgress()
        session.files[index] = receiving
        return true
    }

    fun received(transferId: String, index: Int): List<Int> {
        val file = sessions[transferId]?.files?.get(index) ?: return emptyList()
        return synchronized(file.received) { file.received.sorted() }
    }

    fun writeChunk(transferId: String, index: Int, chunkIndex: Int, data: ByteArray): Boolean {
        val session = sessions[transferId] ?: return false
        val file = session.files[index] ?: return false
        if (chunkIndex !in 0 until file.meta.chunkCount) return false
        if (chunkIndex in file.received) return true

        val expected = file.meta.chunkHashes[chunkIndex]
        val actual = IdentityStore.sha256Hex(data)
        if (!actual.equals(expected, ignoreCase = true)) return false

        synchronized(file) {
            file.handle.seek(chunkIndex.toLong() * file.meta.chunkSize)
            file.handle.write(data)
            file.received.add(chunkIndex)
            // Пишем прогресс на диск каждые 8 чанков — достаточно для resume,
            // но не убивает скорость дисковыми операциями на каждый чанк.
            if (file.received.size % 8 == 0 || file.received.size == file.meta.chunkCount) {
                file.persistProgress()
            }
        }

        session.onProgress?.invoke(progressBytes(transferId))
        return true
    }

    fun finishFile(transferId: String, index: Int): TransferResult {
        val session = sessions[transferId] ?: return TransferResult(false, "нет сессии")
        val file = session.files[index] ?: return TransferResult(false, "нет файла $index")

        val missing = (0 until file.meta.chunkCount).filterNot { it in file.received }
        if (missing.isNotEmpty()) return TransferResult(false, "не хватает чанков: ${missing.size}")

        file.close()

        val actual = runCatching { sha256File(file.part) }.getOrNull()
            ?: return TransferResult(false, "не удалось прочитать файл")
        if (!actual.equals(file.meta.sha256, ignoreCase = true)) {
            return TransferResult(false, "SHA-256 не совпал")
        }

        val outDir = File(session.dir, "out").apply { mkdirs() }
        val target = File(outDir, file.meta.path)
        target.parentFile?.mkdirs()

        return runCatching {
            if (target.exists()) target.delete()
            if (!file.part.renameTo(target)) {
                file.part.copyTo(target, overwrite = true)
                file.part.delete()
            }
            file.indexFile.delete()
            TransferResult(true)
        }.getOrElse { TransferResult(false, it.message ?: "ошибка записи") }
    }

    fun complete(transferId: String): TransferResult {
        val session = sessions[transferId] ?: return TransferResult(false, "нет сессии")
        val outDir = File(session.dir, "out")

        val expectedFiles = session.offer.payload.filesOrEmpty
        if (expectedFiles.isNotEmpty()) {
            val missing = expectedFiles.filterNot { File(outDir, sanitize(it.path)).exists() }
            if (missing.isNotEmpty()) {
                return TransferResult(false, "не принято файлов: ${missing.size}")
            }
        }

        val exported = mutableListOf<String>()
        expectedFiles.forEach { meta ->
            val source = File(outDir, sanitize(meta.path))
            if (!source.exists()) return@forEach
            Downloads.save(context, source, sanitize(meta.path), meta.mime)
            exported += meta.path
        }

        session.files.values.forEach { it.close() }
        runCatching { session.dir.deleteRecursively() }
        sessions.remove(transferId)

        return TransferResult(true, exported.joinToString(", "))
    }

    fun abort(transferId: String) {
        val session = sessions.remove(transferId) ?: return
        session.files.values.forEach { it.close() }
        runCatching { session.dir.deleteRecursively() }
    }

    private fun sanitize(path: String): String {
        val cleaned = path
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
        return cleaned.take(240)
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
