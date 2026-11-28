package app.openbridge.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.openbridge.overlay.OverlayLifecycleOwner
import app.openbridge.theme.BuiltInThemes
import app.openbridge.theme.ThemeConfig
import app.openbridge.theme.normalizeHexColor
import app.openbridge.theme.parseThemeColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

data class EffectRequest(
    val id: Int,
    val type: String,
    val color: Color?,
    val durationMs: Int,
    val origin: String,
)

object EffectPlayer {
    private const val MIN_DURATION_MS = 300
    private const val MAX_DURATION_MS = 2000

    private val main = Handler(Looper.getMainLooper())
    private var counter = 0
    private var host: EffectWindowHost? = null

    @Volatile
    var theme: ThemeConfig = BuiltInThemes.DefaultFlat

    @Volatile
    var themeMode: String = THEME_SYSTEM

    private val _inApp = MutableStateFlow<EffectRequest?>(null)
    val inApp: StateFlow<EffectRequest?> = _inApp.asStateFlow()

    fun play(context: Context, type: String, color: Color?, durationMs: Int, origin: String) {
        val request = EffectRequest(
            id = ++counter,
            type = type,
            color = color,
            durationMs = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
            origin = when (origin.trim().lowercase()) {
                "center" -> "center"
                "bottom" -> "bottom"
                else -> "top"
            },
        )

        main.post {
            runCatching {
                if (Settings.canDrawOverlays(context)) {
                    val windowHost = host ?: EffectWindowHost(context).also { host = it }
                    windowHost.show(request)
                } else {
                    _inApp.value = request
                }
            }
        }
    }

    fun clearInApp(id: Int) {
        if (_inApp.value?.id == id) _inApp.value = null
    }
}

fun parseEffectColor(raw: String): Color? {
    val hex = normalizeHexColor(raw) ?: return null
    return parseThemeColor(hex)
}

@Composable
fun EffectLayer() {
    val request by EffectPlayer.inApp.collectAsState()
    val current = request ?: return
    EffectCanvas(current) { EffectPlayer.clearInApp(current.id) }
}

@Composable
fun EffectCanvas(request: EffectRequest, onFinished: () -> Unit = {}) {
    val progress = remember(request.id) { Animatable(0f) }
    val accent = FlatAccent
    val color = request.color ?: accent

    LaunchedEffect(request.id) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(request.durationMs, easing = LinearEasing))
        onFinished()
    }

    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        if (request.type == "glow") {
            drawGlow(p, color)
        } else {
            drawWave(p, color, request.origin)
        }
    }
}

private fun DrawScope.drawWave(progress: Float, color: Color, origin: String) {
    val center = when (origin) {
        "center" -> Offset(size.width / 2f, size.height / 2f)
        "bottom" -> Offset(size.width / 2f, size.height)
        else -> Offset(size.width / 2f, 0f)
    }
    val maxRadius = sqrt(size.width * size.width + size.height * size.height) * 1.05f
    val radius = (maxRadius * progress).coerceAtLeast(1f)
    val fade = (1f - progress).coerceIn(0f, 1f)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = 0.32f * fade),
                color.copy(alpha = 0.10f * fade),
                Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )

    repeat(3) { index ->
        val ring = radius - index * 26.dp.toPx()
        if (ring > 0f) {
            drawCircle(
                color = color.copy(alpha = fade * (0.45f - index * 0.12f)),
                radius = ring,
                center = center,
                style = Stroke(width = (3 - index).dp.toPx()),
            )
        }
    }
}

private fun DrawScope.drawGlow(progress: Float, color: Color) {
    val pulse = sin(progress * PI).toFloat().coerceIn(0f, 1f)
    if (pulse <= 0f) return

    val corner = CornerRadius(26.dp.toPx())
    val base = 1.dp.toPx()
    repeat(5) { index ->
        val inset = base + index * 6.dp.toPx()
        val alpha = pulse * (0.26f - index * 0.045f)
        val width = size.width - inset * 2f
        val height = size.height - inset * 2f
        if (alpha > 0f && width > 0f && height > 0f) {
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(inset, inset),
                size = Size(width, height),
                cornerRadius = corner,
                style = Stroke(width = (6 - index).dp.toPx()),
            )
        }
    }
}

private class EffectWindowHost(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var composeView: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var windowManager: WindowManager? = null
    private var current: EffectRequest? = null

    fun show(request: EffectRequest) {
        main.post {
            if (current != null) return@post
            val wm = context.getSystemService(WindowManager::class.java) ?: return@post
            val lifecycleOwner = OverlayLifecycleOwner().apply { onCreate() }
            current = request

            val view = ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
                )
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setContent {
                    OpenBridgeTheme(mode = EffectPlayer.themeMode, theme = EffectPlayer.theme) {
                        EffectCanvas(request) { hide(request.id) }
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                title = "openbridge-effect"
            }

            runCatching { wm.addView(view, params) }.onSuccess {
                windowManager = wm
                composeView = view
                owner = lifecycleOwner
            }.onFailure {
                current = null
                lifecycleOwner.onDestroy()
            }
        }
    }

    private fun hide(id: Int) {
        main.post {
            if (current?.id != id) return@post
            current = null
            val view = composeView ?: return@post
            composeView = null
            runCatching { windowManager?.removeViewImmediate(view) }
            windowManager = null
            owner?.onDestroy()
            owner = null
        }
    }
}
