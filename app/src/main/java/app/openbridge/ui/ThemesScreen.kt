package app.openbridge.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbridge.text.AppStrings
import app.openbridge.theme.BuiltInThemes
import app.openbridge.theme.ThemeConfig

@Composable
fun ThemesScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val strings = LocalStrings.current
    val themes by viewModel.themes.collectAsState()
    val activeId by viewModel.themePluginId.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importTheme(it) } }

    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = strings.themesTitle,
                subtitle = strings.themesSubtitle,
                strings = strings,
                onBack = onBack,
            )
        }

        item(key = "import") {
            FlatPrimaryButton(
                text = strings.themesImport,
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "hint") {
            Text(
                text = strings.themesTapToApply,
                style = MaterialTheme.typography.bodySmall,
                color = FlatTextSecondary,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }

        items(themes, key = { it.id }) { theme ->
            ThemeCard(
                theme = theme,
                active = theme.id == activeId,
                deletable = !BuiltInThemes.isBuiltIn(theme.id),
                strings = strings,
                onSelect = { viewModel.setThemePlugin(theme.id) },
                onDelete = { viewModel.deleteTheme(theme.id) },
            )
        }
    }
}

@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String,
    strings: AppStrings,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = strings.btnCancel,
                tint = FlatTextPrimary,
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = labelCase(title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = FlatTextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = FlatTextSecondary,
            )
        }
    }
}

@Composable
private fun ThemeCard(
    theme: ThemeConfig,
    active: Boolean,
    deletable: Boolean,
    strings: AppStrings,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    FlatCard(
        modifier = Modifier
            .clip(FlatShape)
            .clickable(onClick = onSelect),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = theme.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = FlatTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = strings.themeAuthorVersion(theme.author, theme.version),
                    style = MaterialTheme.typography.labelSmall,
                    color = FlatTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Box(modifier = Modifier.padding(top = 8.dp)) {
                        ThemeBadge(label = strings.themesActive, highlighted = true)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            if (active) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = strings.themesActive,
                    tint = FlatAccent,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (deletable) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = strings.themesDelete,
                        tint = FlatTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeBadge(label: String, highlighted: Boolean) {
    Box(
        modifier = Modifier
            .clip(FlatBadgeShape)
            .flatBorder(if (highlighted) FlatAccent else FlatBorder, FlatBadgeShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = labelCase(label),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (highlighted) FlatAccent else FlatTextSecondary,
            maxLines = 1,
        )
    }
}
