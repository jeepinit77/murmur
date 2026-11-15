package app.murmur.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.murmur.MurmurApp
import app.murmur.R
import app.murmur.library.DocMeta
import app.murmur.ui.MainActivity
import app.murmur.web.SiteSignInActivity

/** Notifications about importing shared items: progress with Cancel, and errors with Retry. */
object ShareNotifications {
    private const val PROGRESS_ID = 1002
    private const val ERROR_BASE_ID = 2000
    const val EXTRA_DISMISS = "dismiss_notification"

    fun showProgress(context: Context, label: String, stage: String?) {
        val cancel = PendingIntent.getService(
            context, 0,
            Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_CANCEL_IMPORT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = builder(context)
            .setContentTitle(label)
            .setContentText(stage ?: context.getString(R.string.preparing))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context, MainActivity.ROUTE_NOW_PLAYING))
            .addAction(0, context.getString(R.string.cancel), cancel)
            .build()
        notify(context, PROGRESS_ID, n)
    }

    fun cancelProgress(context: Context) = manager(context).cancel(PROGRESS_ID)

    fun cancel(context: Context, id: Int) = manager(context).cancel(id)

    /** "Couldn't import page", with Retry, Open in browser and (when the site asks) Sign in. */
    fun showError(context: Context, request: SpeakRequest, message: String, url: String, needsSignIn: Boolean) {
        val id = idFor(url)
        val retryIntent = PlaybackService.speakIntent(context, request).putExtra(EXTRA_DISMISS, id)
        val retry = PendingIntent.getForegroundService(
            context, id, retryIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = builder(context)
            .setContentTitle(context.getString(R.string.import_failed))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message\n$url"))
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.retry), retry)
            .addAction(0, context.getString(R.string.open_in_browser), browser(context, url, id))
        if (needsSignIn) b.addAction(0, context.getString(R.string.sign_in), signIn(context, url, null, id))
        notify(context, id, b.build())
    }

    /** The link was saved to the library and will import when the network is back. */
    fun showSavedForLater(context: Context, message: String, meta: DocMeta) {
        val n = builder(context)
            .setContentTitle(message)
            .setContentText(context.getString(R.string.saved_for_later))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.saved_for_later)))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, MainActivity.ROUTE_LIBRARY))
            .build()
        notify(context, idFor(meta.source), n)
    }

    /** The article looks cut off by a paywall; offer to sign in and reload. */
    fun showIncomplete(context: Context, meta: DocMeta) {
        val id = idFor(meta.source)
        val n = builder(context)
            .setContentTitle(context.getString(R.string.may_be_incomplete_title))
            .setContentText(meta.title)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, MainActivity.ROUTE_NOW_PLAYING))
            .addAction(0, context.getString(R.string.sign_in), signIn(context, meta.source, meta.id, id))
            .addAction(0, context.getString(R.string.open_in_browser), browser(context, meta.source, id))
            .build()
        notify(context, id, n)
    }

    private fun builder(context: Context) = NotificationCompat.Builder(context, MurmurApp.CHANNEL_IMPORTS)
        .setSmallIcon(R.drawable.ic_notification)
        .setCategory(NotificationCompat.CATEGORY_STATUS)

    private fun browser(context: Context, url: String, requestCode: Int) = PendingIntent.getActivity(
        context, requestCode + 1,
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun signIn(context: Context, url: String, reloadId: String?, requestCode: Int) = PendingIntent.getActivity(
        context, requestCode + 2,
        SiteSignInActivity.intent(context, url, reloadId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(context: Context, route: String) = PendingIntent.getActivity(
        context, route.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun idFor(url: String) = ERROR_BASE_ID + (url.hashCode() and 0x3ff) * 4

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun notify(context: Context, id: Int, n: Notification) {
        val m = manager(context)
        if (m.areNotificationsEnabled()) runCatching { m.notify(id, n) }
    }
}
