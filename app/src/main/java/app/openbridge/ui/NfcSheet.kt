package app.openbridge.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Нижняя NFC-шторка: только значок и одна строка внизу.
 * Никаких переключателей «Поделиться / Принять» — приём и отправка включены всегда,
 * поэтому окно лишь показывает, что происходит сейчас.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcSheet(
    state: NfcState,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NfcGlyph(phase = state.phase, fraction = state.fraction)

            Spacer(Modifier.height(22.dp))

            Crossfade(
                targetState = headline(state, strings),
                label = "nfc-headline",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }

            val hint = hint(state, strings)
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (state.fraction > 0f &&
                (state.phase == NfcPhase.RECEIVING || state.phase == NfcPhase.TRANSFER)
            ) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "${(state.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun NfcGlyph(phase: NfcPhase, fraction: Float) {
    val colors = MaterialTheme.colorScheme
    // Пульс только в ожидании: во время передачи он бы отвлекал.
    val pulse = if (phase == NfcPhase.READY || phase == NfcPhase.CONNECTING ||
        phase == NfcPhase.RECEIVING
    ) {
        val transition = rememberInfiniteTransition(label = "nfc-pulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "nfc-scale",
        )
        value
    } else {
        1f
    }

    Box(modifier = Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(168.dp)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                }
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.10f)),
        )
        Box(
            modifier = Modifier
                .size(124.dp)
                .clip(CircleShape)
                .background(colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                NfcPhase.CONNECTING -> CircularProgressIndicator(
                    modifier = Modifier.size(84.dp),
                    strokeWidth = 5.dp,
                    color = colors.primary,
                )

                NfcPhase.RECEIVING, NfcPhase.TRANSFER ->
                    // Пока отправитель не назвал размер пакета, честнее крутить
                    // бесконечный индикатор, чем показывать замершие 0 %.
                    if (fraction > 0f) {
                        CircularProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.size(84.dp),
                            strokeWidth = 5.dp,
                            trackColor = colors.primary.copy(alpha = 0.16f),
                            color = colors.primary,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(84.dp),
                            strokeWidth = 5.dp,
                            color = colors.primary,
                        )
                    }

                NfcPhase.DONE, NfcPhase.SENT -> Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = colors.tertiary,
                    modifier = Modifier.size(64.dp),
                )

                NfcPhase.ERROR -> Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(64.dp),
                )

                NfcPhase.READY -> Icon(
                    Icons.Rounded.Nfc,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(68.dp),
                )
            }
        }
    }
}

private fun headline(state: NfcState, strings: AppStrings): String = when (state.phase) {
    NfcPhase.CONNECTING -> strings.nfcConnecting(state.peerName)
    NfcPhase.RECEIVING -> strings.nfcReceiving
    NfcPhase.TRANSFER -> strings.nfcSending
    NfcPhase.DONE -> strings.nfcReceived
    NfcPhase.SENT -> strings.nfcSent
    NfcPhase.ERROR -> if (state.sending) strings.nfcSendFailed else strings.nfcReceiveFailed
    NfcPhase.READY -> if (state.hasItems) strings.nfcHoldToSend else strings.nfcNeedItems
}

private fun hint(state: NfcState, strings: AppStrings): String? = when (state.phase) {
    NfcPhase.DONE -> when {
        state.files > 0 -> strings.nfcReceivedCount(state.files)
        state.texts > 0 -> strings.nfcTextCopied
        else -> null
    }

    else -> null
}
