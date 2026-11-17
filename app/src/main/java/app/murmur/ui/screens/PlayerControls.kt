package app.murmur.ui.screens

import android.os.SystemClock
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.container
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.unit.DpSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.R
import app.murmur.library.DocStatus
import app.murmur.playback.Reader
import app.murmur.playback.ReaderContent
import app.murmur.playback.ReaderState
import app.murmur.playback.timeLeft
import app.murmur.ui.library.DocIcon
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

fun formatSpeed(s: Float): String = if (s % 1f == 0f) "${s.toInt()}×" else "${"%.2f".format(s).trimEnd('0')}×"

/**
 * The player, docked at the bottom of the library and the reader. Collapsed, it shows what's
 * playing with Rewind and Play/Pause; the chevron (or a swipe up) reveals the seek bar, sentence and
 * chapter skipping, speed, sleep timer and voice.
 *
 * [onOpen] is what tapping the title does: open the reader from the library. Without it, tapping
 * the title expands or collapses the player. [source] and [kind] pick the tile beside the title.
 */
@Composable
fun NowPlayingBar(
    state: ReaderState,
    content: ReaderContent?,
    reader: Reader,
    rewindSeconds: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onVoices: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    source: String? = null,
    kind: String? = null,
) {
    BackHandler(enabled = expanded) { onExpandedChange(false) }
    val current = content?.takeIf { it.docId == state.docId }
    val chevronTurn by animateFloatAsState(
        if (expanded) 180f else 0f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "chevron",
    )
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = colors.surfaceContainerHigh,
        shadowElevation = 16.dp,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            // Swiping the top of the player up or down expands or collapses it.
            Column(
                Modifier.pointerInput(expanded) {
                    var travel = 0f
                    detectVerticalDragGestures(
                        onDragStart = { travel = 0f },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            travel += dy
                        },
                        onDragEnd = {
                            val threshold = 24.dp.toPx()
                            if (travel < -threshold && !expanded) onExpandedChange(true)
                            if (travel > threshold && expanded) onExpandedChange(false)
                        },
                    )
                },
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(colors.onSurfaceVariant.copy(alpha = 0.35f), CircleShape),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen?.invoke() ?: onExpandedChange(!expanded) }
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DocIcon(source.orEmpty(), kind.orEmpty(), DocStatus.READY, size = 44.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = if (expanded) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusLine(state, current, showDetails = !expanded)
                    }
                    AnimatedVisibility(
                        visible = !expanded,
                        enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                        exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { reader.skipSeconds(-rewindSeconds) }, enabled = state.segmentCount > 0) {
                                SkipSecondsIcon(rewindSeconds, forward = false)
                            }
                            PlayPauseButton(state, reader, size = 48.dp)
                        }
                    }
                    IconButton(onClick = { onExpandedChange(!expanded) }) {
                        Icon(
                            Icons.Default.ExpandLess,
                            if (expanded) "Show fewer controls" else "Show more controls",
                            Modifier.rotate(chevronTurn),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                LinearProgressIndicator(
                    progress = { if (state.segmentCount > 0) (state.currentIndex + 1f) / state.segmentCount else 0f },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp).height(3.dp),
                    trackColor = colors.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut() + shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            ) {
                ExpandedControls(state, current, reader, rewindSeconds, onVoices)
            }
        }
    }
}

/**
 * Under the title: an error, "Preparing audio…", or (with [showDetails]) the chapter and time left,
 * which the expanded player shows elsewhere.
 */
@Composable
private fun StatusLine(state: ReaderState, content: ReaderContent?, showDetails: Boolean) {
    val colors = MaterialTheme.colorScheme
    when {
        state.error != null -> Text(
            state.error,
            style = MaterialTheme.typography.bodySmall,
            color = colors.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        state.playWhenReady && state.buffering -> PreparingAudio(Modifier.padding(top = 2.dp))
        showDetails -> {
            val parts = listOfNotNull(
                content?.let { chapterTitle(it, state.currentIndex) },
                content?.let { remaining(it, state.currentIndex, state.speed) },
            )
            Text(
                parts.joinToString(" · ").ifEmpty { "Preparing…" },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedControls(
    state: ReaderState,
    content: ReaderContent?,
    reader: Reader,
    rewindSeconds: Int,
    onVoices: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        // Chapter skipping sits around the chapter name, only for documents that have chapters.
        val chapter = content?.let { chapterTitle(it, state.currentIndex) }
        if (chapter != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { reader.previousChapter() }) { Icon(Icons.Default.SkipPrevious, "Previous chapter") }
                Text(
                    chapter,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                IconButton(onClick = { reader.nextChapter() }) { Icon(Icons.Default.SkipNext, "Next chapter") }
            }
        }

        if (state.segmentCount > 1) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            val interaction = remember { MutableInteractionSource() }
            Slider(
                value = dragging ?: state.currentIndex.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { reader.seekTo(it.roundToInt()) }
                    dragging = null
                },
                valueRange = 0f..(state.segmentCount - 1).toFloat(),
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
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = dragging?.roundToInt() ?: state.currentIndex
                Text(
                    "Sentence ${shown + 1} of ${state.segmentCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                content?.let {
                    Text(
                        remaining(it, shown, state.speed),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val ready = state.segmentCount > 0
            IconButton(onClick = { reader.skipSeconds(-rewindSeconds) }, enabled = ready, modifier = Modifier.size(52.dp)) {
                SkipSecondsIcon(rewindSeconds, forward = false, size = 30.dp)
            }
            IconButton(onClick = { reader.previous() }, enabled = ready, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.FastRewind, "Previous sentence", Modifier.size(30.dp))
            }
            PlayPauseButton(state, reader, size = 72.dp)
            IconButton(onClick = { reader.next() }, enabled = ready, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.FastForward, "Next sentence", Modifier.size(30.dp))
            }
            IconButton(onClick = { reader.skipSeconds(rewindSeconds) }, enabled = ready, modifier = Modifier.size(52.dp)) {
                SkipSecondsIcon(rewindSeconds, forward = true, size = 30.dp)
            }
        }

        Row(Modifier.fillMaxWidth()) {
            SpeedButton(state, reader, Modifier.weight(1f))
            SleepButton(state, reader, Modifier.weight(1f))
            val c = LocalContext.current.container
            val voiceId by c.settings.voice.collectAsStateWithLifecycle()
            val voices by c.voices.voices.collectAsStateWithLifecycle()
            OptionButton(
                Icons.Default.RecordVoiceOver,
                voices.firstOrNull { it.id == voiceId }?.name ?: "Voice",
                onClick = onVoices,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun PlayPauseButton(state: ReaderState, reader: Reader, size: Dp = 48.dp) {
    val iconSize = size * 0.5f
    FilledIconButton(onClick = { reader.toggle() }, modifier = Modifier.size(size)) {
        when {
            state.playWhenReady && state.buffering -> CircularProgressIndicator(
                modifier = Modifier.size(iconSize * 0.8f), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary,
            )
            state.playWhenReady -> Icon(Icons.Default.Pause, "Pause", Modifier.size(iconSize))
            else -> Icon(Icons.Default.PlayArrow, "Play", Modifier.size(iconSize))
        }
    }
}

/** The circular-arrow icon with the number of seconds inside, as podcast apps show it. */
@Composable
private fun SkipSecondsIcon(seconds: Int, forward: Boolean, size: Dp = 24.dp) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.Replay,
            if (forward) "Forward $seconds seconds" else "Rewind $seconds seconds",
            Modifier.size(size).graphicsLayer { if (forward) scaleX = -1f },
        )
        Text(
            seconds.toString(),
            fontSize = (size.value * 0.3f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset(y = size * 0.06f),
        )
    }
}

/**
 * One of the equal-width controls under the transport controls: an icon with the current value
 * under it ("1.5×", "25 min", the voice's name), tinted when it's changed from the default.
 */
@Composable
private fun OptionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
        )
    }
}

@Composable
private fun SpeedButton(state: ReaderState, reader: Reader, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OptionButton(
            Icons.Default.Speed,
            formatSpeed(state.speed),
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            active = state.speed != 1f,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEEDS.forEach { s ->
                DropdownMenuItem(text = { Text(formatSpeed(s)) }, onClick = {
                    reader.setSpeed(s)
                    open = false
                })
            }
        }
    }
}

@Composable
private fun SleepButton(state: ReaderState, reader: Reader, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val at = state.sleepAtElapsed
    // Minutes left on the timer, counted down while it runs.
    val minutesLeft by produceState<Int?>(null, at) {
        while (at != null) {
            value = ((at - SystemClock.elapsedRealtime()) / 60_000.0).let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(1)
            delay(15_000)
        }
        value = null
    }
    Box(modifier) {
        OptionButton(
            Icons.Default.Bedtime,
            minutesLeft?.let { "$it min" } ?: "Sleep",
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            active = at != null,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(5, 15, 30, 45, 60).forEach { m ->
                DropdownMenuItem(text = { Text("$m minutes") }, onClick = {
                    reader.setSleepTimer(m)
                    open = false
                })
            }
            if (at != null) {
                DropdownMenuItem(text = { Text("Off") }, onClick = {
                    reader.setSleepTimer(null)
                    open = false
                })
            }
        }
    }
}

/** The current chapter's name, or null for documents with a single chapter. */
private fun chapterTitle(content: ReaderContent, segment: Int): String? {
    val index = content.segments.getOrNull(segment)?.chapter ?: return null
    if (content.document.chapters.size < 2) return null
    return content.document.chapters[index].title?.takeIf { it.isNotBlank() }
        ?: if (index == 0) "Introduction" else "Chapter ${index + 1}"
}

private fun remaining(content: ReaderContent, segment: Int, speed: Float): String {
    val chars = content.charOffsets.last() - content.charOffsets[segment.coerceIn(0, content.segments.size)]
    return timeLeft(chars, speed)
}

@Composable
fun BackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
}

/** A small animated waveform with "Preparing audio…", shown until the first audio plays. */
@Composable
fun PreparingAudio(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.preparing_audio)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Waveform(Modifier.height(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun Waveform(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val color = MaterialTheme.colorScheme.primary
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            val level = 0.3f + 0.7f * (0.5f + 0.5f * kotlin.math.sin(phase - i * 0.9f))
            Spacer(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(level)
                    .background(color, RoundedCornerShape(1.5.dp)),
            )
        }
    }
}
