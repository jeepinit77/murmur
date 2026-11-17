package app.murmur.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.DpSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

/*
 * Building blocks for settings screens: settings sit in rounded cards, grouped under a title, as in
 * the phone's own Settings app. Rows have the same padding everywhere so the cards line up.
 */

/** Horizontal padding of every row inside a card. */
private val ROW_PADDING = 16.dp

/**
 * A titled group of settings in one card. [note] explains the whole group, under its title.
 * [header] goes between the note and the card, such as a search field.
 */
@Composable
internal fun SettingsGroup(
    title: String? = null,
    note: String? = null,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = ROW_PADDING, end = ROW_PADDING, bottom = 8.dp),
            )
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = ROW_PADDING, end = ROW_PADDING, bottom = 8.dp),
            )
        }
        header?.invoke()
        SettingsCard(content = content)
    }
}

/** The rounded card behind a group of rows. */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = shape, color = color) {
        Column(Modifier.padding(vertical = 6.dp), content = content)
    }
}

/**
 * The shape of item [index] of [count] in a list of separate cards: the first and last are rounded
 * on the outside, the rest only slightly, so the list reads as one group.
 */
internal fun segmentShape(index: Int, count: Int): Shape {
    val outer = 24.dp
    val inner = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

/** A heading for a few rows within a card, such as "Code blocks". */
@Composable
internal fun SettingsSubheading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = ROW_PADDING, end = ROW_PADDING, top = 12.dp, bottom = 2.dp),
    )
}

/** Explanatory text inside a card. */
@Composable
internal fun SettingsNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = ROW_PADDING, vertical = 8.dp),
    )
}

@Composable
internal fun SwitchRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = ROW_PADDING, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, description, Modifier.weight(1f).padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun RadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    description: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(start = 4.dp, end = ROW_PADDING, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        RowText(label, description, Modifier.weight(1f).padding(vertical = 8.dp), enabled = enabled)
    }
}

/** A setting with a slider, its current value shown at the end of the title line. */
@Composable
internal fun SliderRow(
    title: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ROW_PADDING, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        SlimSlider(position, onChange, range)
    }
}

/** A row that opens another screen, with its current state ("On") before the arrow. */
@Composable
internal fun NavigationRow(title: String, description: String?, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = ROW_PADDING, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, description, Modifier.weight(1f).padding(end = 8.dp))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** A row that does something right away, such as opening system settings or clearing data. */
@Composable
internal fun ActionRow(title: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = ROW_PADDING, vertical = 14.dp),
    )
}

@Composable
private fun RowText(title: String, description: String?, modifier: Modifier, enabled: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val onSurface = MaterialTheme.colorScheme.onSurface
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) onSurface else onSurface.copy(alpha = 0.38f))
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Murmur's slider: a slim track and a narrow thumb, as in the player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        thumb = { SliderDefaults.Thumb(interaction, thumbSize = DpSize(4.dp, 24.dp)) },
        track = {
            SliderDefaults.Track(
                sliderState = it,
                modifier = Modifier.height(6.dp),
                drawStopIndicator = null,
                thumbTrackGapSize = 4.dp,
                trackInsideCornerSize = 2.dp,
            )
        },
        modifier = modifier,
    )
}

/** One option of a [ChoiceRow]. */
internal class Choice<T>(val value: T, val label: String, val description: String? = null, val enabled: Boolean = true)

/**
 * A setting with a few options, shown as one row with the current choice under its title. Tapping
 * it opens a dialog with the options; picking one closes it.
 */
@Composable
internal fun <T> ChoiceRow(title: String, choices: List<Choice<T>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = ROW_PADDING, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                choices.firstOrNull { it.value == selected }?.label.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    choices.forEach { choice ->
                        RadioRow(choice.label, choice.value == selected, choice.enabled, choice.description) {
                            onSelect(choice.value)
                            open = false
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/*
 * Compact settings: rounded cards with their heading inside, 20dp icons, smaller text and inset
 * dividers. Used where a screen holds many rows, such as notification reading.
 */

/** A card of compact rows, with an optional small heading inside it. */
@Composable
internal fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = color) {
        Column(Modifier.padding(top = if (title == null) 4.dp else 0.dp, bottom = 4.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
                )
            }
            content()
        }
    }
}

/** A divider between compact rows, starting where their text starts. */
@Composable
internal fun SectionDivider(start: Dp = 48.dp) {
    HorizontalDivider(Modifier.padding(start = start, end = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun CompactRowText(title: String, description: String?, modifier: Modifier) {
    Column(modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CompactIcon(icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 14.dp).size(20.dp))
    }
}

@Composable
internal fun CompactSwitchRow(icon: ImageVector?, title: String, description: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 44.dp)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactIcon(icon)
        CompactRowText(title, description, Modifier.weight(1f).padding(end = 4.dp))
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.scale(0.8f))
    }
}

/** A compact row that opens another screen, with an optional summary under its title. */
@Composable
internal fun CompactNavigationRow(icon: ImageVector?, title: String, summary: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactIcon(icon)
        CompactRowText(title, summary, Modifier.weight(1f).padding(end = 4.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
