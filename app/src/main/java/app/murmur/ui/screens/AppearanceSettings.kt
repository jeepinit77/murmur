package app.murmur.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.container
import app.murmur.ui.theme.Palette
import app.murmur.ui.theme.ThemeMode
import app.murmur.ui.theme.isDark
import app.murmur.ui.theme.wallpaperColorsAvailable

/** Theme, Black background, Color and reader text settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSettings() {
    val settings = LocalContext.current.container.settings
    val mode by settings.themeMode.collectAsStateWithLifecycle()
    val palette by settings.palette.collectAsStateWithLifecycle()
    val black by settings.blackBackground.collectAsStateWithLifecycle()
    val dark = mode.isDark()

    SettingsGroup("Theme") {
        ThemeMode.entries.forEach { m ->
            RadioRow(m.label, mode == m) { settings.setThemeMode(m) }
        }
        SwitchRow(
            "Black background",
            "Use black instead of dark gray in the dark theme",
            black,
            settings::setBlackBackground,
        )
    }

    SettingsGroup("Color") {
        FlowRow(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Palette.entries
                .filter { it != Palette.WALLPAPER || wallpaperColorsAvailable }
                .forEach { p ->
                    Swatch(
                        palette = p,
                        color = if (p == Palette.WALLPAPER) MaterialTheme.colorScheme.surfaceContainerHighest else p.swatch(dark),
                        selected = palette == p,
                        onClick = { settings.setPalette(p) },
                    )
                }
        }
    }
    SettingsGroup("Reader") {
        ReaderTextControls()
    }
}

@Composable
private fun Swatch(palette: Palette, color: Color, selected: Boolean, onClick: () -> Unit) {
    val onColor = if (color.luminance() > 0.4f) Color.Black else Color.White
    Column(
        Modifier
            .width(76.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected -> Icon(Icons.Default.Check, null, tint = if (palette == Palette.WALLPAPER) MaterialTheme.colorScheme.onSurface else onColor)
                palette == Palette.WALLPAPER -> Icon(Icons.Default.Wallpaper, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            palette.label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
