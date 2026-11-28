package app.openbridge.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openbridge.theme.BUTTON_OUTLINED
import app.openbridge.theme.BUTTON_TONAL
import app.openbridge.theme.SWITCH_FLAT
import app.openbridge.theme.SWITCH_IOS

@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    background: Color = FlatSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val elevation = FlatCardElevation
    Column(
        modifier = modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, FlatShape, clip = false) else Modifier)
            .fillMaxWidth()
            .background(background, FlatShape)
            .flatBorder()
            .padding(16.dp),
    ) {
        content()
    }
}

@Composable
fun SectionHeader(
    index: String? = null,
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (index == null) labelCase(title) else "$index — ${labelCase(title)}",
        style = MaterialTheme.typography.labelMedium,
        color = FlatTextSecondary,
        modifier = modifier.padding(top = 8.dp, bottom = 10.dp),
    )
}

@Composable
fun FlatPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val style = LocalThemeConfig.current.components.buttonStyle
    val shape = FlatButtonShape
    val background = when (style) {
        BUTTON_TONAL -> FlatAccentDimBg
        BUTTON_OUTLINED -> Color.Transparent
        else -> FlatAccent
    }
    val foreground = when (style) {
        BUTTON_TONAL, BUTTON_OUTLINED -> FlatAccent
        else -> MaterialTheme.colorScheme.onPrimary
    }
    val outline = if (style == BUTTON_OUTLINED) {
        maxOf(1.dp, FlatBorderWidth)
    } else {
        0.dp
    }

    Box(
        modifier = modifier
            .height(46.dp)
            .background(background, shape)
            .then(
                if (outline > 0.dp) {
                    Modifier.border(BorderStroke(outline, FlatAccent), shape)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = labelCase(text),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = foreground,
        )
    }
}

@Composable
fun FlatSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = FlatButtonShape
    Box(
        modifier = modifier
            .height(42.dp)
            .background(FlatSurfaceElevated, shape)
            .flatBorder(FlatBorderActive, shape, width = maxOf(1.dp, FlatBorderWidth))
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = labelCase(text),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = FlatTextPrimary,
        )
    }
}

@Composable
fun FlatToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalThemeConfig.current.components.switchStyle
    val ios = style == SWITCH_IOS
    val flat = style == SWITCH_FLAT

    val width = if (flat) 44.dp else if (ios) 46.dp else 52.dp
    val height = if (flat) 24.dp else if (ios) 28.dp else 32.dp
    val inset = if (ios) 3.dp else if (flat) 3.dp else 4.dp
    val thumbSize = if (flat) 18.dp else if (ios) 22.dp else 24.dp
    val travel = width - inset * 2f - thumbSize
    val trackShape = if (flat) FlatBadgeShape else CircleShape
    val thumbShape = if (flat) RoundedCornerShape(FlatCornerRadius) else CircleShape

    val thumbOffset = animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        label = "flatToggleThumb",
    )

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .background(if (checked) FlatAccent else FlatSurfaceElevated, trackShape)
            .flatBorder(shape = trackShape)
            .padding(inset)
            .clip(trackShape)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(thumbSize)
                .graphicsLayer { translationX = thumbOffset.value.toPx() }
                .background(
                    if (checked) MaterialTheme.colorScheme.onPrimary else FlatTextMuted,
                    thumbShape,
                ),
        )
    }
}

@Composable
fun FlatCheckbox(
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val shape = FlatBadgeShape
    Box(
        modifier = modifier
            .size(22.dp)
            .background(if (checked) FlatAccent else Color.Transparent, shape)
            .flatBorder(
                color = if (checked) FlatAccent else if (enabled) FlatBorderActive else FlatBorder,
                shape = shape,
                width = maxOf(1.dp, FlatBorderWidth),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun FlatProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Int = 6,
) {
    val safe = fraction.coerceIn(0f, 1f)
    val shape = FlatButtonShape
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(FlatSurface, shape)
            .flatBorder(shape = shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(safe)
                .height(height.dp)
                .background(FlatAccent, shape),
        )
    }
}

@Composable
fun FlatTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Text(
        text = labelCase(text),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = if (enabled) FlatAccent else FlatTextMuted,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

@Composable
fun AccentCallout(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(FlatAccentDimBg, FlatShape)
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(18.dp)
                .background(FlatAccent, RoundedCornerShape(1.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = FlatTextSecondary,
        )
    }
}
