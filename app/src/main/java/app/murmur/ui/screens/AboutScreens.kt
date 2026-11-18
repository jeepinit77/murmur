package app.murmur.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.murmur.BuildConfig
import app.murmur.container

/** A library Murmur includes, and its license. */
private class Notice(val name: String, val author: String, val license: String)

/** Every third-party component shipped in the app, in both builds unless noted. */
private val NOTICES = listOf(
    Notice("sherpa-onnx", "Xiaomi Corporation and contributors", "Apache License 2.0"),
    Notice("ONNX Runtime", "Microsoft Corporation", "MIT License"),
    Notice("eSpeak NG", "eSpeak NG contributors", "GNU General Public License v3.0"),
    Notice("PdfBox-Android", "Tom Roush and the Apache Software Foundation", "Apache License 2.0"),
    Notice("Apache Commons Compress", "The Apache Software Foundation", "Apache License 2.0"),
    Notice("OkHttp", "Square, Inc.", "Apache License 2.0"),
    Notice("jsoup", "Jonathan Hedley", "MIT License"),
    Notice("Readability4J", "dankito", "Apache License 2.0"),
    Notice("commonmark-java", "Atlassian and contributors", "BSD 2-Clause License"),
    Notice("Guava", "Google LLC", "Apache License 2.0"),
    Notice("Kotlin, kotlinx.coroutines and kotlinx.serialization", "JetBrains s.r.o. and contributors", "Apache License 2.0"),
    Notice("AndroidX, Jetpack Compose, Media3 and WorkManager", "The Android Open Source Project", "Apache License 2.0"),
    Notice("Material Design icons", "Google LLC", "Apache License 2.0"),
    Notice("Figtree", "The Figtree Project Authors", "SIL Open Font License 1.1"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = SCREEN_PADDING, end = SCREEN_PADDING, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(SCREEN_PADDING),
        ) { content() }
    }
}

@Composable
private fun NoticeRows(rows: List<Triple<String, String, String?>>) {
    SettingsCard {
        rows.forEachIndexed { i, (title, line1, line2) ->
            if (i > 0) SectionDivider(start = 14.dp)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(line1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (line2 != null) {
                    Text(line2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Open source licenses for the libraries in the app. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    TextPage("Open source licenses", onBack) {
        SettingsNote("Murmur includes the following open source software.")
        NoticeRows(NOTICES.map { Triple(it.name, it.author, it.license) })
        if (BuildConfig.FLAVOR == "play") {
            SettingsNote(
                "Google voices and table summaries use Google's ML Kit, which is covered by the Google APIs Terms of Service and ML Kit Terms.",
            )
        }
    }
}

/** Who made each downloadable voice, and its license. */
@Composable
fun VoiceCreditsScreen(onBack: () -> Unit) {
    val catalog = LocalContext.current.container.packs.catalog
    TextPage("Voice credits", onBack) {
        SettingsNote(
            "Downloadable voices are made by their authors and shared under their own licenses. " +
                "Voices marked non-commercial may only be used for personal, non-commercial listening.",
        )
        NoticeRows(catalog.voices.map { Triple(it.name, it.license, it.description.takeIf { d -> d.isNotBlank() }) })
    }
}

/** What Murmur keeps on the phone and what it fetches from the internet. */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    TextPage("Privacy", onBack) {
        val google = BuildConfig.FLAVOR == "play"
        NoticeRows(
            listOfNotNull(
                Triple("Reading aloud", "Text is turned into speech on your phone. No text or audio is sent anywhere.", null),
                Triple(
                    "Your library",
                    "Articles, documents, reading progress and settings are stored only on your phone, and in your phone's backup if backup is on.",
                    null,
                ),
                Triple(
                    "What Murmur downloads",
                    "Web pages you add, the sites' icons, and voices you choose to download. Those sites see a normal request from your phone, like a web browser's.",
                    null,
                ),
                Triple(
                    "Notification reading",
                    "Off unless you turn it on. Notification text is kept in memory only while it waits to be read, and is never saved. For other apps, only their package names are stored.",
                    null,
                ),
                if (google) {
                    Triple(
                        "Google voices and table summaries",
                        "These use Google's on-device ML Kit. Text stays on your phone, but ML Kit sends Google anonymous usage and diagnostic data.",
                        null,
                    )
                } else {
                    null
                },
                Triple("No accounts, ads or tracking", "Murmur has no accounts, ads or analytics.", null),
            ),
        )
    }
}
