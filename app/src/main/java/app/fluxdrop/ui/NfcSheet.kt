package app.fluxdrop.ui

import android.app.Activity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fluxdrop.util.NfcHelper
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcSheet(
    strings: AppStrings,
    onDismiss: () -> Unit,
    onPayloadReceived: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var successPayload by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (activity != null) {
            NfcHelper.startReaderMode(activity) { payload ->
                successPayload = payload
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (activity != null) {
                NfcHelper.stopReaderMode(activity)
            }
        }
    }

    LaunchedEffect(successPayload) {
        val payload = successPayload ?: return@LaunchedEffect
        delay(1100)
        onPayloadReceived(payload)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (successPayload != null) strings.nfcSuccess else strings.nfcTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = strings.nfcSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            // Радарная пульсирующая анимация
            Box(
                modifier = Modifier.size(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (successPayload == null) {
                    val transition = rememberInfiniteTransition(label = "nfc_waves")
                    val wave1 by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
                        label = "wave1",
                    )
                    val wave2 by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(2200, delayMillis = 700, easing = LinearEasing)),
                        label = "wave2",
                    )
                    val wave3 by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(2200, delayMillis = 1400, easing = LinearEasing)),
                        label = "wave3",
                    )

                    listOf(wave1, wave2, wave3).forEach { progress ->
                        val scale = 0.5f + (progress * 1.1f)
                        val alpha = ((1f - progress) * 0.7f).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .size(140.dp)
                                .scale(scale)
                                .clip(CircleShape)
                                .border(
                                    width = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                                    shape = CircleShape,
                                )
                        )
                    }
                }

                // Центральная иконка NFC
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            if (successPayload != null) Color(0xFF2E7D32)
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (successPayload != null) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(46.dp),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Nfc,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(46.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = if (successPayload != null) strings.nfcSuccess else strings.nfcReady,
                style = MaterialTheme.typography.labelLarge,
                color = if (successPayload != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.height(24.dp))

            FilledTonalButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(0.6f),
            ) {
                Text(strings.btnCancel)
            }
        }
    }
}
