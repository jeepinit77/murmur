package app.murmur.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.murmur.container

/**
 * Receives every notification once the user grants notification access. Only notifications from
 * apps the user chose are read; for other apps, only the package name is recorded so the app can
 * be listed in settings. Nothing from a notification's content is written to storage.
 */
class NotificationReaderService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        if (!container.settings.notificationReading.value) {
            requestUnbind()
            return
        }
        // List apps with notifications showing now, but don't read what was already there.
        runCatching { activeNotifications }.getOrNull()?.forEach { if (readable(it)) container.settings.addSeenApp(it.packageName) }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val c = container
        if (!c.settings.notificationReading.value || !readable(sbn)) return
        c.settings.addSeenApp(sbn.packageName)
        if (sbn.packageName !in c.settings.readApps.value) return
        extract(this, sbn)?.let { c.notificationInbox.offer(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        container.notificationInbox.sourceRemoved(sbn.key)
    }

    /** Skips Murmur's own notifications, ongoing ones (music, navigation, downloads) and group summaries. */
    private fun readable(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName || sbn.isOngoing) return false
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return false
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
        return true
    }

    companion object {
        private var instance: NotificationReaderService? = null

        fun component(context: Context) = ComponentName(context, NotificationReaderService::class.java)

        fun hasAccess(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** Connects or disconnects the listener after the setting changes. */
        fun applySetting(context: Context, on: Boolean) {
            if (on) {
                if (hasAccess(context)) runCatching { requestRebind(component(context)) }
            } else {
                context.container.notificationInbox.clear()
                instance?.let { runCatching { it.requestUnbind() } }
            }
        }

        /** Takes the fullest text the app put in the notification, which is often more than it shows. */
        fun extract(context: Context, sbn: StatusBarNotification): IncomingNotification? {
            val n = sbn.notification
            val extras = n.extras
            val appName = appLabel(context, sbn.packageName)
            val conversationKey = sbn.packageName + "|" + (n.shortcutId ?: sbn.key)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            if (style != null && style.messages.isNotEmpty()) {
                val messages = style.messages.mapNotNull { m ->
                    val sender = m.person?.name?.toString()
                    val text = m.text?.toString()?.trim().orEmpty()
                    val c = MessageKinds.classify(text, m.dataMimeType)
                    when {
                        c.kind != MessageKind.TEXT -> IncomingMessage(
                            sender, text.ifEmpty { c.kind.label.replaceFirstChar { it.uppercase() } }, m.timestamp, c.kind, c.detail,
                        )
                        text.isEmpty() -> null
                        else -> IncomingMessage(sender, text, m.timestamp)
                    }
                }
                if (messages.isEmpty()) return null
                return IncomingNotification(
                    packageName = sbn.packageName,
                    appName = appName,
                    sourceKey = sbn.key,
                    conversationKey = conversationKey,
                    messages = messages,
                    conversationTitle = style.conversationTitle?.toString(),
                    isGroup = style.isGroupConversation,
                    selfName = style.user.name?.toString(),
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                )
            }
            val title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE))
                ?.toString()?.trim()
            val body = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n\n")
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                )?.trim()
            if (title.isNullOrEmpty() && body.isNullOrEmpty()) return null
            return IncomingNotification(
                packageName = sbn.packageName,
                appName = appName,
                sourceKey = sbn.key,
                conversationKey = sbn.packageName + "|" + sbn.key,
                title = title,
                body = body,
            )
        }

        private val labels = HashMap<String, String>()

        fun appLabel(context: Context, pkg: String): String = labels.getOrPut(pkg) {
            runCatching {
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
    }
}
