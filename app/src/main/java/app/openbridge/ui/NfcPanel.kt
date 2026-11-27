package app.openbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openbridge.domain.NfcPhase
import app.openbridge.domain.NfcState
import app.openbridge.text.AppStrings

@Composable
fun NfcPanel(
    state: NfcState,
    strings: AppStrings,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    val title: String = when (phase) {
        NfcPhase.CONNECTING -> strings.nfcConnecting(state.peerName)
        NfcPhase.RECEIVING -> strings.nfcReceiving
        NfcPhase.TRANSFER -> strings.nfcSending
        NfcPhase.DONE -> strings.nfcReceived
        NfcPhase.SENT -> strings.nfcSent
        NfcPhase.ERROR -> if (state.sending) strings.nfcSendFailed else strings.nfcReceiveFailed
        NfcPhase.READY -> if (state.hasItems) strings.nfcHoldToSend else strings.nfcNeedItems
    }
    val subtitle: String? = when (phase) {
        NfcPhase.DONE -> when {
            state.files > 0 -> strings.nfcReceivedCount(state.files)
            state.texts > 0 -> strings.nfcTextCopied
            else -> null
        }
        NfcPhase.READY -> if (state.hasItems) strings.nfcSwipeUpHint else strings.nfcEmptyPackage
        NfcPhase.ERROR -> null
        else -> ""
    }
    val showProgress = phase == NfcPhase.RECEIVING || phase == NfcPhase.TRANSFER
    val fraction = state.fraction.coerceIn(0f, 1f)

    var dragTotal by remember { mutableStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { dragTotal = 0f },
                    onDragCancel = { dragTotal = 0f },
                ) { _, drag ->
                    dragTotal += drag
                    if (dragTotal < -48f) {
                        dragTotal = 0f
                        onCollapse()
                    }
                }
            }
            .background(FlatAccentDimBg, FlatShape)
            .flatBorder()
            .padding(start = 10.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(FlatSurfaceElevated, FlatShape)
                    .flatBorder(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Nfc,
                    contentDescription = null,
                    tint = FlatAccent,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = FlatAccent,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlatTextSecondary,
                    maxLines = 2,
                )
            }
        }

        if (showProgress) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(FlatSurfaceElevated, FlatShape)
                        .flatBorder(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(FlatAccent, FlatShape),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = FlatAccent,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        FlatSecondaryButton(
            text = strings.nfcHide,
            onClick = onCollapse,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
