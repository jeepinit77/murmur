package app.murmur.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.container
import app.murmur.notifications.NotificationReaderService
import app.murmur.settings.AutoPlayRule
import app.murmur.settings.AutoPlayScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

/** With more apps than this, a search field appears above the list. */
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
    val autoPlayRules by s.autoPlayRules.collectAsStateWithLifecycle()
    val lastNotified by s.lastNotified.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    // Only one app's auto-play settings are open at a time.
    var expandedApp by rememberSaveable { mutableStateOf<String?>(null) }

    // Access can change in system settings while this screen is in the background.
    var hasAccess by remember { mutableStateOf(NotificationReaderService.hasAccess(context)) }
    LifecycleResumeEffect(Unit) {
        hasAccess = NotificationReaderService.hasAccess(context)
        onPauseOrDispose { }
    }

    // Apps that are read come first, auto-play ones at the top, then the rest by most recent
    // notification. Sorted when the list loads, not on every change, so a row doesn't move away
    // right after it's tapped.
    val loadedApps by produceState<List<AppEntry>?>(null, seenApps) {
        val read = s.readApps.value
        val auto = s.autoPlayApps.value
        val last = s.lastNotified.value
        value = withContext(Dispatchers.IO) {
            loadApps(context, seenApps + read).sortedWith(
                compareBy<AppEntry> {
                    when (it.pkg) {
                        in auto -> 0
                        in read -> 1
                        else -> 2
                    }
                }.thenByDescending { last[it.pkg] ?: 0L }.thenBy { it.label.lowercase() },
            )
        }
    }
    val apps = loadedApps.orEmpty()
    val shownApps = if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }

    Scaffold(topBar = { TopAppBar(title = { Text("Notification reading") }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsGroup(
                note = "Murmur adds a notification with a Play button when an app you choose sends a notification. " +
                    "Notification text stays on this phone and isn't saved.",
            ) {
                SwitchRow(
                    title = "Read notifications",
                    description = "Requires notification access, which lets Murmur see all notifications. Only the apps you choose are read.",
                    checked = enabled,
                    onChange = { on ->
                        s.setNotificationReading(on)
                        NotificationReaderService.applySetting(context, on)
                        if (on && !hasAccess) openAccessSettings(context)
                    },
                )
            }

            if (enabled && !hasAccess) {
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
            if (enabled && loadedApps != null) {
                SettingsGroup(
                    "Apps",
                    note = if (apps.isEmpty()) {
                        null
                    } else {
                        "Turn on Auto-play for an app to read its notifications as soon as they arrive, without pressing Play."
                    },
                    header = if (apps.size > SEARCH_THRESHOLD) {
                        { AppSearchField(query, onChange = { query = it }) }
                    } else {
                        null
                    },
                ) {
                    if (apps.isEmpty()) {
                        SettingsNote("Apps appear here after they send a notification.")
                        return@SettingsGroup
                    }
                    if (shownApps.isEmpty()) SettingsNote("No matching apps")
                    shownApps.forEachIndexed { i, app ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        val checked = app.pkg in readApps
                        val autoPlay = checked && app.pkg in autoPlayApps
                        val expanded = autoPlay && expandedApp == app.pkg
                        AppRow(
                            app = app,
                            lastNotified = lastNotified[app.pkg],
                            checked = checked,
                            onCheckedChange = { on ->
                                s.setReadApp(app.pkg, on)
                                if (!on) s.setAutoPlayApp(app.pkg, false)
                            },
                            autoPlay = if (checked) autoPlay else null,
                            onAutoPlayChange = { on ->
                                s.setAutoPlayApp(app.pkg, on)
                                // Opening the settings when auto-play is turned on shows what its conditions are.
                                expandedApp = if (on) app.pkg else expandedApp.takeIf { it != app.pkg }
                            },
                            expanded = expanded,
                            onExpandedChange = { open -> expandedApp = if (open) app.pkg else null },
                        )
                        if (expanded) {
                            val rule = autoPlayRules[app.pkg] ?: AutoPlayRule()
                            AutoPlayOptions(rule) { s.setAutoPlayRule(app.pkg, it) }
                        }
                    }
                }
            }

            if (hasAccess) {
                SettingsGroup(note = "To remove notification access, turn it off in notification access settings.") {
                    NavigationRow("Notification access settings", description = null) { openAccessSettings(context) }
                }
            }
        }
    }
}

@Composable
private fun AppSearchField(query: String, onChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    )
}

@Composable
private fun AppRow(
    app: AppEntry,
    /** When the app last posted a notification, if known. */
    lastNotified: Long?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** Null when auto-play doesn't apply to this row. */
    autoPlay: Boolean?,
    onAutoPlayChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp))
            if (app.icon != null) {
                Image(app.icon, null, Modifier.size(36.dp))
            } else {
                Spacer(Modifier.size(36.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (lastNotified != null) {
                    val now = System.currentTimeMillis()
                    Text(
                        if (now - lastNotified < DateUtils.MINUTE_IN_MILLIS) {
                            "Just now"
                        } else {
                            DateUtils.getRelativeTimeSpanString(lastNotified, now, DateUtils.MINUTE_IN_MILLIS).toString()
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        if (autoPlay == true) {
            IconToggleButton(
                checked = expanded,
                onCheckedChange = onExpandedChange,
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) { Icon(Icons.Default.Tune, contentDescription = "Auto-play settings for ${app.label}") }
        }
        if (autoPlay != null) {
            Row(
                Modifier.padding(start = 4.dp).toggleable(value = autoPlay, role = Role.Switch, onValueChange = onAutoPlayChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-play", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                Switch(checked = autoPlay, onCheckedChange = null)
            }
        }
    }
}

/** One app's auto-play conditions, in an inset panel under its row. */
@Composable
private fun AutoPlayOptions(rule: AutoPlayRule, onChange: (AutoPlayRule) -> Unit) {
    SettingsCard(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        SwitchRow(
            title = "Only with headphones or Bluetooth",
            description = "Auto-play only when audio goes to headphones, a Bluetooth device or a car.",
            checked = rule.headphonesOnly,
            onChange = { onChange(rule.copy(headphonesOnly = it)) },
        )
        SwitchRow(
            title = "Respect Do Not Disturb",
            description = "Don't auto-play while Do Not Disturb, silent or vibrate mode is on.",
            checked = rule.respectDnd,
            onChange = { onChange(rule.copy(respectDnd = it)) },
        )
        SettingsSubheading("Screen")
        RadioRow("Only when the screen is on", rule.screen == AutoPlayScreen.SCREEN_ON) { onChange(rule.copy(screen = AutoPlayScreen.SCREEN_ON)) }
        RadioRow("Only when the screen is off", rule.screen == AutoPlayScreen.SCREEN_OFF) { onChange(rule.copy(screen = AutoPlayScreen.SCREEN_OFF)) }
        RadioRow("Any time", rule.screen == AutoPlayScreen.ANY) { onChange(rule.copy(screen = AutoPlayScreen.ANY)) }
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
