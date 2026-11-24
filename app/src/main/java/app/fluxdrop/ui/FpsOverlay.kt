package app.fluxdrop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Диагностический счётчик кадров в правом верхнем углу.
 *
 * Намеренно сделан на [withFrameNanos]: он просыпается только когда система
 * действительно рисует кадр, поэтому показывает реальную частоту отрисовки,
 * а не частоту таймера. Пока выключен — ни одного лишнего кадра не стоит.
 */
@Composable
fun FpsOverlay(enabled: Boolean) {
    if (!enabled) return

    var fps by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var frames = 0
        var windowStart = 0L
        while (true) {
            withFrameNanos { now ->
                if (windowStart == 0L) windowStart = now
                frames++
                val elapsed = now - windowStart
                if (elapsed >= 1_000_000_000L) {
                    fps = ((frames * 1_000_000_000L) / elapsed).toInt()
                    frames = 0
                    windowStart = now
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopEnd,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.62f),
            contentColor = Color(0xFF7CFC9A),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .statusBarsPadding()
                .padding(end = 8.dp, top = 8.dp),
        ) {
            Text(
                text = "$fps fps",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
