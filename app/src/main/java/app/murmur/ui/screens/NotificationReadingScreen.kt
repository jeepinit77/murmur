package app.murmur.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.container
import app.murmur.notifications.NotificationReaderService
import app.murmur.notifications.SpokenParts
import app.murmur.settings.AutoPlayScreen
import app.murmur.settings.ReadingRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

/** The apps on the screen: those chosen when the list loaded, and the rest. */
private class AppLists(val chosen: List<AppEntry>, val others: List<AppEntry>) {
    val isEmpty: Boolean get() = chosen.isEmpty() && others.isEmpty()
    val size: Int get() = chosen.size + others.size
}

/** With more apps than this, a Search button appears in the top bar. */
private const val SEARCH_THRESHOLD = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationReadingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s = context.container.settings
    val enabled by s.notificationReading.collectAsStateWithLifecycle()
    val readApps by s.readApps.collectAsStateWithLifecycle()
    val autoPlayApps by s.autoPlayApps.collectAsStateWithLifecycle()
    val seenApps by s.seenApps.collectAsStateWithLifecycle()
    val readingRules by s.readingRules.collectAsStateWithLifecycle()
    val lastNotified by s.lastNotified.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    fun closeSearch() {
        searching = false
        query = ""
    }
    BackHandler(searching) { closeSearch() }
    // While searching, only matching apps are shown; keep the first of them in view as the query changes.
    LaunchedEffect(query, searching) { if (searching) scroll.animateScrollTo(0) }
    // Only one app's settings are open at a time.
    var expandedApp by rememberSaveable { mutableStateOf<String?>(null) }

    // Access can change in system settings while this screen is in the background.
    var hasAccess by remember { mutableStateOf(NotificationReaderService.hasAccess(context)) }
    LifecycleResumeEffect(Unit) {
        hasAccess = NotificationReaderService.hasAccess(context)
        onPauseOrDispose { }
    }

    // Chosen apps come first, auto-play ones at the top; each list is ordered by most recent
    // notification. Split and sorted when the list loads, not on every change, so a row doesn't
    // move away right after it's tapped.
    val loaded by produceState<AppLists?>(null, seenApps) {
        val read = s.readApps.value
        val auto = s.autoPlayApps.value
        val last = s.lastNotified.value
        value = withContext(Dispatchers.IO) {
            val sorted = loadApps(context, seenApps + read).sortedWith(
                compareBy<AppEntry> { if (it.pkg in auto) 0 else 1 }
                    .thenByDescending { last[it.pkg] ?: 0L }
                    .thenBy { it.label.lowercase() },
            )
            val (chosen, others) = sorted.partition { it.pkg in read }
            AppLists(chosen, others)
        }
    }
    fun matches(app: AppEntry) = query.isBlank() || app.label.contains(query.trim(), ignoreCase = true)

    val canSearch = enabled && (loaded?.size ?: 0) > SEARCH_THRESHOLD
    Scaffold(
        topBar = {
            if (searching) {
                SearchTopBar(query, onChange = { query = it }, onClose = ::closeSearch)
            } else {
                TopAppBar(
                    title = { Text("Notification reading") },
                    navigationIcon = { BackIcon(onBack) },
                    actions = {
                        if (canSearch) {
                            IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, contentDescription = "Search apps") }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!searching) {
                SectionCard {
                    CompactSwitchRow(
                        icon = null,
                        title = "Read notifications",
                        description = "Requires notification access. Only the apps you check are read, and notification text isn't saved.",
                        checked = enabled,
                        onChange = { on ->
                            s.setNotificationReading(on)
                            NotificationReaderService.applySetting(context, on)
                            if (on && !hasAccess) openAccessSettings(context)
                        },
                    )
                }
            }

            if (enabled && !hasAccess && !searching) {
                SettingsCard(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        "Murmur doesn't have notification access.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Button(
                        onClick = { openAccessSettings(context) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text("Allow notification access") }
                    if (Build.VERSION.SDK_INT >= 33) {
                        Text(
                            "If the setting can't be turned on, open App info for Murmur, tap ⋮, then tap Allow restricted settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        OutlinedButton(
                            onClick = { openAppInfo(context) },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        ) { Text("App info") }
                    }
                }
            }

            // Wait for the list to load so "Apps appear here" doesn't flash before it.
            val lists = loaded
            if (enabled && lists != null) {
                @Composable
                fun ColumnScope.appRows(list: List<AppEntry>) {
                    list.forEachIndexed { i, app ->
                        if (i > 0) SectionDivider(start = 92.dp)
                        val checked = app.pkg in readApps
                        val expanded = checked && expandedApp == app.pkg
                        val autoPlay = app.pkg in autoPlayApps
                        AppItem(
                            app = app,
                            lastNotified = lastNotified[app.pkg],
                            checked = checked,
                            onCheckedChange = { on ->
                                s.setReadApp(app.pkg, on)
                                if (!on) {
                                    s.setAutoPlayApp(app.pkg, false)
                                    if (expandedApp == app.pkg) expandedApp = null
                                }
                            },
                            autoPlay = autoPlay,
                            expanded = expanded,
                            onExpandedChange = { open -> expandedApp = if (open) app.pkg else null },
                        ) {
                            AppSettings(
                                autoPlay = autoPlay,
                                onAutoPlayChange = { s.setAutoPlayApp(app.pkg, it) },
                                rule = readingRules[app.pkg] ?: s.readingRule(app.pkg),
                                onRuleChange = { s.setReadingRule(app.pkg, it) },
                            )
                        }
                    }
                }

                // With nothing chosen yet, every app is in the first list.
                val first = (if (lists.chosen.isEmpty()) lists.others else lists.chosen).filter(::matches)
                val second = (if (lists.chosen.isEmpty()) emptyList() else lists.others).filter(::matches)
                when {
                    lists.isEmpty -> SectionCard("Apps") { SettingsNote("Apps appear here after they send a notification.") }
                    first.isEmpty() && second.isEmpty() -> SectionCard("Apps") { SettingsNote("No matching apps") }
                    else -> {
                        // Sections without matches are left out while searching.
                        if (first.isNotEmpty()) SectionCard("Apps") { appRows(first) }
                        if (second.isNotEmpty()) SectionCard("Other apps") { appRows(second) }
                    }
                }
            }

            if (hasAccess && !searching) {
                SectionCard {
                    CompactNavigationRow(icon = null, title = "Notification access settings") { openAccessSettings(context) }
                }
            }
        }
    }
}

/** The top bar while searching: a back arrow that closes the search, and the search field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onChange,
                placeholder = { Text("Search apps") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
            }
        },
    )
}

/** One app's row, with its settings below it when [expanded]. */
@Composable
private fun AppItem(
    app: AppEntry,
    /** When the app last posted a notification, if known. */
    lastNotified: Long?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    autoPlay: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    settings: @Composable () -> Unit,
) {
    val background = if (expanded) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier
    Column(background) {
        Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp))
                if (app.icon != null) {
                    Image(app.icon, null, Modifier.size(32.dp))
                } else {
                    Spacer(Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                    Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val detail = listOfNotNull(
                        if (checked) (if (autoPlay) "Auto-play" else "Tap to play") else null,
                        lastNotified?.let(::relativeTime),
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            if (checked) {
                IconToggleButton(
                    checked = expanded,
                    onCheckedChange = onExpandedChange,
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) { Icon(Icons.Default.Tune, contentDescription = "Settings for ${app.label}") }
            }
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { settings() }
        }
    }
}

private fun relativeTime(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < DateUtils.MINUTE_IN_MILLIS) {
        "Just now"
    } else {
        DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}

/** One app's settings, in sections under its row. */
@Composable
private fun AppSettings(
    autoPlay: Boolean,
    onAutoPlayChange: (Boolean) -> Unit,
    rule: ReadingRule,
    onRuleChange: (ReadingRule) -> Unit,
) {
    PanelSection("Reading behavior") {
        ModeOption(
            title = "Auto-play when allowed",
            description = "Read when the conditions below are met. Otherwise, show a Play button.",
            selected = autoPlay,
            onClick = { onAutoPlayChange(true) },
        )
        ModeOption(
            title = "Tap to play only",
            description = "Never read automatically. Show a Play button.",
            selected = !autoPlay,
            onClick = { onAutoPlayChange(false) },
        )
    }
    if (autoPlay) {
        PanelSection("Auto-play") {
            IconSwitchRow(Icons.Outlined.NotificationsActive, "Play a sound before reading", rule.alertSound) {
                onRuleChange(rule.copy(alertSound = it))
            }
            PanelDivider()
            IconSwitchRow(Icons.Outlined.Headphones, "Only with headphones or Bluetooth", rule.headphonesOnly) {
                onRuleChange(rule.copy(headphonesOnly = it))
            }
            PanelDivider()
            IconMenuRow(
                icon = Icons.Outlined.Smartphone,
                title = "Screen",
                value = when (rule.screen) {
                    AutoPlayScreen.SCREEN_ON -> "Only when on"
                    AutoPlayScreen.SCREEN_OFF -> "Only when off"
                    AutoPlayScreen.ANY -> "Any time"
                },
                options = listOf(
                    "Only when the screen is on" to AutoPlayScreen.SCREEN_ON,
                    "Only when the screen is off" to AutoPlayScreen.SCREEN_OFF,
                    "Any time" to AutoPlayScreen.ANY,
                ),
                selected = rule.screen,
                onSelect = { onRuleChange(rule.copy(screen = it)) },
            )
        }
        PanelSection("Don't auto-play during") {
            IconSwitchRow(Icons.Outlined.DoNotDisturbOn, "Do Not Disturb", rule.respectDnd) { onRuleChange(rule.copy(respectDnd = it)) }
            PanelDivider()
            IconSwitchRow(Icons.Outlined.NotificationsOff, "Silent mode", rule.respectSilent) { onRuleChange(rule.copy(respectSilent = it)) }
            PanelDivider()
            IconSwitchRow(Icons.Outlined.Vibration, "Vibrate mode", rule.respectVibrate) { onRuleChange(rule.copy(respectVibrate = it)) }
        }
    }
    PanelSection("What to read") {
        IconMenuRow(
            icon = Icons.Outlined.ChatBubbleOutline,
            title = null,
            value = spokenPartsLabel(rule.parts),
            options = SpokenParts.entries.map { spokenPartsLabel(it) to it },
            selected = rule.parts,
            onSelect = { onRuleChange(rule.copy(parts = it)) },
        )
    }
}

private fun spokenPartsLabel(parts: SpokenParts) = when (parts) {
    SpokenParts.MESSAGE -> "Message"
    SpokenParts.SENDER_AND_MESSAGE -> "Sender and message"
    SpokenParts.APP_SENDER_AND_MESSAGE -> "App, sender and message"
}

@Composable
private fun PanelSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionCard(title, color = MaterialTheme.colorScheme.surfaceContainerLowest, content = content)
}

@Composable
private fun PanelDivider() = SectionDivider()

@Composable
private fun ModeOption(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val highlight = if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)) else Modifier
    Row(
        Modifier
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .fillMaxWidth()
            .then(highlight)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(start = 8.dp, end = 10.dp).size(20.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IconSwitchRow(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    CompactSwitchRow(icon, title, checked = checked, onChange = onChange)
}

/** A row showing the current choice; tapping it opens a menu of [options]. With no [title], the value is the label. */
@Composable
private fun <T> IconMenuRow(
    icon: ImageVector,
    title: String?,
    value: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .heightIn(min = 44.dp)
                .padding(start = 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(title ?: value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
            if (title != null) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (label, option) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { RadioButton(selected = option == selected, onClick = null) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Installed apps among [packages], unsorted. */
private fun loadApps(context: Context, packages: Set<String>): List<AppEntry> {
    val pm = context.packageManager
    return packages.mapNotNull { pkg ->
        val info = runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }.getOrNull() ?: return@mapNotNull null
        val icon = runCatching { pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        AppEntry(pkg, pm.getApplicationLabel(info).toString(), icon)
    }
}

private fun openAccessSettings(context: Context) {
    val component = NotificationReaderService.component(context).flattenToString()
    val detail = if (Build.VERSION.SDK_INT >= 30) {
        Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(SystemSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
    } else {
        null
    }
    val list = Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    runCatching { context.startActivity(detail ?: list) }.onFailure { runCatching { context.startActivity(list) } }
}

private fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(
            Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
}
