package app.murmur.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.BuildConfig
import app.murmur.container
import app.murmur.settings.Settings
import app.murmur.settings.ShareMode
import app.murmur.settings.VoiceSelection
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val speed by c.settings.speed.collectAsStateWithLifecycle()
    val pause by c.settings.paragraphPauseMs.collectAsStateWithLifecycle()
    val shareMode by c.settings.shareMode.collectAsStateWithLifecycle()
    val systemVoice by c.settings.systemVoice.collectAsStateWithLifecycle()
    val installed by c.voices.installed.collectAsStateWithLifecycle()

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

            HorizontalDivider()
            Section("System text-to-speech")
            Text(
                "Murmur can be your phone's TTS engine, so TalkBack, Chrome, e-book readers and other apps use these voices. " +
                    "Choose Murmur as the preferred engine in Android settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = {
                val intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }) { Text("Open text-to-speech settings") }

            var menu by remember { mutableStateOf(false) }
            val packs = c.voices.catalog.voices.filter { it.id in installed }
            val label = systemVoice?.let { sel -> packs.firstOrNull { it.id == sel.packId }?.name } ?: "Same as reading voice"
            Text("Voice for other apps: $label", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { menu = true }) { Text("Change") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Same as reading voice") }, onClick = { c.settings.setSystemVoice(null); menu = false })
                packs.forEach { p ->
                    DropdownMenuItem(text = { Text(p.name) }, onClick = {
                        c.settings.setSystemVoice(VoiceSelection(p.id, p.defaultSpeaker))
                        menu = false
                    })
                }
            }

            HorizontalDivider()
            Section("About")
            Text(
                "Murmur ${BuildConfig.VERSION_NAME} — free and open source (GPL-3.0). Speech runs on-device with " +
                    "sherpa-onnx and ONNX Runtime; no text or audio leaves your phone. Voice models are by their " +
                    "respective authors under the licenses shown on the Voices screen.",
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
