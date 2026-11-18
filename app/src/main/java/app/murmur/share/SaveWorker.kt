package app.murmur.share

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.murmur.MurmurApp
import app.murmur.R
import app.murmur.library.PendingImportException
import app.murmur.playback.ShareNotifications
import app.murmur.playback.SpeakRequest
import app.murmur.playback.import
import app.murmur.library.ImportException
import kotlinx.coroutines.CancellationException

/**
 * Save for later: imports a shared item into the library without reading it. Runs as a data-sync
 * job rather than in the playback service, since nothing is played.
 */
class SaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    override suspend fun doWork(): Result {
        val request = SpeakRequest.fromData(inputData) ?: return Result.failure()
        runCatching { setForeground(foregroundInfo()) }
        val label = request.label()
        ShareNotifications.showProgress(applicationContext, label, null)
        return try {
            val outcome = request.import(applicationContext, { stage -> ShareNotifications.showProgress(applicationContext, label, stage) })
            ShareNotifications.cancelProgress(applicationContext)
            toast(if (outcome.existing) R.string.saved_page_title else R.string.saved_to_library)
            if (!outcome.existing && outcome.document.incomplete) ShareNotifications.showIncomplete(applicationContext, outcome.meta)
            Result.success()
        } catch (e: CancellationException) {
            ShareNotifications.cancelProgress(applicationContext)
            throw e
        } catch (e: Exception) {
            ShareNotifications.cancelProgress(applicationContext)
            val msg = e.message ?: applicationContext.getString(R.string.nothing_to_read)
            toast(msg)
            val link = request.linkUrl()
            when {
                e is PendingImportException -> ShareNotifications.showSavedForLater(applicationContext, msg, e.meta)
                link != null -> ShareNotifications.showError(applicationContext, request, msg, link, (e as? ImportException)?.needsSignIn == true)
            }
            Result.failure()
        } finally {
            request.sharedTextFile(applicationContext)?.delete()
            request.sharedFile(applicationContext)?.delete()
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, MurmurApp.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.saving))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun toast(res: Int) = toast(applicationContext.getString(res))

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val NOTIFICATION_ID = 1003

        fun enqueue(context: Context, request: SpeakRequest) {
            val work = OneTimeWorkRequestBuilder<SaveWorker>()
                .setInputData(request.toData(context))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(work)
        }
    }
}
