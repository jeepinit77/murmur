package app.murmur.notifications

import android.content.Context
import android.util.Log
import app.murmur.playback.PlaybackService
import app.murmur.playback.Reader
import app.murmur.settings.Settings
import app.murmur.text.Document
import app.murmur.voice.VoiceSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Notifications waiting to be read, kept in memory only. Shows Murmur's notification for each
 * batch and, when allowed, reads new batches automatically. Use from the main thread.
 */
class NotificationInbox(
    private val context: Context,
    private val settings: Settings,
    private val voices: VoiceSources,
    private val reader: Reader,
    private val scope: CoroutineScope,
) {
    private val batcher = NotificationBatcher()
    private val autoPlayJobs = HashMap<Int, Job>()
    private val policy = AutoPlayPolicy(context, settings)

    fun offer(n: IncomingNotification) {
        val batch = batcher.offer(n) ?: return
        ReadNotifications.show(context, batch)
        if (batch.packageName in settings.autoPlayApps.value) {
            // Wait briefly so messages sent in quick succession are read together.
            autoPlayJobs.remove(batch.id)?.cancel()
            autoPlayJobs[batch.id] = scope.launch {
                delay(AUTO_PLAY_DELAY_MS)
                autoPlayJobs.remove(batch.id)
                if (batcher.get(batch.id) != null && voices.hasAnyInstalled() && policy.allows(batch.packageName)) {
                    autoPlay(batch.id)
                }
            }
        }
    }

    fun sourceRemoved(sourceKey: String) {
        batcher.removeSource(sourceKey)?.let { forget(it.id) }
    }

    /** Removes a batch and returns what to read, with a document id the library never stores. */
    fun take(id: Int): Pair<String, Document>? {
        val batch = batcher.remove(id) ?: return null
        forget(id)
        return "${Reader.EPHEMERAL_PREFIX}${batch.id}:${System.nanoTime()}" to batch.toDocument()
    }

    fun dismiss(id: Int) {
        batcher.remove(id)
        forget(id)
    }

    fun clear() {
        val ids = batcher.all().map { it.id }
        batcher.clear()
        autoPlayJobs.values.forEach { it.cancel() }
        autoPlayJobs.clear()
        ReadNotifications.cancelAll(context, ids)
    }

    fun isEmpty(): Boolean = batcher.all().isEmpty()

    private fun forget(id: Int) {
        autoPlayJobs.remove(id)?.cancel()
        ReadNotifications.cancel(context, id)
    }

    private fun autoPlay(id: Int) {
        try {
            PlaybackService.speakNotification(context, id)
        } catch (e: Exception) {
            // Android may not allow starting a foreground service from the background. The process
            // is already running for the notification listener, so read it here instead.
            Log.w(TAG, "Couldn't start the playback service; reading in place", e)
            val (docId, doc) = take(id) ?: return
            scope.launch { reader.interject(docId, doc) }
        }
    }

    companion object {
        private const val TAG = "MurmurNotifications"
        private const val AUTO_PLAY_DELAY_MS = 4_000L
    }
}
