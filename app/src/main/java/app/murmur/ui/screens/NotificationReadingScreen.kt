package app.murmur.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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

    // Access can change in system settings while this screen is in the background.
    var hasAccess by remember { mutableStateOf(NotificationReaderService.hasAccess(context)) }
    LifecycleResumeEffect(Unit) {
        hasAccess = NotificationReaderService.hasAccess(context)
        onPauseOrDispose { }
    }

    val apps by produceState(emptyList<AppEntry>(), seenApps, readApps) {
        value = withContext(Dispatchers.IO) { loadApps(context, seenApps + readApps) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Notification reading") }, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Murmur adds a notification with a Play button when an app you choose sends a notification. " +
                    "Notification text stays on this phone and isn't saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

            if (enabled && !hasAccess) {
                Text("Murmur doesn't have notification access.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { openAccessSettings(context) }) { Text("Allow notification access") }
                if (Build.VERSION.SDK_INT >= 33) {
                    Text(
                        "If the setting can't be turned on, open App info for Murmur, tap ⋮, then tap Allow restricted settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { openAppInfo(context) }) { Text("App info") }
                }
            }

            if (enabled) {
                HorizontalDivider()
                Section("Apps")
                if (apps.isEmpty()) {
                    Text(
                        "Apps appear here after they send a notification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (apps.isNotEmpty()) {
                    Text(
                        "Turn on Auto-play for an app to read its notifications as soon as they arrive, without pressing Play.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                apps.forEach { app ->
                    val checked = app.pkg in readApps
                    val autoPlay = checked && app.pkg in autoPlayApps
                    AppRow(
                        app = app,
                        checked = checked,
                        onCheckedChange = { on ->
                            s.setReadApp(app.pkg, on)
                            if (!on) s.setAutoPlayApp(app.pkg, false)
                        },
                        autoPlay = if (checked) autoPlay else null,
                        onAutoPlayChange = { on -> s.setAutoPlayApp(app.pkg, on) },
                    )
                    if (autoPlay) {
                        val rule = autoPlayRules[app.pkg] ?: AutoPlayRule()
                        AutoPlayOptions(rule) { s.setAutoPlayRule(app.pkg, it) }
                    }
                }
            }

            if (hasAccess) {
                HorizontalDivider()
                Text(
                    "To remove notification access, turn it off in notification access settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { openAccessSettings(context) }) { Text("Notification access settings") }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** Null when auto-play doesn't apply to this row. */
    autoPlay: Boolean?,
    onAutoPlayChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
            if (app.icon != null) {
                Image(app.icon, null, Modifier.size(32.dp))
            } else {
                Spacer(Modifier.size(32.dp))
            }
            Text(app.label, Modifier.padding(start = 12.dp, end = 8.dp))
        }
        if (autoPlay != null) {
            Row(
                Modifier.toggleable(value = autoPlay, role = Role.Switch, onValueChange = onAutoPlayChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-play", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                Switch(checked = autoPlay, onCheckedChange = null)
            }
        }
    }
}

/** One app's auto-play conditions, indented under its row. */
@Composable
private fun AutoPlayOptions(rule: AutoPlayRule, onChange: (AutoPlayRule) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Text("Screen", style = MaterialTheme.typography.titleSmall)
        RadioRow("Only when the screen is on", rule.screen == AutoPlayScreen.SCREEN_ON) { onChange(rule.copy(screen = AutoPlayScreen.SCREEN_ON)) }
        RadioRow("Only when the screen is off", rule.screen == AutoPlayScreen.SCREEN_OFF) { onChange(rule.copy(screen = AutoPlayScreen.SCREEN_OFF)) }
        RadioRow("Any time", rule.screen == AutoPlayScreen.ANY) { onChange(rule.copy(screen = AutoPlayScreen.ANY)) }
    }
}

/** Installed apps among [packages], sorted by name. */
private fun loadApps(context: Context, packages: Set<String>): List<AppEntry> {
    val pm = context.packageManager
    return packages.mapNotNull { pkg ->
        val info = runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }.getOrNull() ?: return@mapNotNull null
        val icon = runCatching { pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        AppEntry(pkg, pm.getApplicationLabel(info).toString(), icon)
    }.sortedBy { it.label.lowercase() }
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
