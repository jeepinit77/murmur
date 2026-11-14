package app.murmur.ui.screens

import androidx.compose.foundation.clickable
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
            PlayPauseButton(state, reader)
        }
    }
}

@Composable
fun BackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
}
