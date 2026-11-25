package app.openbridge.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.openbridge.core.IncomingStage
import app.openbridge.core.TransferOffer
import app.openbridge.ui.OpenBridgeTheme
import app.openbridge.ui.IncomingSheetContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Шторка поверх чужих приложений.
 *
 * ВАЖНО: M3 ModalBottomSheet нельзя использовать внутри overlay — она открывается в
 * отдельном Dialog-окне, которому не назначается TYPE_APPLICATION_OVERLAY.
 * Поэтому здесь собственный scrim + slide-in, а контент общий с Activity-вариантом.
 */
class OverlaySheetHost(
    private val context: Context,
    private val onAccept: (transferId: String, always: Boolean) -> Unit,
    private val onDecline: (transferId: String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private var composeView: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var windowManager: WindowManager? = null
    private var current: TransferOffer? = null
    private val stageState = mutableStateOf<IncomingStage>(IncomingStage.Awaiting)

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show(offer: TransferOffer) {
        main.post {
            if (current != null) return@post
            if (!canShow()) return@post

            current = offer
            stageState.value = IncomingStage.Awaiting

            val wm = context.getSystemService(WindowManager::class.java) ?: return@post
            val lifecycleOwner = OverlayLifecycleOwner().apply { onCreate() }

            val view = ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
                )
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setContent {
                    OpenBridgeTheme {
                        SheetOverlay(
                            offer = offer,
                            stage = stageState.value,
                            onClose = ::close,
                            onAccept = { always ->
                                stageState.value = IncomingStage.Connecting
                                onAccept(offer.transferId, always)
                            },
                            onDecline = {
                                onDecline(offer.transferId)
                                close()
                            },
                        )
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
                title = "openbridge-incoming"
            }

            runCatching { wm.addView(view, params) }.onSuccess {
                windowManager = wm
                composeView = view
                owner = lifecycleOwner
            }.onFailure {
                current = null
                owner?.onDestroy()
                owner = null
            }
        }
    }

    fun updateStage(stage: IncomingStage) {
        main.post { stageState.value = stage }
    }

    fun close() {
        main.post {
            val view = composeView ?: return@post
            composeView = null
            current = null
            runCatching { windowManager?.removeViewImmediate(view) }
            windowManager = null
            owner?.onDestroy()
            owner = null
        }
    }
}

@Composable
private fun SheetOverlay(
    offer: TransferOffer,
    stage: IncomingStage,
    onClose: () -> Unit,
    onAccept: (Boolean) -> Unit,
    onDecline: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { visible = true }

    LaunchedEffect(stage) {
        if (stage is IncomingStage.Done || stage is IncomingStage.Failed) {
            delay(900)
            visible = false
            delay(220)
            onClose()
        }
    }

    fun animateOut(then: () -> Unit) {
        scope.launch {
            visible = false
            delay(220)
            then()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(260)) { it },
            exit = slideOutVertically(tween(200)) { it },
        ) {
            val dragState = rememberDraggableState { delta ->
                dragOffset = (dragOffset + delta).coerceIn(0f, 600f)
            }
            Box(
                Modifier
                    .offset { IntOffset(0, dragOffset.roundToInt()) }
                    .wrapContentHeight()
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = {
                            if (dragOffset > 120.dp.value) animateOut(onDecline) else dragOffset = 0f
                        },
                    ),
            ) {
                IncomingSheetContent(
                    offer = offer,
                    stage = stage,
                    onAccept = onAccept,
                    onDecline = { animateOut(onDecline) },
                )
            }
        }
    }
}

private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun onCreate() {
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
