package app.murmur.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import app.murmur.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.murmur.playback.Reader
import app.murmur.playback.ReaderContent
import app.murmur.playback.timeLeft
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Slider
import kotlin.math.roundToInt
import app.murmur.playback.ReaderState

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

fun formatSpeed(s: Float): String = if (s % 1f == 0f) "${s.toInt()}×" else "${"%.2f".format(s).trimEnd('0')}×"

@Composable
fun PlayPauseButton(state: ReaderState, reader: Reader, big: Boolean = false) {
    val size = if (big) 64.dp else 48.dp
    FilledIconButton(onClick = { reader.toggle() }, modifier = Modifier.size(size)) {
        when {
            state.playWhenReady && state.buffering -> CircularProgressIndicator(
                modifier = Modifier.size(size / 2), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary,
            )
            state.playWhenReady -> Icon(Icons.Default.Pause, "Pause")
            else -> Icon(Icons.Default.PlayArrow, "Play")
        }
    }
}

/** Full transport bar for the reader screen. */
@Composable
fun PlayerBar(state: ReaderState, reader: Reader) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (state.segmentCount > 0) {
                LinearProgressIndicator(
                    progress = { (state.currentIndex + 1f) / state.segmentCount },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.playWhenReady && state.buffering) PreparingAudio(Modifier.padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { reader.previousChapter() }) { Icon(Icons.Default.SkipPrevious, "Previous chapter") }
                IconButton(onClick = { reader.previous() }) { Icon(Icons.Default.FastRewind, "Previous sentence") }
                PlayPauseButton(state, reader, big = true)
                IconButton(onClick = { reader.next() }) { Icon(Icons.Default.FastForward, "Next sentence") }
                IconButton(onClick = { reader.nextChapter() }) { Icon(Icons.Default.SkipNext, "Next chapter") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                SpeedChip(state, reader)
                Spacer(Modifier.width(8.dp))
                SleepChip(state, reader)
            }
        }
    }
}

@Composable
fun SpeedChip(state: ReaderState, reader: Reader) {
    var open by remember { mutableStateOf(false) }
    AssistChip(onClick = { open = true }, label = { Text("Speed ${formatSpeed(state.speed)}") })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        SPEEDS.forEach { s ->
            DropdownMenuItem(text = { Text(formatSpeed(s)) }, onClick = {
                reader.setSpeed(s)
                open = false
            })
        }
    }
}

@Composable
fun SleepChip(state: ReaderState, reader: Reader) {
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text(if (state.sleepAtElapsed != null) "Sleep on" else "Sleep") },
        leadingIcon = { Icon(Icons.Default.Bedtime, null, Modifier.size(18.dp)) },
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        listOf(5, 15, 30, 45, 60).forEach { m ->
            DropdownMenuItem(text = { Text("$m minutes") }, onClick = {
                reader.setSleepTimer(m)
                open = false
            })
        }
        if (state.sleepAtElapsed != null) {
            DropdownMenuItem(text = { Text("Off") }, onClick = {
                reader.setSleepTimer(null)
                open = false
            })
        }
    }
}

/**
 * The player card at the top of the reader: what's playing, the current chapter, a seek bar with
 * the time left, and every playback control. Replaces the bottom [PlayerBar] while shown.
 */
@Composable
fun PlayerCard(
    state: ReaderState,
    content: ReaderContent?,
    reader: Reader,
    rewindSeconds: Int,
    onVoices: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val chapter = content?.let { c ->
                        val index = c.segments.getOrNull(state.currentIndex)?.chapter ?: return@let null
                        if (c.document.chapters.size < 2) return@let null
                        c.document.chapters[index].title?.takeIf { it.isNotBlank() }
                            ?: if (index == 0) "Introduction" else "Chapter ${index + 1}"
                    }
                    if (chapter != null) {
                        Text(
                            chapter,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onHide) { Icon(Icons.Default.ExpandLess, "Hide player") }
            }
            Column(Modifier.padding(end = 12.dp)) {
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.playWhenReady && state.buffering) PreparingAudio(Modifier.padding(vertical = 4.dp))
                if (state.segmentCount > 1) {
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = dragging ?: state.currentIndex.toFloat(),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { reader.seekTo(it.roundToInt()) }
                            dragging = null
                        },
                        valueRange = 0f..(state.segmentCount - 1).toFloat(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val shown = dragging?.roundToInt() ?: state.currentIndex
                        Text(
                            "Sentence ${shown + 1} of ${state.segmentCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        content?.let { c ->
                            val chars = c.charOffsets.last() - c.charOffsets[shown.coerceIn(0, c.segments.size)]
                            Text(
                                timeLeft(chars, state.speed),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { reader.skipSeconds(-rewindSeconds) }) {
                        Icon(Icons.Default.Replay, "Rewind $rewindSeconds seconds")
                    }
                    IconButton(onClick = { reader.previousChapter() }) { Icon(Icons.Default.SkipPrevious, "Previous chapter") }
                    IconButton(onClick = { reader.previous() }) { Icon(Icons.Default.FastRewind, "Previous sentence") }
                    PlayPauseButton(state, reader, big = true)
                    IconButton(onClick = { reader.next() }) { Icon(Icons.Default.FastForward, "Next sentence") }
                    IconButton(onClick = { reader.nextChapter() }) { Icon(Icons.Default.SkipNext, "Next chapter") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    SpeedChip(state, reader)
                    Spacer(Modifier.width(8.dp))
                    SleepChip(state, reader)
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = onVoices,
                        label = { Text("Voice") },
                        leadingIcon = { Icon(Icons.Default.RecordVoiceOver, null, Modifier.size(18.dp)) },
                    )
                }
            }
        }
    }
}

/** Compact bar at the bottom of the library while something is loaded. */
@Composable
fun MiniPlayer(state: ReaderState, reader: Reader, onClick: () -> Unit) {
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                if (state.error == null && state.playWhenReady && state.buffering) {
                    PreparingAudio()
                } else {
                    Text(
                        when {
                            state.error != null -> state.error
                            state.segmentCount > 0 -> "Sentence ${state.currentIndex + 1} of ${state.segmentCount}"
                            else -> "Preparing…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
            PlayPauseButton(state, reader)
        }
    }
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
