package app.openbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openbridge.text.AppStrings
import app.openbridge.theme.NAV_FLOATING
import app.openbridge.theme.NAV_MINIMAL
import app.openbridge.theme.NAV_PILL
import app.openbridge.theme.NAV_TOP_LINE

internal data class Tab(
    val label: (AppStrings) -> String,
    val icon: ImageVector,
)

internal val AppTabs = listOf(
    Tab(label = { it.tabSend }, icon = Icons.Rounded.Send),
    Tab(label = { it.tabHistory }, icon = Icons.Rounded.History),
    Tab(label = { it.tabSettings }, icon = Icons.Rounded.Settings),
)

/**
 * Bottom navigation driven by `components.navBarStyle`:
 * `top_line`, `pill`, `floating` or `minimal`.
 */
@Composable
internal fun AppBottomBar(
    tabs: List<Tab>,
    selected: Int,
    strings: AppStrings,
    onSelect: (Int) -> Unit,
) {
    val style = LocalThemeConfig.current.components.navBarStyle
    if (style == NAV_FLOATING) {
        FloatingBar(tabs, selected, strings, onSelect)
    } else {
        StandardBar(style, tabs, selected, strings, onSelect)
    }
}

@Composable
private fun StandardBar(
    style: String,
    tabs: List<Tab>,
    selected: Int,
    strings: AppStrings,
    onSelect: (Int) -> Unit,
) {
    val minimal = style == NAV_MINIMAL
    val pill = style == NAV_PILL
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlatBackground),
    ) {
        if (!minimal && FlatBorderWidth > 0.dp) {
            HorizontalDivider(thickness = FlatBorderWidth, color = FlatBorder)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (pill) 72.dp else 64.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = selected == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when {
                        pill -> {
                            Box(
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(30.dp)
                                    .background(
                                        if (isSelected) FlatAccentDimBg else Color.Transparent,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label(strings),
                                    tint = if (isSelected) FlatAccent else FlatTextSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            NavLabel(tab, strings, isSelected)
                        }

                        style == NAV_TOP_LINE -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(if (isSelected) FlatAccent else Color.Transparent),
                            )
                            Spacer(Modifier.height(8.dp))
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label(strings),
                                tint = if (isSelected) FlatAccent else FlatTextMuted,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                            NavLabel(tab, strings, isSelected)
                        }

                        else -> {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label(strings),
                                tint = if (isSelected) FlatAccent else FlatTextSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            NavLabel(tab, strings, isSelected)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingBar(
    tabs: List<Tab>,
    selected: Int,
    strings: AppStrings,
    onSelect: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FlatBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(maxOf(FlatCardElevation, 2.dp), FlatShape)
                .background(FlatSurface, FlatShape)
                .flatBorder(width = maxOf(1.dp, FlatBorderWidth))
                .clip(FlatShape)
                .height(62.dp)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = selected == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(28.dp)
                            .background(
                                if (isSelected) FlatAccentDimBg else Color.Transparent,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label(strings),
                            tint = if (isSelected) FlatAccent else FlatTextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    NavLabel(tab, strings, isSelected)
                }
            }
        }
    }
}

@Composable
private fun NavLabel(tab: Tab, strings: AppStrings, selected: Boolean) {
    Text(
        text = labelCase(tab.label(strings)),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (selected) FlatTextPrimary else FlatTextSecondary,
        maxLines = 1,
    )
}
