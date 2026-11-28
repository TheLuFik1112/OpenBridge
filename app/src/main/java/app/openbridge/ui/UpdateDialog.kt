package app.openbridge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openbridge.domain.ReleaseInfo
import app.openbridge.text.AppStrings

@Composable
fun UpdateAvailableDialog(
    strings: AppStrings,
    release: ReleaseInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val target = release.apkUrl ?: release.pageUrl

    AlertDialog(
        shape = FlatShape,
        containerColor = FlatSurface,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = labelCase(strings.updateAvailableTitle),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FlatTextPrimary,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "${strings.versionLabel} ${release.version}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = FlatAccent,
                )
                if (release.publishedAt.isNotBlank()) {
                    Text(
                        text = "${strings.updateReleaseDate}: ${release.publishedAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = FlatTextSecondary,
                    )
                }
                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = release.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = FlatTextPrimary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (target.isNotBlank()) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    onDismiss()
                },
            ) {
                Text(strings.updateDownload, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.updateLater) }
        },
    )
}

@Composable
fun WhatsNewDialog(
    strings: AppStrings,
    release: ReleaseInfo,
    onClose: () -> Unit,
) {
    AlertDialog(
        shape = FlatShape,
        containerColor = FlatSurface,
        onDismissRequest = onClose,
        title = {
            Text(
                text = strings.whatsNewTitle(release.version),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FlatTextPrimary,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = release.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = FlatTextPrimary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(strings.updateClose, fontWeight = FontWeight.Bold)
            }
        },
    )
}
