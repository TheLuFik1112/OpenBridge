package app.openbridge.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbridge.plugins.ScriptPlugin
import app.openbridge.text.AppStrings

@Composable
fun PluginsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val strings = LocalStrings.current
    val plugins by viewModel.scriptPlugins.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importScriptPlugin(it) } }

    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = strings.pluginsTitle,
                subtitle = strings.pluginsSubtitle,
                strings = strings,
                onBack = onBack,
            )
        }

        item(key = "import") {
            FlatPrimaryButton(
                text = strings.pluginsImport,
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (plugins.isEmpty()) {
            item(key = "empty") {
                FlatCard {
                    Text(
                        text = strings.pluginsEmpty,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlatTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    )
                }
            }
        } else {
            items(plugins, key = { it.manifest.id }) { plugin ->
                PluginCard(
                    plugin = plugin,
                    strings = strings,
                    onEnabled = { enabled ->
                        viewModel.setScriptPluginEnabled(plugin.manifest.id, enabled)
                    },
                    onDelete = { viewModel.deleteScriptPlugin(plugin.manifest.id) },
                )
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: ScriptPlugin,
    strings: AppStrings,
    onEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val manifest = plugin.manifest

    FlatCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = manifest.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = FlatTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = strings.pluginAuthorVersion(manifest.author, manifest.version),
                    style = MaterialTheme.typography.labelSmall,
                    color = FlatTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (manifest.description.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = manifest.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlatTextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.End) {
                FlatToggle(
                    checked = plugin.enabled,
                    onCheckedChange = onEnabled,
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = strings.pluginsDelete,
                        tint = FlatTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
