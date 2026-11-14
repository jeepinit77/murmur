package app.murmur.ui.screens

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.AppContainer
import app.murmur.container
import app.murmur.settings.VoiceSelection
import app.murmur.voice.DownloadState
import app.murmur.voices.VoicePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(onDone: () -> Unit) {
    val c = LocalContext.current.container
    val installed by c.voices.installed.collectAsStateWithLifecycle()
    val selected by c.settings.voice.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voices") },
                navigationIcon = { BackIcon(onDone) },
                actions = { if (installed.isNotEmpty()) TextButton(onClick = onDone) { Text("Done") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "All voices run offline on your phone. Kokoro sounds the most natural; " +
                        "Piper voices are smaller and start speaking faster (best for TalkBack).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(c.voices.catalog.voices, key = { it.id }) { pack ->
                VoiceCard(c, pack, pack.id in installed, selected)
            }
        }
    }
}

@Composable
private fun VoiceCard(c: AppContainer, pack: VoicePack, isInstalled: Boolean, selected: VoiceSelection?) {
    val context = LocalContext.current
    val download by remember(pack.id) { c.voices.downloadState(pack.id) }.collectAsState(initial = DownloadState.Idle)
    val scope = rememberCoroutineScope()
    val isSelected = selected?.packId == pack.id
    var speaker by remember(selected, pack.id) {
        mutableStateOf(if (isSelected) selected!!.speaker else pack.defaultSpeaker)
    }
    var previewing by remember { mutableStateOf(false) }

    Card(
        colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isInstalled) {
                    RadioButton(selected = isSelected, onClick = {
                        c.settings.setVoice(VoiceSelection(pack.id, speaker))
                        c.reader.restartIfPlaying()
                    })
                }
                Column(Modifier.weight(1f)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${pack.quality} · ${pack.sizeMb} MB · ${pack.languages.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (pack.description.isNotBlank()) Text(pack.description, style = MaterialTheme.typography.bodyMedium)
            Text("License: ${pack.license}", style = MaterialTheme.typography.labelSmall)

            if (isInstalled && pack.speakers.isNotEmpty()) {
                SpeakerPicker(pack, speaker) { s ->
                    speaker = s
                    if (isSelected) {
                        c.settings.setVoice(VoiceSelection(pack.id, s))
                        c.reader.restartIfPlaying()
                    }
                }
            }

            when (val d = download) {
                is DownloadState.Running -> {
                    Text(d.phase + (d.fraction?.let { " ${(it * 100).toInt()}%" } ?: "…"), style = MaterialTheme.typography.bodySmall)
                    val fraction = d.fraction
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { c.voices.cancelDownload(pack.id) }) { Text("Cancel") }
                }
                DownloadState.Queued -> {
                    Text("Waiting for network…", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { c.voices.cancelDownload(pack.id) }) { Text("Cancel") }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d is DownloadState.Failed) {
                        Text(d.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (isInstalled) {
                        OutlinedButton(enabled = !previewing, onClick = {
                            previewing = true
                            scope.launch {
                                try {
                                    preview(c, pack, speaker)
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "Preview failed", Toast.LENGTH_LONG).show()
                                } finally {
                                    previewing = false
                                }
                            }
                        }) { Text(if (previewing) "Speaking…" else "Preview") }
                        TextButton(onClick = {
                            if (isSelected) c.settings.setVoice(null)
                            c.voices.delete(pack.id)
                        }) { Text("Delete") }
                    } else {
                        Button(onClick = { c.voices.download(pack.id) }) { Text("Download") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeakerPicker(pack: VoicePack, speaker: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AssistChip(onClick = { open = true }, label = { Text("Speaker: ${pack.speakers.getOrElse(speaker) { "#$speaker" }}") })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        pack.speakers.forEachIndexed { i, name ->
            DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(i) })
        }
    }
}

/** Synthesizes the sample sentence and plays it once (outside the reader/media session). */
private suspend fun preview(c: AppContainer, pack: VoicePack, speaker: Int) {
    val audio = c.synthesizer.generate(
        pack, speaker,
        "Hello! This is how I sound. I can read your articles, documents and books aloud.",
        c.settings.speed.value.coerceAtMost(1.6f),
    )
    withContext(Dispatchers.IO) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(audio.samples.size * 4)
            .build()
        try {
            track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            Thread.sleep(audio.samples.size * 1000L / audio.sampleRate + 200)
        } finally {
            track.release()
        }
    }
}
