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

class ScriptPluginEngine(
    context: Context,
    private val manager: ScriptPluginManager,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "OBPlugin").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun onInit() {
        scope.launch {
            val plugins = loadPlugins()
            withContext(Dispatchers.Main) {
                PluginUiRegistry.retainPlugins(plugins.map { it.manifest.id }.toSet())
            }
            plugins.forEach { runHook(it, HOOK_INIT, "") }
        }
    }

    fun onInitPlugin(id: String) {
        scope.launch {
            val plugin = loadPlugins().firstOrNull { it.manifest.id == id } ?: return@launch
            withContext(Dispatchers.Main) { PluginUiRegistry.clearPlugin(id) }
            runHook(plugin, HOOK_INIT, "")
        }
    }

    fun onButtonClick(buttonId: String, pluginId: String? = null) {
        if (buttonId.isBlank()) return
        dispatch(HOOK_BUTTON, pluginId) { jsString(buttonId) }
    }

    fun onTextReceived(
        text: String,
        senderName: String,
        transport: String = TRANSPORT_LAN,
    ) {
        if (text.isBlank()) return
        dispatch(HOOK_TEXT) {
            "${jsString(text)}, ${jsString(senderName)}, ${jsString(transport)}"
        }
    }

    fun onFileReceived(
        fileName: String,
        filePath: String,
        mimeType: String,
        senderName: String,
        transport: String = TRANSPORT_LAN,
    ) {
        if (fileName.isBlank()) return
        dispatch(HOOK_FILE) {
            listOf(fileName, filePath, mimeType, senderName, transport)
                .joinToString(", ") { jsString(it) }
        }
    }

    fun onTransferSent(fileNameOrText: String, recipientName: String, transport: String) {
        dispatch(HOOK_SENT) {
            listOf(fileNameOrText, recipientName, transport).joinToString(", ") { jsString(it) }
        }
    }

    private fun dispatch(hook: String, onlyPlugin: String? = null, arguments: () -> String) {
        scope.launch {
            val plugins = loadPlugins()
            if (plugins.isEmpty()) return@launch

            val args = arguments()
            for (plugin in plugins) {
                if (onlyPlugin != null && plugin.manifest.id != onlyPlugin) continue
                runHook(plugin, hook, args)
            }
        }
    }

    private fun loadPlugins(): List<ScriptPlugin> =
        runCatching { manager.enabledPlugins() }
            .getOrElse {
                Log.w(LOG_TAG, "Cannot load script plugins: ${it.message}")
                emptyList()
            }

    private suspend fun runHook(plugin: ScriptPlugin, hook: String, args: String) {
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

    private suspend fun execute(plugin: ScriptPlugin, hook: String, args: String) {
        val source = runCatching {
            File(plugin.dir, plugin.manifest.entry).readText()
        }.getOrNull() ?: run {
            Log.w(LOG_TAG, "Plugin ${plugin.manifest.id}: cannot read ${plugin.manifest.entry}")
            return
        }

        val permissions = plugin.manifest.permissions.toSet()
        val bridge: ObBridge = AndroidObBridge(
            context = appContext,
            pluginId = plugin.manifest.id,
            permissions = permissions,
        )
        val uiBridge: ObUiBridge = AndroidObUiBridge(
            pluginId = plugin.manifest.id,
            permissions = permissions,
        )

        val task = executor.submit(Runnable {
            var quickJs: QuickJs? = null
            try {
                val instance = QuickJs.create()
                quickJs = instance
                instance.set("ob", ObBridge::class.java, bridge)
                instance.set("obUi", ObUiBridge::class.java, uiBridge)
                instance.evaluate(OB_PRELUDE, "ob.js")
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
        const val HOOK_INIT = "onInit"
        const val HOOK_BUTTON = "onButtonClick"
        const val HOOK_TEXT = "onTextReceived"
        const val HOOK_FILE = "onFileReceived"
        const val HOOK_SENT = "onTransferSent"
        const val TIMEOUT_MS = 2500L
        const val TRANSPORT_LAN = "lan"

        val OB_PRELUDE = """
            ob = (function (native, ui) {
              function text(value) {
                return value === undefined || value === null ? "" : String(value);
              }
              function options(value) {
                if (typeof value === "string") return value;
                if (value && typeof value === "object") {
                  try { return JSON.stringify(value); } catch (error) { return ""; }
                }
                return "";
              }
              return {
                openUrl: function (url) { native.openUrl(text(url)); },
                copyToClipboard: function (value) { native.copyToClipboard(text(value)); },
                toast: function (message) { native.toast(text(message)); },
                log: function (message) { native.log(text(message)); },
                playEffect: function (type, opts) {
                  native.playEffect(text(type), options(opts));
                },
                vibrate: function (ms) { native.vibrate(Math.round(Number(ms) || 0)); },
                ui: {
                  overrideButton: function (id, config) {
                    ui.overrideButton(text(id), options(config));
                  },
                  addButton: function (slotId, config) {
                    ui.addButton(text(slotId), options(config));
                  },
                  clear: function () { ui.clear(); }
                }
              };
            })(ob, obUi);
        """.trimIndent()
    }
}
