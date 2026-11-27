package app.openbridge.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
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
        text = if (index == null) title.uppercase() else "$index — ${title.uppercase()}",
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
    Box(
        modifier = modifier
            .height(46.dp)
            .background(FlatAccent, FlatShape)
            .clip(FlatShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
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
    Box(
        modifier = modifier
            .height(42.dp)
            .background(
                if (IsMaterialControls) FlatSurfaceElevated else FlatSurface,
                FlatShape,
            )
            .flatBorder(FlatBorderActive)
            .clip(FlatShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
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
    val material = IsMaterialControls
    val width = if (material) 52.dp else 44.dp
    val height = if (material) 32.dp else 24.dp
    val inset = if (material) 4.dp else 3.dp
    val thumbSize = if (material) 24.dp else 18.dp
    val travel = width - inset * 2f - thumbSize
    val trackShape = if (material) CircleShape else RoundedCornerShape(FlatCornerRadius)
    val thumbShape = if (material) CircleShape else RoundedCornerShape(FlatCornerRadius)

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
    val shape = if (IsMaterialControls) RoundedCornerShape(4.dp) else FlatShape
    Box(
        modifier = modifier
            .size(22.dp)
            .background(if (checked) FlatAccent else Color.Transparent, shape)
            .flatBorder(
                color = if (checked) FlatAccent else if (enabled) FlatBorderActive else FlatBorder,
                shape = shape,
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
    val shape = if (IsMaterialControls) CircleShape else FlatShape
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
        text = text.uppercase(),
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
