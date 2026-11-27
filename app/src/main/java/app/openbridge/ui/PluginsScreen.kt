package app.openbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Placeholder for the upcoming script/extension engine. Themes live on their own
 * screen, so this one only has to be ready to list plugins later.
 */
@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val strings = LocalStrings.current

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        ScreenHeader(
            title = strings.pluginsTitle,
            subtitle = strings.pluginsSubtitle,
            strings = strings,
            onBack = onBack,
        )

        Spacer(Modifier.height(16.dp))

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
}
