package app.murmur.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.container
import app.murmur.settings.LineSpacing
import app.murmur.settings.ReaderFont
import app.murmur.settings.Settings
import kotlin.math.roundToInt

/** Article text and heading styles from the reader text settings. */
internal class ArticleStyles(val body: TextStyle, val heading: TextStyle)

@Composable
internal fun rememberArticleStyles(): ArticleStyles {
    val settings = LocalContext.current.container.settings
    val size by settings.textSize.collectAsStateWithLifecycle()
    val font by settings.readerFont.collectAsStateWithLifecycle()
    val spacing by settings.lineSpacing.collectAsStateWithLifecycle()
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
    )
}

/** Text size, font, line spacing and Keep screen on, shared by the reader's sheet and Settings. */
@Composable
internal fun ReaderTextControls() {
    val settings = LocalContext.current.container.settings
    val size by settings.textSize.collectAsStateWithLifecycle()
    val font by settings.readerFont.collectAsStateWithLifecycle()
    val spacing by settings.lineSpacing.collectAsStateWithLifecycle()
    val keepScreenOn by settings.keepScreenOn.collectAsStateWithLifecycle()

    SliderRow(
        title = "Text size",
        value = "$size",
        position = size.toFloat(),
        range = Settings.MIN_TEXT_SIZE.toFloat()..Settings.MAX_TEXT_SIZE.toFloat(),
        onChange = { settings.setTextSize(it.roundToInt()) },
    )
    SegmentedRow("Font", ReaderFont.entries, font, { it.label }, settings::setReaderFont)
    SegmentedRow("Line spacing", LineSpacing.entries, spacing, { it.label }, settings::setLineSpacing)
    SwitchRow(
        title = "Keep screen on",
        description = "While reading an article that isn't playing",
        checked = keepScreenOn,
        onChange = settings::setKeepScreenOn,
    )
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
