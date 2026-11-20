package app.murmur.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Light, dark or follow the system setting. */
enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * The app's color choices. [WALLPAPER] uses Android's colors from the wallpaper (Android 12+).
 * Hue and chroma are OKLCH values for the primary color; the accent, when given, sets the
 * tertiary color instead of a hue shifted from the primary.
 */
enum class Palette(
    val label: String,
    internal val hue: Double,
    internal val chroma: Double,
    internal val accentHue: Double? = null,
    internal val accentChroma: Double? = null,
    internal val neutralChroma: Double = 0.010,
) {
    /** The launcher icon's navy, with its yellow as the accent; surfaces lean navy. */
    NAVY("Navy", 266.0, 0.09, accentHue = 90.0, accentChroma = 0.13, neutralChroma = 0.022),
    VIOLET("Violet", 293.0, 0.20),
    BLUE("Blue", 258.0, 0.16),
    TEAL("Teal", 195.0, 0.11),
    GREEN("Green", 145.0, 0.14),
    ORANGE("Orange", 48.0, 0.16),
    ROSE("Rose", 5.0, 0.17),
    WALLPAPER("Wallpaper", 0.0, 0.0),
    ;

    /** The swatch shown in Settings. */
    fun swatch(dark: Boolean): Color = Color(TonalPalette(hue, chroma).tone(if (dark) 80 else 40))

    /** The play button and reading highlight: the accent when the palette has one, else the primary color. */
    fun accentColors(scheme: ColorScheme, dark: Boolean): AccentColors {
        if (accentHue == null || this == WALLPAPER) {
            return AccentColors(scheme.primary, scheme.onPrimary, scheme.primaryContainer.copy(alpha = 0.75f))
        }
        val accent = TonalPalette(accentHue, accentChroma ?: chroma)
        // Like the icon: a bright accent with the primary's darkest tone on it.
        return AccentColors(
            button = Color(accent.tone(85)),
            onButton = Color(TonalPalette(hue, chroma).tone(20)),
            highlight = Color(accent.tone(if (dark) 35 else 90)).copy(alpha = if (dark) 0.85f else 0.9f),
        )
    }
}

/** Colors for the play button and the sentence being read. */
data class AccentColors(val button: Color, val onButton: Color, val highlight: Color)

private class Tonals(p: Palette) {
    val primary = TonalPalette(p.hue, p.chroma)
    /** Light tones of saturated hues (teal, green) glare at full chroma, so containers use less. */
    val soft = TonalPalette(p.hue, minOf(p.chroma, 0.07))
    val secondary = TonalPalette(p.hue, p.chroma * 0.35)
    val tertiary = TonalPalette(p.accentHue ?: ((p.hue + 60) % 360), p.accentChroma ?: (p.chroma * 0.6))
    val neutral = TonalPalette(p.hue, p.neutralChroma)
    val neutralVariant = TonalPalette(p.hue, maxOf(0.022, p.neutralChroma * 1.5))
    val error = TonalPalette(27.0, 0.19)
}

private fun Int.c() = Color(this)

/** A full Material 3 color scheme for a preset [palette]. */
fun schemeFor(palette: Palette, dark: Boolean, black: Boolean = false): ColorScheme {
    val t = Tonals(palette)
    return if (!dark) {
        lightColorScheme(
            primary = t.primary.tone(40).c(),
            onPrimary = t.primary.tone(100).c(),
            primaryContainer = t.soft.tone(90).c(),
            onPrimaryContainer = t.primary.tone(10).c(),
            inversePrimary = t.primary.tone(80).c(),
            secondary = t.secondary.tone(40).c(),
            onSecondary = t.secondary.tone(100).c(),
            secondaryContainer = t.secondary.tone(90).c(),
            onSecondaryContainer = t.secondary.tone(10).c(),
            tertiary = t.tertiary.tone(40).c(),
            onTertiary = t.tertiary.tone(100).c(),
            tertiaryContainer = t.tertiary.tone(90).c(),
            onTertiaryContainer = t.tertiary.tone(10).c(),
            background = t.neutral.tone(98).c(),
            onBackground = t.neutral.tone(10).c(),
            surface = t.neutral.tone(98).c(),
            onSurface = t.neutral.tone(10).c(),
            surfaceVariant = t.neutralVariant.tone(90).c(),
            onSurfaceVariant = t.neutralVariant.tone(30).c(),
            surfaceTint = t.primary.tone(40).c(),
            inverseSurface = t.neutral.tone(20).c(),
            inverseOnSurface = t.neutral.tone(95).c(),
            error = t.error.tone(40).c(),
            onError = t.error.tone(100).c(),
            errorContainer = t.error.tone(90).c(),
            onErrorContainer = t.error.tone(10).c(),
            outline = t.neutralVariant.tone(50).c(),
            outlineVariant = t.neutralVariant.tone(80).c(),
            scrim = Color.Black,
            surfaceBright = t.neutral.tone(98).c(),
            surfaceDim = t.neutral.tone(87).c(),
            surfaceContainerLowest = t.neutral.tone(100).c(),
            surfaceContainerLow = t.neutral.tone(96).c(),
            surfaceContainer = t.neutral.tone(94).c(),
            surfaceContainerHigh = t.neutral.tone(92).c(),
            surfaceContainerHighest = t.neutral.tone(90).c(),
        )
    } else {
        // Black background: pure black surfaces, with containers lifted slightly less than usual.
        val base = if (black) 0 else 6
        darkColorScheme(
            primary = t.primary.tone(80).c(),
            onPrimary = t.primary.tone(20).c(),
            primaryContainer = t.primary.tone(30).c(),
            onPrimaryContainer = t.soft.tone(90).c(),
            inversePrimary = t.primary.tone(40).c(),
            secondary = t.secondary.tone(80).c(),
            onSecondary = t.secondary.tone(20).c(),
            secondaryContainer = t.secondary.tone(30).c(),
            onSecondaryContainer = t.secondary.tone(90).c(),
            tertiary = t.tertiary.tone(80).c(),
            onTertiary = t.tertiary.tone(20).c(),
            tertiaryContainer = t.tertiary.tone(30).c(),
            onTertiaryContainer = t.tertiary.tone(90).c(),
            background = t.neutral.tone(base).c(),
            onBackground = t.neutral.tone(90).c(),
            surface = t.neutral.tone(base).c(),
            onSurface = t.neutral.tone(90).c(),
            surfaceVariant = t.neutralVariant.tone(30).c(),
            onSurfaceVariant = t.neutralVariant.tone(80).c(),
            surfaceTint = t.primary.tone(80).c(),
            inverseSurface = t.neutral.tone(90).c(),
            inverseOnSurface = t.neutral.tone(20).c(),
            error = t.error.tone(80).c(),
            onError = t.error.tone(20).c(),
            errorContainer = t.error.tone(30).c(),
            onErrorContainer = t.error.tone(90).c(),
            outline = t.neutralVariant.tone(60).c(),
            outlineVariant = t.neutralVariant.tone(30).c(),
            scrim = Color.Black,
            surfaceBright = t.neutral.tone(24).c(),
            surfaceDim = t.neutral.tone(base).c(),
            surfaceContainerLowest = t.neutral.tone(if (black) 0 else 4).c(),
            surfaceContainerLow = t.neutral.tone(if (black) 6 else 10).c(),
            surfaceContainer = t.neutral.tone(if (black) 9 else 12).c(),
            surfaceContainerHigh = t.neutral.tone(if (black) 14 else 17).c(),
            surfaceContainerHighest = t.neutral.tone(if (black) 19 else 22).c(),
        )
    }
}

/** Makes a dynamic (wallpaper) dark scheme use a black background. */
fun ColorScheme.withBlackBackground(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
)
