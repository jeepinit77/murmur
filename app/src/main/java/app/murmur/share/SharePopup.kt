package app.murmur.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Parts shared by the popups shown over other apps when sharing to Murmur.

/** One choice. Tapping it acts immediately; the chevron shows it isn't a selection. */
@Composable
internal fun OptionRow(
    icon: ImageVector,
    label: String,
    description: String,
    background: Color,
    iconBackground: Color,
    iconTint: Color,
    iconShape: Shape,
    colors: PopupColors,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, iconBackground, iconTint, iconShape, 52)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.text)
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.secondaryText,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
internal fun IconTile(icon: ImageVector, background: Color, tint: Color, shape: Shape, sizeDp: Int) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((sizeDp * 0.54f).dp))
    }
}

/** The popup's colors, from the app's theme and color settings. */
internal class PopupColors(
    val card: Color,
    val group: Color,
    val outline: Color,
    val text: Color,
    val secondaryText: Color,
    val closeButton: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val gold: Color,
    val onGold: Color,
    val goldSoft: Color,
    val onGoldSoft: Color,
) {
    companion object {
        fun from(scheme: ColorScheme) = PopupColors(
            card = scheme.surfaceContainerHigh,
            group = scheme.surfaceContainerLowest,
            outline = scheme.outlineVariant,
            text = scheme.onSurface,
            secondaryText = scheme.onSurfaceVariant,
            closeButton = scheme.surfaceContainerHighest,
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            accentSoft = scheme.primaryContainer.copy(alpha = 0.45f),
            gold = scheme.tertiaryContainer,
            onGold = scheme.onTertiaryContainer,
            goldSoft = scheme.tertiaryContainer,
            onGoldSoft = scheme.onTertiaryContainer,
        )
    }
}
