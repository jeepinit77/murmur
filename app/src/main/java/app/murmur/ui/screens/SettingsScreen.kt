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
