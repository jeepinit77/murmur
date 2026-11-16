package app.murmur.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.container
import app.murmur.settings.LineSpacing
import app.murmur.settings.ReaderFont
import app.murmur.settings.ReaderMargins
import app.murmur.settings.ReadingColors
import app.murmur.settings.Settings
import kotlin.math.roundToInt

/** Article text and heading styles from the reader text settings. */
internal class ArticleStyles(val body: TextStyle, val heading: TextStyle, val margin: Int)

@Composable
internal fun rememberArticleStyles(): ArticleStyles {
    val settings = LocalContext.current.container.settings
    val size by settings.textSize.collectAsStateWithLifecycle()
    val font by settings.readerFont.collectAsStateWithLifecycle()
    val spacing by settings.lineSpacing.collectAsStateWithLifecycle()
    val margins by settings.readerMargins.collectAsStateWithLifecycle()
    // Article text keeps the phone's fonts; Figtree is for the interface.
    val family = if (font == ReaderFont.SERIF) FontFamily.Serif else FontFamily.Default
    val headingSize = size * 1.2f
    return ArticleStyles(
        body = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = family,
            fontSize = size.sp,
            lineHeight = (size * spacing.multiplier).sp,
        ),
        heading = MaterialTheme.typography.titleLarge.copy(
            fontFamily = family,
            fontSize = headingSize.sp,
            lineHeight = (headingSize * 1.3f).sp,
        ),
        margin = margins.dp,
    )
}

/** Article styles outside reader mode, where the text settings don't apply. */
@Composable
internal fun standardArticleStyles(): ArticleStyles {
    val size = Settings.DEFAULT_TEXT_SIZE
    return ArticleStyles(
        body = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = FontFamily.Default,
            fontSize = size.sp,
            lineHeight = (size * LineSpacing.NORMAL.multiplier).sp,
        ),
        heading = MaterialTheme.typography.titleLarge,
        margin = ReaderMargins.NORMAL.dp,
    )
}

/** Background, text and secondary text colors for a reading color choice; null for Match app. */
private class Paper(val background: Color, val text: Color, val secondary: Color)

private fun ReadingColors.paper(): Paper? = when (this) {
    ReadingColors.APP -> null
    ReadingColors.WHITE -> Paper(Color(0xFFFFFFFF), Color(0xFF1F1F1F), Color(0xFF5F6368))
    ReadingColors.SEPIA -> Paper(Color(0xFFF4ECD8), Color(0xFF4B3A2A), Color(0xFF7D6650))
    ReadingColors.DARK -> Paper(Color(0xFF242424), Color(0xFFE2E2E2), Color(0xFFA9A9A9))
    ReadingColors.BLACK -> Paper(Color(0xFF000000), Color(0xFFD6D6D6), Color(0xFF999999))
}

/** The app's colors with the background, surfaces and text swapped for [paper]. */
private fun ColorScheme.onPaper(paper: Paper): ColorScheme {
    val bg = paper.background
    fun tint(amount: Float) = lerp(bg, paper.text, amount)
    return copy(
        background = bg,
        onBackground = paper.text,
        surface = bg,
        onSurface = paper.text,
        onSurfaceVariant = paper.secondary,
        surfaceContainerLowest = bg,
        surfaceContainerLow = tint(0.03f),
        surfaceContainer = tint(0.05f),
        surfaceContainerHigh = tint(0.08f),
        surfaceContainerHighest = tint(0.11f),
        outlineVariant = tint(0.18f),
        // The reading highlight: the accent color, faded into the page.
        primaryContainer = lerp(bg, primary, if (bg.luminance() > 0.5f) 0.22f else 0.35f),
        onPrimaryContainer = paper.text,
    )
}

/**
 * Reader mode's colors: the app theme, or White, Sepia, Dark or Black, with the status and
 * navigation bar icons to match while the reader is open.
 */
@Composable
internal fun ReadingTheme(enabled: Boolean, content: @Composable () -> Unit) {
    // Always the same structure, so turning reader mode on or off keeps the screen's state.
    val settings = LocalContext.current.container.settings
    val choice by settings.readingColors.collectAsStateWithLifecycle()
    val paper = if (enabled) choice.paper() else null
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, paper) { paper?.let { base.onPaper(it) } ?: base }
    val view = LocalView.current
    val lightPage = scheme.background.luminance() > 0.5f
    DisposableEffect(view, lightPage, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = lightPage
        controller?.isAppearanceLightNavigationBars = lightPage
        onDispose {
            if (before != null) {
                controller.isAppearanceLightStatusBars = before
                controller.isAppearanceLightNavigationBars = before
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        // Article text takes its color from here, not from the app's surfaces around the reader.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}

/** Reader mode's colors, text size, font, line spacing, margins and Keep screen on, for its sheet and Settings. */
@Composable
internal fun ReaderTextControls() {
    val settings = LocalContext.current.container.settings
    val size by settings.textSize.collectAsStateWithLifecycle()
    val font by settings.readerFont.collectAsStateWithLifecycle()
    val spacing by settings.lineSpacing.collectAsStateWithLifecycle()
    val margins by settings.readerMargins.collectAsStateWithLifecycle()
    val colors by settings.readingColors.collectAsStateWithLifecycle()
    val keepScreenOn by settings.keepScreenOn.collectAsStateWithLifecycle()

    ColorRow(colors, settings::setReadingColors)
    SliderRow(
        title = "Text size",
        value = "$size",
        position = size.toFloat(),
        range = Settings.MIN_TEXT_SIZE.toFloat()..Settings.MAX_TEXT_SIZE.toFloat(),
        onChange = { settings.setTextSize(it.roundToInt()) },
    )
    SegmentedRow("Font", ReaderFont.entries, font, { it.label }, settings::setReaderFont)
    SegmentedRow("Line spacing", LineSpacing.entries, spacing, { it.label }, settings::setLineSpacing)
    SegmentedRow("Margins", ReaderMargins.entries, margins, { it.label }, settings::setReaderMargins)
    SwitchRow(
        title = "Keep screen on",
        description = "While reader mode is on",
        checked = keepScreenOn,
        onChange = settings::setKeepScreenOn,
    )
}

/** Reading colors as circles showing "Aa" in each choice's colors. */
@Composable
private fun ColorRow(selected: ReadingColors, onSelect: (ReadingColors) -> Unit) {
    val app = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Colors", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ReadingColors.entries.forEach { choice ->
                val paper = choice.paper() ?: Paper(app.background, app.onBackground, app.onSurfaceVariant)
                val isSelected = choice == selected
                Column(
                    Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(choice) })
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(paper.background)
                            .border(
                                if (isSelected) 3.dp else 1.dp,
                                if (isSelected) app.primary else app.outlineVariant,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Aa", color = paper.text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    }
                    Text(
                        choice.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> SegmentedRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label(option), maxLines = 1) }
            }
        }
    }
}
