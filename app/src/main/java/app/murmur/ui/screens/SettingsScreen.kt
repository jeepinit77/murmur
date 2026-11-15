package app.murmur.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.BuildConfig
import app.murmur.container
import app.murmur.settings.Settings
import app.murmur.settings.SavedPageMode
import app.murmur.settings.ShareMode
import app.murmur.web.CodeMode
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import app.murmur.web.ExtractOptions
import app.murmur.web.SiteSignInActivity
import app.murmur.web.TableAiStatus
import app.murmur.web.TableMode
import kotlinx.coroutines.delay
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onNotificationReading: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val speed by c.settings.speed.collectAsStateWithLifecycle()
    val pause by c.settings.paragraphPauseMs.collectAsStateWithLifecycle()
    val shareMode by c.settings.shareMode.collectAsStateWithLifecycle()
    val savedPageMode by c.settings.savedPageMode.collectAsStateWithLifecycle()
    val rewind by c.settings.rewindSeconds.collectAsStateWithLifecycle()
    val googleVoices by c.settings.googleVoices.collectAsStateWithLifecycle()
    val keepPlaying by c.settings.keepPlayingWhileLoading.collectAsStateWithLifecycle()
    val soundCues by c.settings.soundCues.collectAsStateWithLifecycle()
    val headings by c.settings.headingsAsChapters.collectAsStateWithLifecycle()
    val intro by c.settings.spokenIntro.collectAsStateWithLifecycle()
    val captions by c.settings.readCaptions.collectAsStateWithLifecycle()
    val tables by c.settings.tableMode.collectAsStateWithLifecycle()
    val code by c.settings.codeMode.collectAsStateWithLifecycle()
    val labelAi by c.settings.labelAiSummaries.collectAsStateWithLifecycle()
    val notificationReading by c.settings.notificationReading.collectAsStateWithLifecycle()
    // Table summaries need an on-device model: null in the foss build. Rechecked while it downloads.
    val tableAi = c.tableAi
    val aiStatus by produceState<TableAiStatus?>(null, tableAi) {
        if (tableAi == null) return@produceState
        while (true) {
            value = tableAi.status()
            if (value != TableAiStatus.DOWNLOADING && value != TableAiStatus.NEEDS_DOWNLOAD) break
            delay(5_000)
        }
    }
    // Until the first check finishes, trust a saved "Summarize" choice so the selection doesn't jump.
    val canSummarize = tableAi != null && (aiStatus?.usable ?: (tables == TableMode.SUMMARIZE))
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear website sign-ins?") },
            text = { Text("You'll be signed out of every site you signed in to in Murmur.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    SiteSignInActivity.clearSignIns()
                    Toast.makeText(context, "Website sign-ins cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Reading speed: ${formatSpeed(speed)}")
            Slider(
                value = speed,
                onValueChange = { c.reader.setSpeed((it * 20).roundToInt() / 20f) },
                valueRange = Settings.MIN_SPEED..Settings.MAX_SPEED,
            )

            Section("Pause between paragraphs: $pause ms")
            Slider(
                value = pause.toFloat(),
                onValueChange = { c.settings.setParagraphPauseMs((it / 50).roundToInt() * 50) },
                valueRange = 0f..1500f,
            )

            HorizontalDivider()
            Section("Rewind button")
            Settings.REWIND_CHOICES.forEach { seconds ->
                RadioRow("$seconds seconds", rewind == seconds) { c.settings.setRewindSeconds(seconds) }
            }

            HorizontalDivider()
            Section("When sharing while something is playing")
            ShareMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = shareMode == mode, onClick = { c.settings.setShareMode(mode) })
                    Text(if (mode == ShareMode.REPLACE) "Start reading the new item" else "Add it to the queue")
                }
            }
            if (shareMode == ShareMode.REPLACE) {
                SwitchRow(
                    title = "Keep playing while loading",
                    description = "Keep reading the current item until the shared page is ready, instead of stopping right away.",
                    checked = keepPlaying,
                    onChange = c.settings::setKeepPlayingWhileLoading,
                )
            }
            SwitchRow(
                title = "Loading and error sounds",
                description = "Play a quiet tone while audio is being prepared, and a short sound if reading can't start.",
                checked = soundCues,
                onChange = c.settings::setSoundCues,
            )

            HorizontalDivider()
            Section("When sharing a saved page")
            SavedPageMode.entries.forEach { mode ->
                val label = when (mode) {
                    SavedPageMode.ASK -> "Ask"
                    SavedPageMode.RESUME -> "Resume"
                    SavedPageMode.IMPORT_AGAIN -> "Import again"
                }
                RadioRow(label, savedPageMode == mode) { c.settings.setSavedPageMode(mode) }
            }

            HorizontalDivider()
            Section("Web articles")
            Text(
                "These settings apply to pages you add from now on. To update a saved article, choose Reload from its menu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                title = "Headings as chapters",
                description = "Split articles at their section headings so you can skip between sections.",
                checked = headings,
                onChange = c.settings::setHeadingsAsChapters,
            )
            SwitchRow(
                title = "Read title and author first",
                description = "After the title, read the subtitle, author, site name and publish date.",
                checked = intro,
                onChange = c.settings::setSpokenIntro,
            )
            SwitchRow(
                title = "Read image captions",
                description = "Read figure captions and image descriptions.",
                checked = captions,
                onChange = c.settings::setReadCaptions,
            )
            Text("Code blocks", style = MaterialTheme.typography.titleSmall)
            RadioRow("Skip", code == CodeMode.SKIP) { c.settings.setCodeMode(CodeMode.SKIP) }
            RadioRow("Say “${ExtractOptions.CODE_NOTICE}”", code == CodeMode.NOTICE) { c.settings.setCodeMode(CodeMode.NOTICE) }
            RadioRow("Read line by line", code == CodeMode.READ) { c.settings.setCodeMode(CodeMode.READ) }

            // Applies to every source that keeps tables: web pages, pasted text, HTML and text files, EPUBs.
            HorizontalDivider()
            Section("Tables")
            RadioRow("Skip", tables == TableMode.SKIP) { c.settings.setTableMode(TableMode.SKIP) }
            // Without a model, a saved "Summarize" choice reads row by row.
            val readSelected = tables == TableMode.READ || (tables == TableMode.SUMMARIZE && !canSummarize)
            RadioRow("Read row by row", readSelected) { c.settings.setTableMode(TableMode.READ) }
            if (tableAi != null) {
                RadioRow(
                    label = "Summarize with AI",
                    selected = tables == TableMode.SUMMARIZE && canSummarize,
                    enabled = canSummarize,
                    description = when (aiStatus) {
                        TableAiStatus.READY -> "Uses Gemini Nano on this phone. Tables with fewer than 4 rows are read row by row."
                        TableAiStatus.NEEDS_DOWNLOAD -> "Uses Gemini Nano, which will download to this phone. Tables are read row by row until it's ready."
                        TableAiStatus.DOWNLOADING -> "Gemini Nano is downloading. Tables are read row by row until it's ready."
                        TableAiStatus.UNSUPPORTED, TableAiStatus.ERROR -> "Not available on this phone. Requires Gemini Nano."
                        null -> null
                    },
                ) {
                    c.settings.setTableMode(TableMode.SUMMARIZE)
                    tableAi.prepare()
                }
                if (tables == TableMode.SUMMARIZE && canSummarize) {
                    SwitchRow(
                        title = "Label summaries as AI",
                        description = "Read “Table summary by AI” before each summary.",
                        checked = labelAi,
                        onChange = c.settings::setLabelAiSummaries,
                    )
                }
            }

            HorizontalDivider()
            Section("Notifications")
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onNotificationReading),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Notification reading", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Read notifications from apps you choose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (notificationReading) "On" else "Off", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Section("Website sign-ins")
            Text(
                "Sites you sign in to from an article's menu stay signed in, so articles from your subscriptions import in full.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear website sign-ins") }

            if (c.voices.hasOptInSources) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Google voices", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Add voices from Google's Speech Recognition & Synthesis app to the Voice screen. " +
                                "On some phones, every Google voice sounds the same.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = googleVoices, onCheckedChange = { c.settings.setGoogleVoices(it) })
                }
            }

            HorizontalDivider()
            Section("About")
            Text(
                "Murmur ${BuildConfig.VERSION_NAME} — free and open source (GPL-3.0). Speech runs on-device with " +
                    "sherpa-onnx and ONNX Runtime; no text or audio leaves your phone. Voice models are by their " +
                    "respective authors, under their own licenses.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Text(label, color = color)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
