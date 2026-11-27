package app.openbridge.plugins

import android.content.Context
import android.util.Log
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs JavaScript plugin hooks in a short-lived QuickJS context.
 *
 * Every hook is dispatched to a background coroutine and each plugin gets at
 * most [TIMEOUT_MS] to finish. A broken script is logged and skipped; it can
 * never take the receive pipeline or the UI down with it.
 */
class ScriptPluginEngine(
    context: Context,
    private val manager: ScriptPluginManager,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // QuickJS is not thread safe and a script may block forever. A cached pool
    // with daemon threads keeps a stuck script away from the rest of the app.
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "OBPlugin").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun onTextReceived(text: String, senderName: String) {
        if (text.isBlank()) return
        dispatch(HOOK_TEXT) {
            "${jsString(text)}, ${jsString(senderName)}"
        }
    }

    fun onFileReceived(
        fileName: String,
        filePath: String,
        mimeType: String,
        senderName: String,
    ) {
        if (fileName.isBlank()) return
        dispatch(HOOK_FILE) {
            listOf(fileName, filePath, mimeType, senderName).joinToString(", ") { jsString(it) }
        }
    }

    private fun dispatch(hook: String, arguments: () -> String) {
        scope.launch {
            val plugins = runCatching { manager.enabledPlugins() }
                .getOrElse {
                    Log.w(LOG_TAG, "Cannot load script plugins: ${it.message}")
                    emptyList()
                }
            if (plugins.isEmpty()) return@launch

            val args = arguments()
            for (plugin in plugins) {
                try {
                    execute(plugin, hook, args)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(
                        LOG_TAG,
                        "Plugin ${plugin.manifest.id} failed in $hook: ${error.message}",
                        error,
                    )
                }
            }
        }
    }

    private suspend fun execute(plugin: ScriptPlugin, hook: String, args: String) {
        val source = runCatching {
            File(plugin.dir, plugin.manifest.entry).readText()
        }.getOrNull() ?: run {
            Log.w(LOG_TAG, "Plugin ${plugin.manifest.id}: cannot read ${plugin.manifest.entry}")
            return
        }

        val bridge: ObBridge = AndroidObBridge(
            context = appContext,
            pluginId = plugin.manifest.id,
            permissions = plugin.manifest.permissions.toSet(),
        )

        val task = executor.submit(Runnable {
            var quickJs: QuickJs? = null
            try {
                val instance = QuickJs.create()
                quickJs = instance
                instance.set("ob", ObBridge::class.java, bridge)
                instance.evaluate(source, plugin.manifest.entry)
                instance.evaluate(
                    "typeof $hook === 'function' ? $hook($args) : undefined",
                    "$hook.js",
                )
            } finally {
                runCatching { quickJs?.close() }
            }
        })

        val outcome = withContext(Dispatchers.Default) {
            runCatching { task.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }

        outcome.exceptionOrNull()?.let { error ->
            when (error) {
                is TimeoutException -> {
                    task.cancel(true)
                    Log.w(LOG_TAG, "Plugin ${plugin.manifest.id} timed out in $hook")
                }

                is ExecutionException -> throw error.cause ?: error
                else -> throw error
            }
        }
    }

    private fun jsString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (char < ' ') {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }

    private companion object {
        const val HOOK_TEXT = "onTextReceived"
        const val HOOK_FILE = "onFileReceived"
        const val TIMEOUT_MS = 2500L
    }
}
