package app.openbridge.overlay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.openbridge.OpenBridgeApp
import app.openbridge.core.IncomingStage
import app.openbridge.ui.OpenBridgeTheme
import app.openbridge.ui.FlatSurface
import app.openbridge.ui.IncomingSheetContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class IncomingRequestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val graph = (application as OpenBridgeApp).graph
        val promptId = intent.getStringExtra(EXTRA_PROMPT_ID)
        val prompt = promptId?.let { graph.presenter.prompt(it) }
        if (promptId == null || prompt == null) {
            finish()
            return
        }

        setContent {
            OpenBridgeTheme {
                val stage by if (prompt.trackProgress) {
                    graph.incoming.stage(promptId).collectAsState()
                } else {
                    remember { mutableStateOf<IncomingStage>(IncomingStage.Awaiting) }
                }
                val sheetState = rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                )
                val scope = rememberCoroutineScope()

                fun close() {
                    scope.launch {
                        runCatching { sheetState.hide() }
                        finish()
                        overridePendingTransition(0, 0)
                    }
                }

                LaunchedEffect(stage) {
                    when (stage) {
                        IncomingStage.Connecting,
                        is IncomingStage.Transferring,
                        IncomingStage.Done,
                        is IncomingStage.Failed -> close()
                        IncomingStage.Awaiting -> Unit
                    }
                }

                ModalBottomSheet(
                    onDismissRequest = {
                        graph.presenter.decline(promptId)
                        close()
                    },
                    sheetState = sheetState,
                    containerColor = FlatSurface,
                    shape = RoundedCornerShape(0.dp),
                    dragHandle = null,
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 0.dp,
                    scrimColor = Color.Black.copy(alpha = 0.72f),
                ) {
                    IncomingSheetContent(
                        prompt = prompt,
                        stage = stage,
                        onAccept = { always ->
                            graph.presenter.accept(promptId, always)
                            if (!prompt.trackProgress) close()
                        },
                        onDecline = {
                            graph.presenter.decline(promptId)
                            close()
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_PROMPT_ID = "prompt_id"

        fun intent(context: Context, promptId: String): Intent =
            Intent(context, IncomingRequestActivity::class.java)
                .putExtra(EXTRA_PROMPT_ID, promptId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
