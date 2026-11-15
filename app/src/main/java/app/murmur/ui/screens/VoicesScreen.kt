package app.murmur.ui.screens

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.AppContainer
import app.murmur.container
import app.murmur.voice.DownloadState
import app.murmur.voice.Gender
import app.murmur.voice.PcmVoiceSource
import app.murmur.voice.PlayingVoiceSource
import app.murmur.voice.Voice
import app.murmur.voice.VoicePackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ---- Voice: choose one of the voices you have -----------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(onDone: () -> Unit, onDownloads: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val all by c.voices.voices.collectAsStateWithLifecycle()
    val installed by c.voices.installed.collectAsStateWithLifecycle()
    val selected by c.settings.voice.collectAsStateWithLifecycle()
    val selectedOnOpen by rememberSaveable { mutableStateOf(c.settings.voice.value) }
    val deviceLanguage = remember { Locale.getDefault().language }
    val deviceLocale = remember { Locale.getDefault().toLanguageTag() }
    val scope = rememberCoroutineScope()

    var genderFilter by rememberSaveable { mutableStateOf<Gender?>(null) }
    var previewing by remember { mutableStateOf<String?>(null) }

    val mine = all.filter { it.id in installed }
    // Two downloads can contain a voice with the same name (e.g. Bella); name the download then.
    val duplicateNames = mine.groupBy { it.name }.filterValues { it.size > 1 }.keys
    val shown = mine
        .filter { genderFilter == null || it.gender == genderFilter }
        .sortedWith(
            // The voice that was current when the screen opened goes first. Not the live
            // selection: tapping a voice must not move it while you're looking at the list.
            compareBy<Voice> { it.id != selectedOnOpen }
                // The phone's own locale (e.g. English (US)) before other regions of its language.
                .thenBy { it.language != deviceLocale }
                .thenBy { languageOf(it) != deviceLanguage }
                .thenBy { it.language }
                // Pad numbers so "Google voice 2" sorts before "Google voice 16".
                .thenBy { v -> v.name.replace(DIGITS) { it.value.padStart(4, '0') } },
        )

    fun preview(voice: Voice) {
        previewing = voice.id
        scope.launch {
            try {
                preview(c, voice)
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Preview failed", Toast.LENGTH_LONG).show()
            } finally {
                previewing = null
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Voice") }, navigationIcon = { BackIcon(onDone) }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (mine.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("You haven't downloaded any voices yet.", style = MaterialTheme.typography.bodyLarge)
                        DownloadVoicesButton(onDownloads)
                    }
                }
                return@LazyColumn
            }
            if (mine.size > 3 && mine.any { it.gender != null }) {
                item(key = "filters") {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(selected = genderFilter == null, onClick = { genderFilter = null }, label = { Text("All") })
                        Gender.entries.forEach { g ->
                            FilterChip(selected = genderFilter == g, onClick = { genderFilter = g }, label = { Text(g.label) })
                        }
                    }
                }
            }
            item(key = "h-mine") { SectionHeader("Your voices") }
            items(shown, key = { it.id }) { voice ->
                val isSelected = voice.id == selected
                ListItem(
                    modifier = Modifier.clickable {
                        c.settings.setVoice(voice.id)
                        c.reader.restartIfPlaying()
                    },
                    leadingContent = {
                        Box(Modifier.size(24.dp)) {
                            if (isSelected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    headlineContent = { Text(voice.name) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                voice.gender?.label,
                                localeName(voice.language),
                                voice.pkg.name.takeIf { voice.name in duplicateNames },
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { preview(voice) }, enabled = previewing == null) {
                            if (previewing == voice.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.PlayArrow, contentDescription = "Preview ${voice.name}")
                        }
                    },
                )
            }
            item(key = "downloads") {
                Box(Modifier.padding(16.dp)) { DownloadVoicesButton(onDownloads) }
            }
        }
    }
}

@Composable
private fun DownloadVoicesButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Download voices")
    }
}

// ---- Download voices: one row per download ---------------------------------------------------

/** A download and the voices it provides. */
private class Download(val pkg: VoicePackage, val voices: List<Voice>) {
    val languages: Set<String> = voices.map(::languageOf).toSet()
    val qualityRank: Int = voices.maxOf { it.qualityRank }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadVoicesScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    val all by c.voices.voices.collectAsStateWithLifecycle()
    val installed by c.voices.installed.collectAsStateWithLifecycle()
    val deviceLanguage = remember { Locale.getDefault().language }

    val downloads = all.groupBy { it.pkg.id }.values.map { Download(it.first().pkg, it) }
    var languageFilter by rememberSaveable {
        mutableStateOf(deviceLanguage.takeIf { lang -> all.any { languageOf(it) == lang } })
    }
    var confirmDelete by remember { mutableStateOf<Download?>(null) }

    val languages = downloads.flatMap { it.languages }.distinct()
        .sortedWith(compareBy<String> { it != deviceLanguage }.thenBy { languageName(it) })
    val order = compareBy<Download> { deviceLanguage !in it.languages }
        .thenByDescending { it.qualityRank }
        .thenBy { it.pkg.sizeMb ?: Int.MAX_VALUE }
        .thenBy { it.pkg.name }
    val visible = downloads.filter { languageFilter == null || languageFilter in it.languages }
    val done = visible.filter { d -> d.voices.any { it.id in installed } }.sortedWith(order)
    val available = visible.filter { d -> d.voices.none { it.id in installed } }.sortedWith(order)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Download voices") }, navigationIcon = { BackIcon(onBack) }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "filters") {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LanguageFilter(languages, languageFilter) { languageFilter = it }
                }
            }
            if (done.isNotEmpty()) {
                item(key = "h-done") { SectionHeader("Downloaded") }
                items(done, key = { it.pkg.id }) { d ->
                    DownloadRow(c, d, isInstalled = true, onDelete = { confirmDelete = d })
                }
            }
            if (available.isNotEmpty()) {
                item(key = "h-available") { SectionHeader("Available") }
                items(available, key = { it.pkg.id }) { d ->
                    DownloadRow(c, d, isInstalled = false, onDelete = {})
                }
            }
        }
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${d.pkg.name}?") },
            text = {
                Text(
                    if (d.pkg.voiceCount > 1) "This removes its ${d.pkg.voiceCount} voices. You can download them again later."
                    else "You can download this voice again later.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sourceFor(c, d.voices.first()).delete(d.pkg)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DownloadRow(c: AppContainer, d: Download, isInstalled: Boolean, onDelete: () -> Unit) {
    val source = remember(d.pkg.id) { sourceFor(c, d.voices.first()) }
    val state by remember(d.pkg.id) { source.downloadState(d.pkg) }.collectAsState(initial = DownloadState.Idle)
    val pkg = d.pkg
    ListItem(
        headlineContent = { Text(pkg.name) },
        supportingContent = {
            Column {
                // A download with one locale whose name doesn't say it (Piper voices) shows it here.
                val locales = d.voices.map { it.language }.distinct()
                val locale = locales.singleOrNull()?.let(::localeName)
                    ?.takeIf { !pkg.name.contains(languageName(languageOf(d.voices.first()))) }
                Text(
                    listOfNotNull(
                        if (pkg.voiceCount == 1) "1 voice" else "${pkg.voiceCount} voices",
                        locale,
                        d.languages.size.takeIf { it > 2 }?.let { "$it languages" },
                        pkg.sizeMb?.let { "$it MB" },
                        if (isInstalled && !pkg.canDelete) "Managed by Google" else pkg.highlight.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
                when (val s = state) {
                    is DownloadState.Running -> {
                        val fraction = s.fraction
                        Text(s.phase + (fraction?.let { " ${(it * 100).toInt()}%" } ?: "…"))
                        if (fraction != null) {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    DownloadState.Queued -> Text("Waiting for network…")
                    is DownloadState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    DownloadState.Idle -> Unit
                }
            }
        },
        trailingContent = {
            val busy = state is DownloadState.Running || state == DownloadState.Queued
            when {
                busy -> IconButton(onClick = { source.cancelDownload(pkg) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel download")
                }
                isInstalled && pkg.canDelete -> TextButton(onClick = onDelete) { Text("Delete") }
                isInstalled -> Unit
                else -> OutlinedButton(
                    onClick = { source.download(d.voices.first()) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("Download") }
            }
        },
    )
}

// ---- Shared pieces -----------------------------------------------------------------------------

private fun sourceFor(c: AppContainer, voice: Voice) = c.voices.sourceOf(voice)

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun LanguageFilter(languages: List<String>, selected: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(selected?.let { languageName(it) } ?: "All languages") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All languages") }, onClick = { open = false; onPick(null) })
            languages.forEach { code ->
                DropdownMenuItem(text = { Text(languageName(code)) }, onClick = { open = false; onPick(code) })
            }
        }
    }
}

private fun languageOf(voice: Voice): String = Locale.forLanguageTag(voice.language).language

/** "en" -> "English", in the phone's language. */
private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** "en-US" -> "English (US)"; regions other than the US and UK use their full names. */
private fun localeName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    val region = when (locale.country) {
        "", "XA" -> return languageName(locale.language)
        "US" -> "US"
        "GB" -> "UK"
        else -> locale.getDisplayCountry(Locale.getDefault())
    }
    return "${languageName(locale.language)} ($region)"
}

private val DIGITS = Regex("\\d+")

private const val PREVIEW_TEXT = "Hello! This is how I sound. I can read your articles, documents and books aloud."

/** Speaks a sample sentence once, outside the reader and media session. */
private suspend fun preview(c: AppContainer, voice: Voice) {
    when (val source = c.voices.sourceOf(voice)) {
        is PlayingVoiceSource -> source.speak(voice, PREVIEW_TEXT, c.settings.speed.value)
        is PcmVoiceSource -> {
            val audio = source.synthesize(voice, PREVIEW_TEXT, c.settings.speed.value.coerceAtMost(1.6f))
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
    }
}
