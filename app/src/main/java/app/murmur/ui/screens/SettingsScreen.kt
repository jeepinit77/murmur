package app.murmur.ui.screens

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.heightIn
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.BuildConfig
import app.murmur.container
import app.murmur.settings.SavedPageMode
import app.murmur.settings.Settings
import app.murmur.settings.ShareMode
import app.murmur.web.CodeMode
import app.murmur.web.ExtractOptions
import app.murmur.web.SiteSignInActivity
import app.murmur.web.TableAiStatus
import app.murmur.web.TableMode
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The pages Settings opens. [route] is the part after "settings/" in the navigation route. */
enum class SettingsPage(val route: String, val title: String) {
    PLAYBACK("playback", "Playback"),
    APPEARANCE("appearance", "Appearance"),
    READING("reading", "Reading"),
    SHARING("sharing", "Sharing"),
    LIBRARY("library", "Library"),
    ABOUT("about", "About");

    companion object {
        fun fromRoute(route: String?): SettingsPage? = entries.firstOrNull { it.route == route }
    }
}

/**
 * Settings: a short list of categories, each with a summary of its current values, so the
 * setting someone wants is one tap away instead of somewhere in a long page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPage: (SettingsPage) -> Unit,
    onVoices: () -> Unit,
    onNotificationReading: () -> Unit,
) {
    val c = LocalContext.current.container
    val voices by c.voices.voices.collectAsStateWithLifecycle()
    val voiceId by c.settings.voice.collectAsStateWithLifecycle()
    val speed by c.settings.speed.collectAsStateWithLifecycle()
    val rewind by c.settings.rewindSeconds.collectAsStateWithLifecycle()
    val mode by c.settings.themeMode.collectAsStateWithLifecycle()
    val palette by c.settings.palette.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CategoryGroup(
                Category(Icons.Outlined.RecordVoiceOver, "Voice", voices.firstOrNull { it.id == voiceId }?.name ?: "None", onVoices),
                Category(Icons.Outlined.PlayCircle, "Playback", "${formatSpeed(speed)} · Rewind $rewind seconds") { onPage(SettingsPage.PLAYBACK) },
                Category(Icons.Outlined.Palette, "Appearance", "${mode.label} · ${palette.label}") { onPage(SettingsPage.APPEARANCE) },
            )
            CategoryGroup(
                Category(Icons.AutoMirrored.Outlined.Article, "Reading", "Web articles, code blocks, tables and website sign-ins") {
                    onPage(SettingsPage.READING)
                },
                Category(Icons.Outlined.Share, "Sharing", "What happens when you share to Murmur") { onPage(SettingsPage.SHARING) },
                Category(Icons.Outlined.LibraryBooks, "Library", "Export or import your library") { onPage(SettingsPage.LIBRARY) },
                Category(Icons.Outlined.Notifications, "Read Notifications", "Hear notifications automatically or tap to listen later.", onNotificationReading),
            )
            CategoryGroup(
                Category(Icons.Outlined.Info, "About", "Murmur ${BuildConfig.VERSION_NAME}") { onPage(SettingsPage.ABOUT) },
            )
        }
    }
}

private class Category(val icon: ImageVector, val title: String, val summary: String, val onClick: () -> Unit)

/** Categories that belong together, in one card. */
@Composable
private fun CategoryGroup(vararg items: Category) {
    SectionCard {
        items.forEachIndexed { i, item ->
            if (i > 0) SectionDivider()
            CompactNavigationRow(item.icon, item.title, item.summary, item.onClick)
        }
    }
}

/** One category's settings on its own page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    onBack: () -> Unit,
    onWelcome: () -> Unit = {},
    onAboutPage: (String) -> Unit = {},
) {
    Scaffold(topBar = { TopAppBar(title = { Text(page.title) }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = SCREEN_PADDING, end = SCREEN_PADDING, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(SCREEN_PADDING),
        ) {
            when (page) {
                SettingsPage.PLAYBACK -> PlaybackSettings()
                SettingsPage.APPEARANCE -> AppearanceSettings()
                SettingsPage.READING -> ReadingSettings()
                SettingsPage.SHARING -> SharingSettings()
                SettingsPage.ABOUT -> AboutSettings(onWelcome, onAboutPage)
                SettingsPage.LIBRARY -> LibrarySettings()
            }
        }
    }
}

@Composable
private fun PlaybackSettings() {
    val c = LocalContext.current.container
    val speed by c.settings.speed.collectAsStateWithLifecycle()
    val pause by c.settings.paragraphPauseMs.collectAsStateWithLifecycle()
    val rewind by c.settings.rewindSeconds.collectAsStateWithLifecycle()
    val soundCues by c.settings.soundCues.collectAsStateWithLifecycle()

    SettingsGroup {
        SliderRow(
            title = "Reading speed",
            value = formatSpeed(speed),
            position = speed,
            range = Settings.MIN_SPEED..Settings.MAX_SPEED,
            onChange = { c.reader.setSpeed((it * 20).roundToInt() / 20f) },
        )
        SectionDivider(start = 14.dp)
        SliderRow(
            title = "Pause between paragraphs",
            value = "$pause ms",
            position = pause.toFloat(),
            range = 0f..1500f,
            onChange = { c.settings.setParagraphPauseMs((it / 50).roundToInt() * 50) },
        )
        SectionDivider(start = 14.dp)
        ChoiceRow(
            title = "Rewind button",
            choices = Settings.REWIND_CHOICES.map { Choice(it, "$it seconds") },
            selected = rewind,
            onSelect = c.settings::setRewindSeconds,
        )
        SectionDivider(start = 14.dp)
        SwitchRow(
            title = "Loading and error sounds",
            description = "Play a quiet tone while audio is being prepared, and a short sound if reading can't start.",
            checked = soundCues,
            onChange = c.settings::setSoundCues,
        )
    }
}

@Composable
private fun SharingSettings() {
    val c = LocalContext.current.container
    val shareMode by c.settings.shareMode.collectAsStateWithLifecycle()
    val savedPageMode by c.settings.savedPageMode.collectAsStateWithLifecycle()
    val keepPlaying by c.settings.keepPlayingWhileLoading.collectAsStateWithLifecycle()

    SettingsGroup {
        ChoiceRow(
            title = "When sharing while something is playing",
            choices = ShareMode.entries.map {
                Choice(it, if (it == ShareMode.REPLACE) "Start reading the new item" else "Add it to the queue")
            },
            selected = shareMode,
            onSelect = c.settings::setShareMode,
        )
        if (shareMode == ShareMode.REPLACE) {
            SectionDivider(start = 14.dp)
            SwitchRow(
                title = "Keep playing while loading",
                description = "Keep reading the current item until the shared page is ready, instead of stopping right away.",
                checked = keepPlaying,
                onChange = c.settings::setKeepPlayingWhileLoading,
            )
        }
        SectionDivider(start = 14.dp)
        ChoiceRow(
            title = "When sharing a saved page",
            choices = SavedPageMode.entries.map { mode ->
                val label = when (mode) {
                    SavedPageMode.ASK -> "Ask"
                    SavedPageMode.RESUME -> "Resume"
                    SavedPageMode.IMPORT_AGAIN -> "Import again"
                }
                Choice(mode, label)
            },
            selected = savedPageMode,
            onSelect = c.settings::setSavedPageMode,
        )
    }
}

@Composable
private fun ReadingSettings() {
    val context = LocalContext.current
    val c = context.container
    val headings by c.settings.headingsAsChapters.collectAsStateWithLifecycle()
    val intro by c.settings.spokenIntro.collectAsStateWithLifecycle()
    val captions by c.settings.readCaptions.collectAsStateWithLifecycle()
    val tables by c.settings.tableMode.collectAsStateWithLifecycle()
    val code by c.settings.codeMode.collectAsStateWithLifecycle()
    val labelAi by c.settings.labelAiSummaries.collectAsStateWithLifecycle()
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
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        confirmClear = false
                        SiteSignInActivity.clearSignIns()
                        Toast.makeText(context, "Website sign-ins cleared", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    SettingsGroup(
        "Web articles",
        note = "These settings apply to pages you add from now on. To update a saved article, choose Reload from its menu.",
    ) {
        SwitchRow(
            title = "Headings as chapters",
            description = "Split articles at their section headings so you can skip between sections.",
            checked = headings,
            onChange = c.settings::setHeadingsAsChapters,
        )
        SectionDivider(start = 14.dp)
        SwitchRow(
            title = "Read title and author first",
            description = "After the title, read the subtitle, author, site name and publish date.",
            checked = intro,
            onChange = c.settings::setSpokenIntro,
        )
        SectionDivider(start = 14.dp)
        SwitchRow(
            title = "Read image captions",
            description = "Read figure captions and image descriptions.",
            checked = captions,
            onChange = c.settings::setReadCaptions,
        )
        SectionDivider(start = 14.dp)
        ChoiceRow(
            title = "Code blocks",
            choices = listOf(
                Choice(CodeMode.SKIP, "Skip"),
                Choice(CodeMode.NOTICE, "Say “${ExtractOptions.CODE_NOTICE}”"),
                Choice(CodeMode.READ, "Read line by line"),
            ),
            selected = code,
            onSelect = c.settings::setCodeMode,
        )
    }

    // Applies to every source that keeps tables: web pages, pasted text, HTML and text files, EPUBs.
    SettingsGroup {
        // Without a model, a saved "Summarize" choice reads row by row.
        val shownTables = if (tables == TableMode.SUMMARIZE && !canSummarize) TableMode.READ else tables
        ChoiceRow(
            title = "Tables",
            choices = listOfNotNull(
                Choice(TableMode.SKIP, "Skip"),
                Choice(TableMode.READ, "Read row by row"),
                // Left out on phones that can't run Gemini Nano, rather than shown as unavailable.
                tableAi?.takeIf { aiStatus != TableAiStatus.UNSUPPORTED && aiStatus != TableAiStatus.ERROR }?.let {
                    Choice(
                        TableMode.SUMMARIZE,
                        "Summarize with AI",
                        enabled = canSummarize,
                        description = when (aiStatus) {
                            TableAiStatus.READY -> "Uses Gemini Nano on this phone. Tables with fewer than 4 rows are read row by row."
                            TableAiStatus.NEEDS_DOWNLOAD -> "Uses Gemini Nano, which will download to this phone. Tables are read row by row until it's ready."
                            TableAiStatus.DOWNLOADING -> "Gemini Nano is downloading. Tables are read row by row until it's ready."
                            TableAiStatus.UNSUPPORTED, TableAiStatus.ERROR, null -> null
                        },
                    )
                },
            ),
            selected = shownTables,
            onSelect = { mode ->
                c.settings.setTableMode(mode)
                if (mode == TableMode.SUMMARIZE) tableAi?.prepare()
            },
        )
        if (tableAi != null) {
            if (tables == TableMode.SUMMARIZE && canSummarize) {
                SectionDivider(start = 14.dp)
                SwitchRow(
                    title = "Label summaries as AI",
                    description = "Read “Table summary by AI” before each summary.",
                    checked = labelAi,
                    onChange = c.settings::setLabelAiSummaries,
                )
            }
        }
    }

    SettingsGroup(
        "Website sign-ins",
        note = "Sites you sign in to from an article's menu stay signed in, so articles from your subscriptions import in full.",
    ) {
        ActionRow("Clear website sign-ins", destructive = true) { confirmClear = true }
    }
}

@Composable
private fun LibrarySettings() {
    val context = LocalContext.current
    val library = context.container.library
    val scope = rememberCoroutineScope()
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { library.exportTo(it) } ?: error("Couldn't save the file")
            }.onSuccess { n -> toast(if (n == 1) "Exported 1 item" else "Exported $n items") }
                .onFailure { toast(it.message ?: "Couldn't export the library") }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { library.importFrom(it) } ?: error("Couldn't open the file")
            }.onSuccess { n -> toast(if (n == 0) "Everything in that file is already in your library" else if (n == 1) "Added 1 item" else "Added $n items") }
                .onFailure { toast(it.message ?: "Couldn't import the library") }
        }
    }
    SettingsGroup(note = "Export saves every item, its reading progress and Done state to one file, to keep or move to another phone. Import adds the items from such a file; items already in your library are skipped.") {
        ActionRow("Export library") { export.launch("Murmur library ${java.time.LocalDate.now()}.zip") }
        SectionDivider(start = 14.dp)
        ActionRow("Import library") { import.launch(arrayOf("application/zip", "application/octet-stream")) }
    }
}

@Composable
private fun AboutSettings(onWelcome: () -> Unit, onAboutPage: (String) -> Unit) {
    SettingsGroup {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Version", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionDivider(start = 14.dp)
        NavigationRow("Welcome tour", description = "Choosing a voice, adding things to read, and what else Murmur does", onClick = onWelcome)
    }
    SettingsGroup {
        NavigationRow("Privacy", description = "What stays on your phone and what Murmur downloads") { onAboutPage(ABOUT_PRIVACY) }
        SectionDivider(start = 14.dp)
        NavigationRow("Open source licenses", description = null) { onAboutPage(ABOUT_LICENSES) }
        SectionDivider(start = 14.dp)
        NavigationRow("Voice credits", description = "Who made each voice, and its license") { onAboutPage(ABOUT_VOICES) }
    }
}

/** Pages opened from About. */
const val ABOUT_PRIVACY = "privacy"
const val ABOUT_LICENSES = "licenses"
const val ABOUT_VOICES = "voice-credits"
