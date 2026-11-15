package app.murmur.ui.screens

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
import app.murmur.settings.ShareMode
import app.murmur.web.CodeMode
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import app.murmur.web.ExtractOptions
import app.murmur.web.SiteSignInActivity
import app.murmur.web.TableMode
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val speed by c.settings.speed.collectAsStateWithLifecycle()
    val pause by c.settings.paragraphPauseMs.collectAsStateWithLifecycle()
    val shareMode by c.settings.shareMode.collectAsStateWithLifecycle()
    val googleVoices by c.settings.googleVoices.collectAsStateWithLifecycle()
    val keepPlaying by c.settings.keepPlayingWhileLoading.collectAsStateWithLifecycle()
    val headings by c.settings.headingsAsChapters.collectAsStateWithLifecycle()
    val intro by c.settings.spokenIntro.collectAsStateWithLifecycle()
    val captions by c.settings.readCaptions.collectAsStateWithLifecycle()
    val tables by c.settings.tableMode.collectAsStateWithLifecycle()
    val code by c.settings.codeMode.collectAsStateWithLifecycle()
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
            Text("Tables", style = MaterialTheme.typography.titleSmall)
            RadioRow("Skip", tables == TableMode.SKIP) { c.settings.setTableMode(TableMode.SKIP) }
            RadioRow("Read row by row", tables == TableMode.READ) { c.settings.setTableMode(TableMode.READ) }
            Text("Code blocks", style = MaterialTheme.typography.titleSmall)
            RadioRow("Skip", code == CodeMode.SKIP) { c.settings.setCodeMode(CodeMode.SKIP) }
            RadioRow("Say “${ExtractOptions.CODE_NOTICE}”", code == CodeMode.NOTICE) { c.settings.setCodeMode(CodeMode.NOTICE) }
            RadioRow("Read line by line", code == CodeMode.READ) { c.settings.setCodeMode(CodeMode.READ) }

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
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label)
    }
}
