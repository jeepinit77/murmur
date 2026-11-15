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
import app.murmur.library.Imported
import app.murmur.settings.ShareMode
import app.murmur.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hosts the media session (notification + lock screen controls) and performs
 * "speak now" requests from the share sheet without any UI.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speakJob: Job? = null
    private lateinit var openPlayer: PendingIntent

    override fun onCreate() {
        super.onCreate()
        val c = container
        val player = SessionPlayer(speechPlayer(this), c.reader)
        openPlayer = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_NOW_PLAYING)
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
        val s = MediaSession.Builder(this, player).setSessionActivity(openPlayer).build()
        session = s
        // Media3 only attaches a session to the service (and so to the media notification) when
        // a controller binds. The share path never opens the UI, so no controller ever binds:
        // register it ourselves, otherwise the "Preparing" placeholder is never replaced.
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SPEAK) {
            // We were started with startForegroundService(): promote immediately. Media3 replaces
            // this notification with the media-style one (same id) once playback state updates.
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, preparingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            handleSpeak(intent)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleSpeak(intent: Intent) {
        val c = container
        val request = SpeakRequest.fromServiceIntent(intent) ?: return
        val queue = c.settings.shareMode.value == ShareMode.QUEUE && c.reader.isBusy()
        if (!queue) c.reader.showPreparing(request.label())
        speakJob?.cancel()
        speakJob = scope.launch {
            try {
                val imported: Imported = when {
                    request.uri != null -> c.importer.fromUri(request.uri, request.mime)
                    request.textFile != null -> {
                        val f = File(request.textFile)
                        val text = f.readText()
                        f.delete()
                        c.importer.fromText(text, request.subject)
                    }
                    request.html != null -> c.importer.fromHtml(request.html, request.subject)
                    else -> c.importer.fromText(request.text.orEmpty(), request.subject)
                }
                val segmentCount = app.murmur.text.SentenceSegmenter().segment(imported.document).size
                val meta = c.library.add(imported.document, imported.source, imported.kind, segmentCount)
                if (queue) {
                    c.reader.enqueue(meta.id)
                    Toast.makeText(this@PlaybackService, getString(R.string.queued), Toast.LENGTH_SHORT).show()
                } else {
                    c.reader.open(meta.id, imported.document, startIndex = 0, autoplay = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: getString(R.string.nothing_to_read)
                if (!queue) c.reader.showError(msg)
                Toast.makeText(this@PlaybackService, msg, Toast.LENGTH_LONG).show()
                if (!c.reader.isBusy()) {
                    ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

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
        /** Same id Media3's DefaultMediaNotificationProvider uses, so its notification replaces ours. */
        private const val NOTIFICATION_ID = 1001

        private var sharedPlayer: SpeechPlayer? = null

        /** One player per process, reused if the service is recreated. */
        fun speechPlayer(context: Context): SpeechPlayer =
            sharedPlayer ?: SpeechPlayer(context.container.reader, context.container.appScope).also { sharedPlayer = it }

        fun speak(context: Context, request: SpeakRequest) {
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_SPEAK)
            request.writeTo(context, intent)
            ContextCompat.startForegroundService(context, intent)
        }
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
) {
    fun label(): String = subject?.takeIf { it.isNotBlank() }
        ?: text?.trim()?.lineSequence()?.firstOrNull()?.take(60)?.takeIf { it.isNotBlank() }
        ?: "Shared item"

    fun writeTo(context: Context, intent: Intent) {
        subject?.let { intent.putExtra(EXTRA_SUBJECT, it) }
        mime?.let { intent.putExtra(EXTRA_MIME, it) }
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
        private const val MAX_INLINE = 200_000

        fun fromServiceIntent(intent: Intent): SpeakRequest? {
            val r = SpeakRequest(
                text = intent.getStringExtra(EXTRA_TEXT),
                html = intent.getStringExtra(EXTRA_HTML),
                subject = intent.getStringExtra(EXTRA_SUBJECT),
                uri = IntentCompat.getParcelableExtra(intent, EXTRA_URI, Uri::class.java),
                mime = intent.getStringExtra(EXTRA_MIME),
                textFile = intent.getStringExtra(EXTRA_TEXT_FILE),
            )
            return if (r.text == null && r.html == null && r.uri == null && r.textFile == null) null else r
        }
    }
}
