package app.murmur.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.murmur.MurmurApp
import app.murmur.R
import app.murmur.container
import app.murmur.library.ImportException
import app.murmur.library.PendingImportException
import app.murmur.web.SharedLink
import app.murmur.web.UrlNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import app.murmur.settings.SavedPageMode
import app.murmur.settings.ShareMode
import app.murmur.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Hosts the media session (notification + lock screen controls) and performs
 * "speak now" requests from the share sheet without any UI.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = Channel<SpeakRequest>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    private var worker: Job? = null
    private var importJob: Job? = null
    private lateinit var openPlayer: PendingIntent

    override fun onCreate() {
        super.onCreate()
        val c = container
        val player = SessionPlayer(speechPlayer(this), c.reader)
        openPlayer = PendingIntent.getActivity(
            this, 0,
            // Its own action, so Android never mistakes it for the task's launch intent and
            // just brings the app forward without delivering the extras.
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_OPEN_PLAYER)
                .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_NOW_PLAYING)
                .putExtra(MainActivity.EXTRA_SHOW_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(MurmurApp.CHANNEL_PLAYBACK)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )
        val s = MediaSession.Builder(this, player)
            .setSessionActivity(openPlayer)
            .setCallback(MediaButtons.Callback(c.reader, c.settings))
            .build()
        session = s
        // Keep the Rewind and Speed buttons' icons in step with the settings.
        scope.launch {
            combine(c.settings.rewindSeconds, c.reader.state.map { it.speed }.distinctUntilChanged()) { r, sp -> r to sp }
                .collect { (rewind, speed) -> s.setMediaButtonPreferences(MediaButtons.buttons(rewind, speed)) }
        }
        // Media3 only attaches a session to the service (and so to the media notification) when
        // a controller binds. The share path never opens the UI, so no controller ever binds:
        // register it ourselves, otherwise the "Preparing" placeholder is never replaced.
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SPEAK -> {
                // We were started with startForegroundService(): promote immediately. Media3 replaces
                // this notification with the media-style one (same id) once playback state updates.
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, preparingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                intent.getIntExtra(ShareNotifications.EXTRA_DISMISS, 0).takeIf { it != 0 }
                    ?.let { ShareNotifications.cancel(this, it) }
                SpeakRequest.fromServiceIntent(intent)?.let { enqueueRequest(it) }
            }
            ACTION_SPEAK_NOTIFICATION -> {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, preparingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                val c = container
                val item = c.notificationInbox.take(intent.getIntExtra(EXTRA_BATCH, -1))
                when {
                    item == null -> stopIfIdle()
                    !c.voices.hasAnyInstalled() -> {
                        toast(getString(R.string.need_voice))
                        stopIfIdle()
                    }
                    else -> scope.launch { c.reader.interject(item.first, item.second) }
                }
            }
            ACTION_CANCEL_IMPORT -> {
                importJob?.cancel()
                ShareNotifications.cancelProgress(this)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Shared items import one at a time, in order, so sharing several links quickly keeps all of
     * them. Cancel stops only the one in progress.
     */
    private fun enqueueRequest(request: SpeakRequest) {
        pending.incrementAndGet()
        requests.trySend(request)
        if (worker == null) {
            worker = scope.launch {
                for (r in requests) {
                    pending.decrementAndGet()
                    val job = launch { handleSpeak(r) }
                    importJob = job
                    job.join()
                    importJob = null
                }
            }
        }
    }

    private suspend fun handleSpeak(request: SpeakRequest) {
        val c = container
        // In "start reading the new item" mode, a later share wins; earlier ones are queued.
        val laterShareWaiting = pending.get() > 0
        val queue = (c.settings.shareMode.value == ShareMode.QUEUE && c.reader.isBusy()) || laterShareWaiting
        val keepPlaying = !queue && c.settings.keepPlayingWhileLoading.value && c.reader.isBusy()
        val showInPlayer = !queue && !keepPlaying
        if (showInPlayer) c.reader.showPreparing(request.label())
        ShareNotifications.showProgress(this, request.label(), null)
        val onStage: (String) -> Unit = { stage -> ShareNotifications.showProgress(this, request.label(), stage) }
        val importAgain = request.importAgain || c.settings.savedPageMode.value == SavedPageMode.IMPORT_AGAIN
        try {
            val outcome = when {
                request.uri != null -> c.imports.save(c.importer.fromUri(request.uri, request.mime))
                request.textFile != null -> {
                    val f = File(request.textFile)
                    val text = f.readText()
                    f.delete()
                    c.imports.text(text, request.subject, onStage, importAgain)
                }
                request.html != null -> c.imports.save(c.importer.fromHtml(request.html, request.subject))
                else -> c.imports.text(request.text.orEmpty(), request.subject, onStage, importAgain)
            }
            ShareNotifications.cancelProgress(this)
            if (queue) {
                c.reader.enqueue(outcome.meta.id)
                toast(getString(if (outcome.existing) R.string.queued_saved else R.string.queued))
            } else {
                if (outcome.existing) toast(getString(R.string.resuming_saved))
                c.reader.open(outcome.meta.id, outcome.document, startIndex = if (outcome.existing) null else 0, autoplay = true)
                c.reader.state.value.notice?.let { toast(it) }
            }
            if (!outcome.existing && outcome.document.incomplete) {
                ShareNotifications.showIncomplete(this, outcome.meta)
            }
        } catch (e: CancellationException) {
            ShareNotifications.cancelProgress(this)
            if (showInPlayer) c.reader.clearPreparing()
            stopIfIdle()
            throw e
        } catch (e: Exception) {
            ShareNotifications.cancelProgress(this)
            val msg = e.message ?: getString(R.string.nothing_to_read)
            // The player plays the error sound itself when it shows the error.
            if (showInPlayer) c.reader.showError(msg) else c.cues.playError()
            toast(msg)
            val link = request.linkUrl()
            when {
                e is PendingImportException -> ShareNotifications.showSavedForLater(this, msg, e.meta)
                link != null -> ShareNotifications.showError(this, request, msg, link, (e as? ImportException)?.needsSignIn == true)
            }
            stopIfIdle()
        }
    }

    private fun stopIfIdle() {
        if (!container.reader.isBusy() && pending.get() == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun preparingNotification() =
        NotificationCompat.Builder(this, MurmurApp.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.preparing))
            .setContentIntent(openPlayer)
            .setOngoing(true)
            .setSilent(true)
            .build()

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!container.reader.state.value.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            release()
            session = null
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_SPEAK = "app.murmur.action.SPEAK"
        const val ACTION_CANCEL_IMPORT = "app.murmur.action.CANCEL_IMPORT"
        /** Reads a notification batch from the notification inbox. */
        const val ACTION_SPEAK_NOTIFICATION = "app.murmur.action.SPEAK_NOTIFICATION"
        private const val EXTRA_BATCH = "batch"
        const val ACTION_OPEN_PLAYER = "app.murmur.action.OPEN_PLAYER"
        /** Same id Media3's DefaultMediaNotificationProvider uses, so its notification replaces ours. */
        private const val NOTIFICATION_ID = 1001

        private var sharedPlayer: SpeechPlayer? = null

        /** One player per process, reused if the service is recreated. */
        fun speechPlayer(context: Context): SpeechPlayer =
            sharedPlayer ?: SpeechPlayer(context.container.reader, context.container.settings, context.container.appScope).also { sharedPlayer = it }

        fun speak(context: Context, request: SpeakRequest) {
            ContextCompat.startForegroundService(context, speakIntent(context, request))
        }

        fun speakNotification(context: Context, batchId: Int) {
            ContextCompat.startForegroundService(context, speakNotificationIntent(context, batchId))
        }

        fun speakNotificationIntent(context: Context, batchId: Int): Intent =
            Intent(context, PlaybackService::class.java).setAction(ACTION_SPEAK_NOTIFICATION).putExtra(EXTRA_BATCH, batchId)

        fun speakIntent(context: Context, request: SpeakRequest): Intent =
            Intent(context, PlaybackService::class.java).setAction(ACTION_SPEAK).also { request.writeTo(context, it) }
    }
}

/** What to read, carried from the share activity to the service. */
data class SpeakRequest(
    val text: String? = null,
    val html: String? = null,
    val subject: String? = null,
    val uri: Uri? = null,
    val mime: String? = null,
    /** Large text is passed through a cache file to stay under the Binder size limit. */
    val textFile: String? = null,
    /** For a link already in the library: fetch it again and replace the saved copy. */
    val importAgain: Boolean = false,
) {
    fun label(): String = subject?.takeIf { it.isNotBlank() }
        ?: linkUrl()?.let { link -> SharedLink.parse(text.orEmpty())?.titleHint ?: UrlNormalizer.displayHost(link) }
        ?: text?.trim()?.lineSequence()?.firstOrNull()?.take(60)?.takeIf { it.isNotBlank() }
        ?: "Shared item"

    /** The web address when this request is a link share. */
    fun linkUrl(): String? = text?.let { SharedLink.parse(it, subject)?.url }

    fun writeTo(context: Context, intent: Intent) {
        subject?.let { intent.putExtra(EXTRA_SUBJECT, it) }
        mime?.let { intent.putExtra(EXTRA_MIME, it) }
        if (importAgain) intent.putExtra(EXTRA_IMPORT_AGAIN, true)
        html?.let { intent.putExtra(EXTRA_HTML, it.take(MAX_INLINE)) }
        if (uri != null) {
            intent.putExtra(EXTRA_URI, uri)
            // Hand our read grant on to the service for as long as it runs.
            intent.clipData = android.content.ClipData.newRawUri("", uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (text != null) {
            if (text.length > MAX_INLINE) {
                val f = File(context.cacheDir, "share-${System.nanoTime()}.txt")
                f.writeText(text)
                intent.putExtra(EXTRA_TEXT_FILE, f.absolutePath)
            } else {
                intent.putExtra(EXTRA_TEXT, text)
            }
        }
    }

    companion object {
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_HTML = "html"
        private const val EXTRA_SUBJECT = "subject"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_TEXT_FILE = "text_file"
        private const val EXTRA_IMPORT_AGAIN = "import_again"
        private const val MAX_INLINE = 200_000

        fun fromServiceIntent(intent: Intent): SpeakRequest? {
            val r = SpeakRequest(
                text = intent.getStringExtra(EXTRA_TEXT),
                html = intent.getStringExtra(EXTRA_HTML),
                subject = intent.getStringExtra(EXTRA_SUBJECT),
                uri = IntentCompat.getParcelableExtra(intent, EXTRA_URI, Uri::class.java),
                mime = intent.getStringExtra(EXTRA_MIME),
                textFile = intent.getStringExtra(EXTRA_TEXT_FILE),
                importAgain = intent.getBooleanExtra(EXTRA_IMPORT_AGAIN, false),
            )
            return if (r.text == null && r.html == null && r.uri == null && r.textFile == null) null else r
        }
    }
}
