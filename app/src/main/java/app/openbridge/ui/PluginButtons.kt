package app.openbridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.openbridge.plugins.PluginUiRegistry
import app.openbridge.theme.BUTTON_OUTLINED
import app.openbridge.theme.BUTTON_TONAL

class PluginUiActions(
    val onButtonClick: (buttonId: String, pluginId: String?) -> Unit,
)

val LocalPluginUi = staticCompositionLocalOf<PluginUiActions?> { null }

enum class OBButtonLook { Solid, Tile, Text }

@Composable
fun OBButton(
    id: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: String? = null,
    icon: ImageVector? = null,
    look: OBButtonLook = OBButtonLook.Solid,
    height: Dp = if (look == OBButtonLook.Tile) 56.dp else 46.dp,
) {
    val over = PluginUiRegistry.overrideFor(id)
    if (over?.hidden == true) return
    val host = LocalPluginUi.current
    val compact = over != null &&
        (over.style != null || over.cornerRadiusDp != null) &&
        look == OBButtonLook.Text

    PluginButtonFace(
        text = over?.text ?: text,
        style = over?.style ?: style,
        color = over?.color,
        cornerRadiusDp = over?.cornerRadiusDp,
        icon = icon,
        look = if (compact) OBButtonLook.Solid else look,
        height = if (compact) 32.dp else height,
        enabled = enabled,
        modifier = modifier,
        onClick = {
            if (over != null && over.onClick) host?.onButtonClick(id, over.pluginId)
            onClick()
        },
    )
}

@Composable
fun PluginSlot(slotId: String, modifier: Modifier = Modifier) {
    val buttons = PluginUiRegistry.buttons(slotId)
    if (buttons.isEmpty()) return
    val host = LocalPluginUi.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        buttons.forEach { button ->
            PluginButtonFace(
                text = button.text,
                style = button.style,
                color = button.color,
                cornerRadiusDp = button.cornerRadiusDp,
                icon = null,
                look = OBButtonLook.Solid,
                height = 42.dp,
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (button.onClick) host?.onButtonClick(button.id, button.pluginId)
                },
            )
        }
    }
}

@Composable
private fun PluginButtonFace(
    text: String,
    style: String?,
    color: Color?,
    cornerRadiusDp: Float?,
    icon: ImageVector?,
    look: OBButtonLook,
    height: Dp,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val resolvedStyle = style ?: LocalThemeConfig.current.components.buttonStyle
    val accent = color ?: FlatAccent
    val tile = look == OBButtonLook.Tile && style == null
    val shape = when {
        cornerRadiusDp != null -> RoundedCornerShape(cornerRadiusDp.dp)
        tile -> FlatShape
        else -> FlatButtonShape
    }

    if (look == OBButtonLook.Text) {
        Text(
            text = labelCase(text),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (enabled) accent else FlatTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )
        return
    }

    val background = when {
        tile -> FlatSurfaceElevated
        resolvedStyle == BUTTON_TONAL -> accent.copy(alpha = 0.16f)
        resolvedStyle == BUTTON_OUTLINED -> Color.Transparent
        else -> accent
    }
    val foreground = when {
        tile -> color ?: FlatTextPrimary
        resolvedStyle == BUTTON_TONAL || resolvedStyle == BUTTON_OUTLINED -> accent
        else -> MaterialTheme.colorScheme.onPrimary
    }
    val outline = when {
        tile -> maxOf(1.dp, FlatBorderWidth)
        resolvedStyle == BUTTON_OUTLINED -> maxOf(1.dp, FlatBorderWidth)
        else -> 0.dp
    }
    val outlineColor = if (tile && color == null) FlatBorder else accent

    Box(
        modifier = modifier
            .height(height)
            .background(background, shape)
            .then(
                if (outline > 0.dp) {
                    Modifier.border(BorderStroke(outline, outlineColor), shape)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (tile) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = labelCase(text),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = labelCase(text),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
