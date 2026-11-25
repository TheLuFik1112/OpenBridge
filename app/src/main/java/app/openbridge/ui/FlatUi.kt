package app.openbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ── Flat technical UI primitives ────────────────────────────────────────────
// Все карточки — плоские, без скруглений и теней, с 1.dp рамкой.

@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(0.dp))
            .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp))
            .padding(16.dp),
    ) {
        content()
    }
}

@Composable
fun SectionHeader(
    index: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "// $index — ${title.uppercase()}",
        style = MaterialTheme.typography.labelMedium,
        color = FlatAccent,
        fontFamily = FontFamily.Monospace,
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
            .background(FlatAccent, RoundedCornerShape(0.dp))
            .clip(RoundedCornerShape(0.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0A0A0A),
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
            .background(FlatSurface, RoundedCornerShape(0.dp))
            .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorderActive), RoundedCornerShape(0.dp))
            .clip(RoundedCornerShape(0.dp))
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
    Box(
        modifier = modifier
            .width(52.dp)
            .height(24.dp)
            .background(if (checked) FlatAccent else FlatSurfaceElevated, RoundedCornerShape(0.dp))
            .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp))
            .clip(RoundedCornerShape(0.dp))
            .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .offset(x = if (checked) 28.dp else 2.dp)
                .width(18.dp)
                .height(18.dp)
                .background(if (checked) Color(0xFF0A0A0A) else FlatTextMuted, RoundedCornerShape(0.dp)),
        )
    }
}

@Composable
fun FlatCheckbox(
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(22.dp)
            .background(if (checked) FlatAccent else Color.Transparent, RoundedCornerShape(0.dp))
            .border(
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (checked) FlatAccent else if (enabled) FlatBorderActive else FlatBorder,
                ),
                RoundedCornerShape(0.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = Color(0xFF0A0A0A),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Плоский индикатор прогресса: рамка + заливка без градиентов и скруглений. */
@Composable
fun FlatProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Int = 6,
) {
    val safe = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(FlatSurface, RoundedCornerShape(0.dp))
            .border(androidx.compose.foundation.BorderStroke(1.dp, FlatBorder), RoundedCornerShape(0.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(safe)
                .height(height.dp)
                .background(FlatAccent, RoundedCornerShape(0.dp)),
        )
    }
}

@Composable
fun AccentCallout(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(FlatAccentDimBg, RoundedCornerShape(0.dp))
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(18.dp)
                .background(FlatAccent, RoundedCornerShape(0.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = FlatTextSecondary,
        )
    }
}
