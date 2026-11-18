package app.murmur.ui.screens

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.window.PopupProperties
import androidx.activity.compose.BackHandler
import android.os.SystemClock
import androidx.compose.material3.IconToggleButton
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.murmur.settings.ReaderAlignment
import app.murmur.settings.ReaderFont
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
    val alignment by settings.readerAlignment.collectAsStateWithLifecycle()
    // Article text keeps the phone's fonts; Figtree is for the interface.
    val family = font.family()
    val headingSize = size * 1.2f
    val justified = alignment == ReaderAlignment.JUSTIFIED
    return ArticleStyles(
        body = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = family,
            fontSize = size.sp,
            lineHeight = (size * spacing.multiplier).sp,
            textAlign = if (justified) TextAlign.Justify else TextAlign.Start,
            // Hyphenation keeps justified lines from opening wide gaps between words.
            hyphens = if (justified) Hyphens.Auto else Hyphens.None,
        ),
        heading = MaterialTheme.typography.titleLarge.copy(
            fontFamily = family,
            fontSize = headingSize.sp,
            lineHeight = (headingSize * 1.3f).sp,
        ),
        margin = margins.dp,
    )
}

/** The phone's typeface for each reader font choice. */
internal fun ReaderFont.family(): FontFamily = when (this) {
    ReaderFont.SANS_SERIF -> FontFamily.Default
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.CONDENSED -> FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
    ReaderFont.MONOSPACE -> FontFamily.Monospace
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
private class Paper(val background: Color, val text: Color, val secondary: Color, val accent: Color? = null)

private fun ReadingColors.paper(): Paper? = when (this) {
    ReadingColors.APP -> null
    ReadingColors.WHITE -> Paper(Color(0xFFFFFFFF), Color(0xFF1F1F1F), Color(0xFF5F6368))
    ReadingColors.GRAY -> Paper(Color(0xFFE6E6E4), Color(0xFF262626), Color(0xFF5C5C5A))
    ReadingColors.SEPIA -> Paper(Color(0xFFF4ECD8), Color(0xFF4B3A2A), Color(0xFF7D6650))
    ReadingColors.DARK -> Paper(Color(0xFF242424), Color(0xFFE2E2E2), Color(0xFFA9A9A9))
    ReadingColors.BLACK -> Paper(Color(0xFF000000), Color(0xFFD6D6D6), Color(0xFF999999))
    // Only red light, which is easier on the eyes in the dark. Highlights and controls stay red too.
    ReadingColors.RED -> Paper(Color(0xFF000000), Color(0xFFD03A2E), Color(0xFF8E2A22), accent = Color(0xFFB0322A))
}

/** The app's colors with the background, surfaces and text swapped for [paper]. */
private fun ColorScheme.onPaper(paper: Paper): ColorScheme {
    val bg = paper.background
    // On a near-black page shadows don't show, so the bars and menus need a stronger tint to stand out.
    val boost = if (bg.luminance() < 0.02f) 2.2f else 1f
    fun tint(amount: Float) = lerp(bg, paper.text, amount * boost)
    val primary = paper.accent ?: primary
    return copy(
        primary = primary,
        onPrimary = if (paper.accent != null) bg else onPrimary,
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
        outlineVariant = lerp(bg, paper.text, 0.18f),
        // The reading highlight: the accent color, faded into the page.
        primaryContainer = lerp(bg, primary, if (bg.luminance() > 0.5f) 0.22f else 0.35f),
        onPrimaryContainer = paper.text,
        // Selected choices in the text settings.
        secondaryContainer = lerp(bg, primary, if (bg.luminance() > 0.5f) 0.18f else 0.3f),
        onSecondaryContainer = paper.text,
    )
}

/** The app's colors before reader mode swaps them, for previewing Match app. */
private val LocalAppColors = staticCompositionLocalOf<ColorScheme?> { null }

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
        CompositionLocalProvider(
            LocalContentColor provides scheme.onBackground,
            LocalAppColors provides base,
            content = content,
        )
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
    val alignment by settings.readerAlignment.collectAsStateWithLifecycle()
    val keepScreenOn by settings.keepScreenOn.collectAsStateWithLifecycle()

    ColorRow(colors, settings::setReadingColors)
    SectionDivider(start = 14.dp)
    TextSizeRow(size, settings::setTextSize)
    SectionDivider(start = 14.dp)
    SegmentedRow(
        "Font", ReaderFont.entries, font, { it.label }, settings::setReaderFont,
        // Each choice is shown in its own typeface.
        labelFamily = { it.family() },
        stacked = true,
    )
    SectionDivider(start = 14.dp)
    SegmentedRow("Line spacing", LineSpacing.entries, spacing, { it.label }, settings::setLineSpacing)
    SectionDivider(start = 14.dp)
    SegmentedRow("Margins", ReaderMargins.entries, margins, { it.label }, settings::setReaderMargins)
    SectionDivider(start = 14.dp)
    SegmentedRow("Alignment", ReaderAlignment.entries, alignment, { it.label }, settings::setReaderAlignment)
    SectionDivider(start = 14.dp)
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text("Colors", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReadingColors.entries.forEach { choice ->
                val isSelected = choice == selected
                Column(
                    Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(choice) })
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ColorSwatch(choice, 36.dp, isSelected)
                    Text(
                        choice.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** A circle showing "Aa" in a reading color choice's text and background colors. */
@Composable
private fun ColorSwatch(choice: ReadingColors, size: Dp, selected: Boolean) {
    val app = LocalAppColors.current ?: MaterialTheme.colorScheme
    val paper = choice.paper() ?: Paper(app.background, app.onBackground, app.onSurfaceVariant)
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(paper.background)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) scheme.primary else scheme.outlineVariant,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text("Aa", color = paper.text, fontWeight = FontWeight.Medium, fontSize = (size.value * 0.36f).sp)
    }
}

/** Text size: smaller and larger buttons on either side of a slider, with the size in the title line. */
@Composable
private fun TextSizeRow(size: Int, onChange: (Int) -> Unit) {
    val min = Settings.MIN_TEXT_SIZE
    val max = Settings.MAX_TEXT_SIZE
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(textSizeLabel(size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(size - 1) }, enabled = size > min, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(Icons.Default.TextDecrease, "Smaller text", Modifier.size(20.dp))
            }
            SlimSlider(
                value = size.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = min.toFloat()..max.toFloat(),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Text size"; stateDescription = textSizeLabel(size) },
            )
            IconButton(onClick = { onChange(size + 1) }, enabled = size < max, modifier = Modifier.offset(x = 12.dp)) {
                Icon(Icons.Default.TextIncrease, "Larger text", Modifier.size(20.dp))
            }
        }
    }
}

/** A title with its choices beside it, as a segmented button. */
@Composable
private fun <T> SegmentedRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    labelFamily: (T) -> FontFamily? = { null },
    stacked: Boolean = false,
) {
    if (stacked) {
        // Title above, for choices that need the full width.
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
            SegmentedChoices(Modifier.fillMaxWidth(), options, selected, label, onSelect, labelFamily)
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        SegmentedChoices(Modifier.weight(1f), options, selected, label, onSelect, labelFamily)
    }
}

@Composable
private fun <T> SegmentedChoices(
    modifier: Modifier,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    labelFamily: (T) -> FontFamily?,
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                // No check mark: the selected choice is filled, and the labels need the room.
                icon = {},
                modifier = Modifier.height(36.dp),
            ) {
                Text(label(option), style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, fontFamily = labelFamily(option))
            }
        }
    }
}

/** Text size as a percentage of the standard size, as in "100%". */
internal fun textSizeLabel(size: Int): String = "${(size * 100f / Settings.DEFAULT_TEXT_SIZE).roundToInt()}%"

/**
 * Reader mode's controls: a floating bar with Close, Text (size and font), Colors and Spacing menus, the
 * reader mode button shown as on (tapping it turns reader mode off), and [moreItems] in a More
 * options menu. [onBack] closes reader mode, returning to the article.
 */
@Composable
internal fun ReaderModeBar(
    onBack: () -> Unit,
    moreOpen: Boolean,
    onMoreOpenChange: (Boolean) -> Unit,
    moreItems: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalContext.current.container.settings
    val size by settings.textSize.collectAsStateWithLifecycle()
    val font by settings.readerFont.collectAsStateWithLifecycle()
    val spacing by settings.lineSpacing.collectAsStateWithLifecycle()
    val margins by settings.readerMargins.collectAsStateWithLifecycle()
    val colors by settings.readingColors.collectAsStateWithLifecycle()
    val alignment by settings.readerAlignment.collectAsStateWithLifecycle()
    var openMenu by remember { mutableStateOf<String?>(null) }
    // The menus don't take focus, so tapping another button in the bar while one is open reaches
    // that button: the open menu closes and the tapped one opens. Tapping the open menu's own
    // button closes it, so that tap must not reopen what the outside tap just closed.
    var lastClosed by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val current = openMenu ?: "more".takeIf { moreOpen }
    fun close() {
        current?.let { lastClosed = it to SystemClock.uptimeMillis() }
        openMenu = null
        onMoreOpenChange(false)
    }
    fun open(name: String) {
        val justClosed = current == name ||
            lastClosed?.let { (n, t) -> n == name && SystemClock.uptimeMillis() - t < 500 } == true
        close()
        if (justClosed) return
        if (name == "more") onMoreOpenChange(true) else openMenu = name
    }
    BackHandler(enabled = current != null) { close() }
    val menu = PopupProperties(focusable = false)
    val scheme = MaterialTheme.colorScheme
    val tonal = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
    )

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Close reader mode") }

            Box {
                FilledTonalIconButton(onClick = { open("font") }, colors = tonal) { Icon(Icons.Default.TextFields, "Text") }
                DropdownMenu(expanded = openMenu == "font", onDismissRequest = ::close, properties = menu) {
                    // Text size first: it changes more often than the font.
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { settings.setTextSize(size - 1) }, enabled = size > Settings.MIN_TEXT_SIZE) {
                            Icon(Icons.Default.Remove, "Smaller text")
                        }
                        Text(
                            textSizeLabel(size),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { settings.setTextSize(size + 1) }, enabled = size < Settings.MAX_TEXT_SIZE) {
                            Icon(Icons.Default.Add, "Larger text")
                        }
                    }
                    HorizontalDivider()
                    ReaderFont.entries.forEach { f ->
                        ChoiceItem(f.label, f == font, fontFamily = f.family()) { settings.setReaderFont(f) }
                    }
                }
            }
            Box {
                FilledTonalIconButton(onClick = { open("colors") }, colors = tonal) { Icon(Icons.Default.Palette, "Colors") }
                DropdownMenu(expanded = openMenu == "colors", onDismissRequest = ::close, properties = menu) {
                    ReadingColors.entries.forEach { choice ->
                        ChoiceItem(choice.label, choice == colors, leading = {
                            ColorSwatch(choice, 32.dp, selected = false)
                        }) { settings.setReadingColors(choice) }
                    }
                }
            }
            Box {
                FilledTonalIconButton(onClick = { open("spacing") }, colors = tonal) {
                    Icon(Icons.Default.FormatLineSpacing, "Spacing")
                }
                DropdownMenu(expanded = openMenu == "spacing", onDismissRequest = ::close, properties = menu) {
                    PictureChoices("Line spacing", LineSpacing.entries, spacing, { it.label }, settings::setLineSpacing) { l, color ->
                        LineSpacingPicture(l, color)
                    }
                    PictureChoices("Margins", ReaderMargins.entries, margins, { it.label }, settings::setReaderMargins) { m, color ->
                        MarginsPicture(m, color)
                    }
                    PictureChoices("Alignment", ReaderAlignment.entries, alignment, { it.label }, settings::setReaderAlignment) { a, color ->
                        val icon = if (a == ReaderAlignment.LEFT) Icons.AutoMirrored.Filled.FormatAlignLeft else Icons.Default.FormatAlignJustify
                        Icon(icon, null, tint = color)
                    }
                }
            }

            // The same button that turned reader mode on in the article, shown as on.
            IconToggleButton(
                checked = true,
                onCheckedChange = { onBack() },
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = scheme.primary,
                    checkedContentColor = scheme.onPrimary,
                ),
            ) { Icon(Icons.AutoMirrored.Filled.MenuBook, "Reader mode") }

            Box {
                IconButton(onClick = { open("more") }) { Icon(Icons.Default.MoreVert, "More options") }
                DropdownMenu(expanded = moreOpen, onDismissRequest = ::close, properties = menu) { moreItems() }
            }
        }
    }
}

/** A menu choice with a check mark when selected; the menu stays open so changes can be compared. */
@Composable
private fun ChoiceItem(
    label: String,
    selected: Boolean,
    fontFamily: FontFamily? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, fontFamily = fontFamily) },
        onClick = onClick,
        leadingIcon = leading,
        trailingIcon = { if (selected) Icon(Icons.Default.Check, "Selected") },
    )
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** A row of choices shown as small pictures with labels, under a heading. The menu stays open. */
@Composable
private fun <T> PictureChoices(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    picture: @Composable (T, Color) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                val content = if (isSelected) scheme.onSecondaryContainer else scheme.onSurface
                Column(
                    Modifier
                        .width(76.dp)
                        .clip(shape)
                        .background(if (isSelected) scheme.secondaryContainer else Color.Transparent)
                        .border(1.dp, if (isSelected) scheme.primary else scheme.outlineVariant, shape)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(top = 10.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { picture(option, content) }
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelMedium,
                        color = content,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Four lines with the gap between them growing with the line spacing. */
@Composable
private fun LineSpacingPicture(spacing: LineSpacing, color: Color) {
    val gap = when (spacing) {
        LineSpacing.COMPACT -> 2.dp
        LineSpacing.NORMAL -> 4.dp
        LineSpacing.WIDE -> 6.dp
    }
    Canvas(Modifier.size(28.dp)) {
        val stroke = 2.dp.toPx()
        val step = stroke + gap.toPx()
        var y = (size.height - (4 * stroke + 3 * gap.toPx())) / 2 + stroke / 2
        repeat(4) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke, cap = StrokeCap.Round)
            y += step
        }
    }
}

/** A page with lines of text, set in from the edges by the margin. */
@Composable
private fun MarginsPicture(margins: ReaderMargins, color: Color) {
    val inset = when (margins) {
        ReaderMargins.NARROW -> 2.dp
        ReaderMargins.NORMAL -> 4.5.dp
        ReaderMargins.WIDE -> 7.dp
    }
    Canvas(Modifier.width(26.dp).height(32.dp)) {
        val border = 1.5.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(border / 2, border / 2),
            size = Size(size.width - border, size.height - border),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(border),
        )
        val x = border + inset.toPx()
        var y = 7.dp.toPx()
        repeat(5) {
            drawLine(color, Offset(x, y), Offset(size.width - x, y), 1.5.dp.toPx(), cap = StrokeCap.Round)
            y += 4.5.dp.toPx()
        }
    }
}
