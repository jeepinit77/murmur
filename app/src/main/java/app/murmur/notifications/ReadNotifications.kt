package app.murmur.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.murmur.MurmurApp
import app.murmur.R
import app.murmur.container
import app.murmur.playback.PlaybackService

/**
 * Murmur's own notifications for notifications it can read: one per chat (or per notification
 * from other apps), grouped together, with Play and Dismiss.
 */
object ReadNotifications {
    private const val BASE_ID = 5000
    private const val SUMMARY_ID = 4999
    private const val GROUP = "app.murmur.NOTIFICATION_READING"
    const val ACTION_DISMISS = "app.murmur.action.DISMISS_NOTIFICATION_READING"
    const val EXTRA_BATCH = "batch"

    fun show(context: Context, batch: NotificationBatch) {
        val nm = manager(context)
        if (!nm.areNotificationsEnabled()) return
        val id = BASE_ID + batch.id
        val play = playIntent(context, batch.id)
        val n = NotificationCompat.Builder(context, MurmurApp.CHANNEL_NOTIFICATION_READING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(batch.heading)
            .setContentText(batch.summary)
            .setSubText(batch.appName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(batch.summary))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(GROUP)
            .setOnlyAlertOnce(true)
            .setContentIntent(play)
            .setDeleteIntent(dismissIntent(context, batch.id))
            .addAction(0, context.getString(R.string.play), play)
            .addAction(0, context.getString(R.string.dismiss), dismissIntent(context, batch.id))
            .build()
        runCatching { nm.notify(id, n) }
        showSummary(context)
    }

    fun cancel(context: Context, batchId: Int) {
        manager(context).cancel(BASE_ID + batchId)
        if (context.container.notificationInbox.isEmpty()) manager(context).cancel(SUMMARY_ID)
    }

    fun cancelAll(context: Context, batchIds: List<Int>) {
        batchIds.forEach { manager(context).cancel(BASE_ID + it) }
        manager(context).cancel(SUMMARY_ID)
    }

    private fun showSummary(context: Context) {
        val n = NotificationCompat.Builder(context, MurmurApp.CHANNEL_NOTIFICATION_READING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.channel_notification_reading))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager(context).notify(SUMMARY_ID, n) }
    }

    private fun playIntent(context: Context, batchId: Int): PendingIntent =
        PendingIntent.getForegroundService(
            context, BASE_ID + batchId * 2,
            PlaybackService.speakNotificationIntent(context, batchId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun dismissIntent(context: Context, batchId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, BASE_ID + batchId * 2 + 1,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_DISMISS).putExtra(EXTRA_BATCH, batchId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}

/** Dismiss, or swiping one of Murmur's notification-reading notifications away. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReadNotifications.ACTION_DISMISS) return
        val id = intent.getIntExtra(ReadNotifications.EXTRA_BATCH, -1)
        if (id >= 0) context.container.notificationInbox.dismiss(id)
    }
}
