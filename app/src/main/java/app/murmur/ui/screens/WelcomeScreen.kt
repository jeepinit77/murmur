package app.murmur.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.container
import app.murmur.notifications.NotificationReaderService
import app.murmur.ui.library.MurmurMark
import app.murmur.voice.DownloadState
import app.murmur.voice.Voice
import app.murmur.voice.VoicePackage
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * The welcome flow shown on first launch (and from Settings > About): a few swipeable pages that
 * get a voice downloading, show the ways to add something to read, offer notification reading,
 * and list what else Murmur does. Every page can be skipped.
 */

private enum class WelcomePage { WELCOME, VOICE, ADD, NOTIFICATIONS, MORE }

@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val pages = WelcomePage.entries
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val page = pages[pager.currentPage]

    // The voice chosen on the Voice page starts downloading when the page is left.
    var chosenPackage by rememberSaveable { mutableStateOf<String?>(null) }
    fun startChosenDownload() {
        val pkgId = chosenPackage ?: return
        val voice = c.voices.voices.value.firstOrNull { it.pkg.id == pkgId } ?: return
        if (c.voices.voices.value.none { it.pkg.id == pkgId && it.id in c.voices.installed.value }) {
            c.voices.sourceOf(voice).download(voice)
        }
    }

    // Asked on the Notifications page, with its reason, or on finishing if that page was skipped.
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    // Updated from the permission dialog's answer, so the page shows Allowed right after.
    var notificationsAllowed by remember { mutableStateOf(!needsNotificationPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted || !needsNotificationPermission(context)
    }
    fun askForNotifications() {
        permissionAsked = true
        c.settings.notificationPermissionAsked = true
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun finish() {
        // Skip shouldn't drop the voice chosen on the Voice page.
        startChosenDownload()
        if (!permissionAsked && needsNotificationPermission(context)) askForNotifications()
        c.settings.welcomeDone = true
        onDone()
    }

    fun next() {
        if (page == WelcomePage.VOICE) startChosenDownload()
        if (pager.currentPage == pages.lastIndex) finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
    }
    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
    LaunchedEffect(pager.currentPage) {
        // Swiping past the Voice page counts as choosing it, the same as Next.
        if (pager.currentPage > WelcomePage.VOICE.ordinal) startChosenDownload()
    }

    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .background(
                Brush.verticalGradient(
                    0f to scheme.primaryContainer.copy(alpha = 0.55f),
                    0.45f to scheme.background.copy(alpha = 0f),
                    1f to scheme.tertiaryContainer.copy(alpha = 0.4f),
                ),
            ),
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp).height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageDots(pages.size, pager.currentPage, Modifier.weight(1f))
                if (page != WelcomePage.MORE) TextButton(onClick = ::finish) { Text("Skip") }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { index ->
                when (pages[index]) {
                    WelcomePage.WELCOME -> WelcomeIntro()
                    WelcomePage.VOICE -> VoicePage(chosenPackage) { chosenPackage = it }
                    WelcomePage.ADD -> AddPage()
                    WelcomePage.NOTIFICATIONS -> NotificationsPage(notificationsAllowed, ::askForNotifications)
                    WelcomePage.MORE -> MorePage(chosenPackage)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pager.currentPage > 0) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("Back") }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = ::next,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(52.dp),
                ) {
                    Text(
                        when (page) {
                            WelcomePage.WELCOME -> "Get started"
                            WelcomePage.MORE -> "Done"
                            else -> "Next"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

private fun needsNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** One dot per page; the current one stretches into a pill. */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 24.dp else 8.dp, label = "dot")
            val color by animateColorAsState(
                if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "dotColor",
            )
            Box(Modifier.size(width = width, height = 8.dp).clip(CircleShape).background(color))
        }
    }
}

// ---- Page building blocks ------------------------------------------------------------------

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** A rounded square with a soft gradient behind the page's icon. */
@Composable
private fun IconBadge(icon: ImageVector) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(32.dp), tint = scheme.onPrimary) }
}

@Composable
private fun PageTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A translucent card with a hairline border, over the page's gradient. */
@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) { Column(Modifier.padding(vertical = 8.dp), content = content) }
}

/** An icon, a title and a line of explanation. */
@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Pages ---------------------------------------------------------------------------------

@Composable
private fun WelcomeIntro() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
            contentAlignment = Alignment.Center,
        ) { MurmurMark(Modifier.size(52.dp), color = MaterialTheme.colorScheme.onPrimary) }
        Spacer(Modifier.height(32.dp))
        Text("Welcome to Murmur", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Listen to articles, documents and notifications, read aloud by voices that run on your phone.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        GlassCard {
            FeatureRow(Icons.Outlined.Lock, "Private", "Nothing you read leaves your phone.")
            FeatureRow(Icons.Outlined.Headphones, "Works in the background", "Keep listening with the screen off or in other apps.")
        }
    }
}

/** A download offered on the Voice page. */
private class VoiceChoice(val pkg: VoicePackage, val voices: List<Voice>, val reason: String)

/**
 * Up to three downloads for the phone's language: the most natural, the smallest quick-starting
 * one, and Google's voices when the phone has them.
 */
private fun voiceChoices(all: List<Voice>): List<VoiceChoice> {
    val language = Locale.getDefault().language
    val country = Locale.getDefault().toLanguageTag()
    val packages = all.filter { Locale.forLanguageTag(it.language).language == language }
        .groupBy { it.pkg.id }.values.map { it.first().pkg to it }
    if (packages.isEmpty()) return emptyList()
    val notGoogle = packages.filter { (_, v) -> v.first().engine != "Google" }
    val best = notGoogle.maxWithOrNull(compareBy<Pair<VoicePackage, List<Voice>>> { (_, v) -> v.maxOf { it.qualityRank } }
            .thenByDescending { (pkg, _) -> pkg.sizeMb ?: Int.MAX_VALUE })
    val quick = notGoogle.filter { (_, v) -> v.any { it.language == country } }.ifEmpty { notGoogle }
        .minByOrNull { (pkg, _) -> pkg.sizeMb ?: Int.MAX_VALUE }
    val google = packages.firstOrNull { (_, v) -> v.first().engine == "Google" && v.any { it.language == country } }
        ?: packages.firstOrNull { (_, v) -> v.first().engine == "Google" }
    return listOfNotNull(
        quick?.let { VoiceChoice(it.first, it.second, "Small download, starts quickly") },
        best?.takeIf { it.first.id != quick?.first?.id }?.let { VoiceChoice(it.first, it.second, "Most natural, larger download") },
        google?.let { VoiceChoice(it.first, it.second, "Uses Google's speech app on this phone") },
    )
}

@Composable
private fun VoicePage(chosen: String?, onChoose: (String) -> Unit) {
    val c = LocalContext.current.container
    val all by c.voices.voices.collectAsStateWithLifecycle()
    val installed by c.voices.installed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.voices.activateAll() }
    val choices = remember(all) { voiceChoices(all) }
    fun isInstalled(choice: VoiceChoice) = choice.voices.any { it.id in installed }
    // Preselect a voice that's already downloaded, otherwise the small one.
    LaunchedEffect(choices) {
        if (chosen == null) (choices.firstOrNull(::isInstalled) ?: choices.firstOrNull())?.let { onChoose(it.pkg.id) }
    }

    PageColumn {
        IconBadge(Icons.Outlined.RecordVoiceOver)
        PageTitle("Choose a voice", "It downloads while you finish here. You can add more voices later in Settings › Voice.")
        if (choices.isEmpty()) {
            GlassCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading voices…", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        choices.forEach { choice ->
            val selected = choice.pkg.id == chosen
            val state by remember(choice.pkg.id) { c.voices.sourceOf(choice.voices.first()).downloadState(choice.pkg) }
                .collectAsState(initial = DownloadState.Idle)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                border = BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                ),
                modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { onChoose(choice.pkg.id) },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(choice.pkg.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    if (choice.pkg.voiceCount == 1) "1 voice" else "${choice.pkg.voiceCount} voices",
                                    choice.pkg.sizeMb?.let { "$it MB" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(choice.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (isInstalled(choice)) {
                            Icon(Icons.Filled.Check, "Downloaded", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    DownloadProgress(state)
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: DownloadState) {
    when (state) {
        is DownloadState.Running -> {
            val fraction = state.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(4.dp),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp).height(4.dp), strokeCap = StrokeCap.Round)
            }
        }
        DownloadState.Queued -> Text(
            "Waiting for network…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        is DownloadState.Failed -> Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
        DownloadState.Idle -> Unit
    }
}

@Composable
private fun AddPage() {
    PageColumn {
        IconBadge(Icons.Outlined.AutoStories)
        PageTitle("Add something to read", "Murmur reads web pages, PDFs, EPUBs and text. Here's how to get them in.")
        GlassCard {
            FeatureRow(Icons.Outlined.Share, "Share from any app", "Tap Share and choose Read aloud to start right away, or Murmur for more options.")
            FeatureRow(Icons.Outlined.TouchApp, "Select text", "Choose Read aloud with Murmur in the menu that appears.")
            FeatureRow(Icons.Outlined.ContentPaste, "Copy a link", "Open Murmur and it offers to add the link you copied.")
            FeatureRow(Icons.AutoMirrored.Filled.ShortText, "Use the Add button", "Paste text, enter a web page link, or open a PDF, EPUB or text file.")
        }
    }
}

@Composable
private fun NotificationsPage(granted: Boolean, onAskPermission: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val reading by c.settings.notificationReading.collectAsStateWithLifecycle()

    PageColumn {
        IconBadge(Icons.Outlined.Notifications)
        PageTitle("Notifications", "Murmur shows playback controls in your notification shade while it reads.")
        GlassCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Notifications, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Column(Modifier.padding(start = 14.dp, end = 8.dp).weight(1f)) {
                    Text("Playback controls", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (granted) "Allowed" else "Needs permission to show notifications.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (granted) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    FilledTonalButton(onClick = onAskPermission) { Text("Allow") }
                }
            }
        }
        GlassCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(reading, role = Role.Switch) { on ->
                        c.settings.setNotificationReading(on)
                        NotificationReaderService.applySetting(context, on)
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Column(Modifier.padding(start = 14.dp, end = 8.dp).weight(1f)) {
                    Text("Read notifications aloud", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Hear messages from the apps you choose, automatically or with a tap. " +
                            "You'll choose the apps in Settings › Read Notifications.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = reading, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun MorePage(chosenPackage: String?) {
    val c = LocalContext.current.container
    val all by c.voices.voices.collectAsStateWithLifecycle()
    val installed by c.voices.installed.collectAsStateWithLifecycle()
    val chosen = all.filter { it.pkg.id == chosenPackage }
    val pkg = chosen.firstOrNull()?.pkg

    PageColumn {
        IconBadge(Icons.Outlined.Widgets)
        PageTitle("You're all set", "A few more things Murmur can do:")
        GlassCard {
            FeatureRow(Icons.Outlined.AutoStories, "Reader mode", "Full-screen reading with your choice of font, colors and spacing.")
            FeatureRow(Icons.Outlined.Speed, "Speed and skipping", "Change the reading speed, skip by sentence or chapter, and rewind.")
            FeatureRow(Icons.Outlined.Search, "Find and Listen from here", "Search an article and start reading from any sentence.")
            FeatureRow(Icons.Outlined.Bedtime, "Sleep timer", "Stop reading after a set time.")
            FeatureRow(Icons.Outlined.Headphones, "Headphone controls", "Play, pause and skip from your headphones or car.")
        }
        if (pkg != null) {
            val state by remember(pkg.id) { c.voices.sourceOf(chosen.first()).downloadState(pkg) }
                .collectAsState(initial = DownloadState.Idle)
            GlassCard {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.RecordVoiceOver, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(pkg.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                chosen.any { it.id in installed } -> "Ready"
                                state is DownloadState.Running -> "${(state as DownloadState.Running).phase}…"
                                state is DownloadState.Failed -> (state as DownloadState.Failed).message
                                else -> "Waiting to download"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (chosen.any { it.id in installed }) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
                Box(Modifier.padding(horizontal = 16.dp)) { DownloadProgress(state) }
            }
        }
    }
}
