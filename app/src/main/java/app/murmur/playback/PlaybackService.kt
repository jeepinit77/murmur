package app.murmur.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
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
import app.murmur.library.Imports
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
            .setCallback(MediaButtons.Callback(c.reader, c.settings, packageName))
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
        // The service is exported for media controls, so other apps can reach it. Murmur's own
        // commands carry a private token; anything else is ignored.
        val action = intent?.action
        if (action in OWN_ACTIONS && !hasValidToken(this, intent!!)) {
            Log.w("MurmurPlayback", "Ignoring $action without Murmur's token")
            if (action != ACTION_CANCEL_IMPORT) {
                // Started with startForegroundService(): Android requires a foreground notification.
                ServiceCompat.startForeground(this, placeholderId(), preparingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                stopIfIdle()
            }
            return super.onStartCommand(intent, flags, startId)
        }
        when (action) {
            ACTION_SPEAK -> {
                // We were started with startForegroundService(): promote immediately. Media3 replaces
                // this notification with the media-style one (same id) once playback state updates.
                val request = SpeakRequest.fromServiceIntent(intent)
                ServiceCompat.startForeground(
                    this, placeholderId(),
                    preparingNotification(if (request?.action == ShareAction.SAVE) R.string.saving else R.string.preparing),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                intent.getIntExtra(ShareNotifications.EXTRA_DISMISS, 0).takeIf { it != 0 }
                    ?.let { ShareNotifications.cancel(this, it) }
                request?.let { enqueueRequest(it) }
            }
            ACTION_SPEAK_NOTIFICATION -> {
                ServiceCompat.startForeground(
                    this, placeholderId(), preparingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                val c = container
                // Checked before taking the message, so without a voice it stays to be played later.
                val item = if (c.voices.hasAnyInstalled()) c.notificationInbox.take(intent.getIntExtra(EXTRA_BATCH, -1)) else null
                when {
                    !c.voices.hasAnyInstalled() -> {
                        toast(getString(R.string.need_voice))
                        stopIfIdle()
                    }
                    item == null -> stopIfIdle()
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
        val save = request.action == ShareAction.SAVE
        // In "start reading the new item" mode, a later share wins; earlier ones are queued.
        val laterShareWaiting = pending.get() > 0
        val queueWhenBusy = request.action == ShareAction.QUEUE || c.settings.shareMode.value == ShareMode.QUEUE
        val queue = !save && ((queueWhenBusy && c.reader.isBusy()) || laterShareWaiting)
        val keepPlaying = !save && !queue && c.settings.keepPlayingWhileLoading.value && c.reader.isBusy()
        val showInPlayer = !save && !queue && !keepPlaying
        if (showInPlayer) c.reader.showPreparing(request.label())
        ShareNotifications.showProgress(this, request.label(), null)
        val onStage: (String) -> Unit = { stage -> ShareNotifications.showProgress(this, request.label(), stage) }
        val importAgain = request.importAgain || c.settings.savedPageMode.value == SavedPageMode.IMPORT_AGAIN
        try {
            val outcome = request.import(this, onStage, importAgain)
            ShareNotifications.cancelProgress(this)
            if (save) {
                toast(getString(if (outcome.existing) R.string.saved_page_title else R.string.saved_to_library))
                stopIfIdle()
            } else if (queue) {
                c.reader.enqueue(outcome.meta.id)
                toast(getString(if (outcome.existing) R.string.queued_saved else R.string.queued))
            } else {
                if (outcome.existing) toast(getString(R.string.resuming_saved))
                // Pausing while the page was being prepared means don't start reading it.
                val pausedWhilePreparing = showInPlayer && !c.reader.isPreparingToPlay()
                c.reader.open(outcome.meta.id, outcome.document, startIndex = if (outcome.existing) null else 0, autoplay = !pausedWhilePreparing)
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

    /**
     * The id for the "Preparing"/"Saving" notification. While an article is loaded (playing or
     * paused), its media controls use [NOTIFICATION_ID]; the placeholder gets its own id so it
     * doesn't replace them, and removing it later doesn't remove them from the lock screen.
     */
    private fun placeholderId(): Int = if (container.reader.content.value != null) PLACEHOLDER_ID else NOTIFICATION_ID

    private fun stopIfIdle() {
        if (!container.reader.isBusy() && pending.get() == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun preparingNotification(title: Int = R.string.preparing) =
        NotificationCompat.Builder(this, MurmurApp.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(title))
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
        /** "Preparing" or "Saving" while something is already loaded in the player. */
        private const val PLACEHOLDER_ID = 1002

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
            withToken(context, Intent(context, PlaybackService::class.java).setAction(ACTION_SPEAK_NOTIFICATION).putExtra(EXTRA_BATCH, batchId))

        fun speakIntent(context: Context, request: SpeakRequest): Intent =
            withToken(context, Intent(context, PlaybackService::class.java).setAction(ACTION_SPEAK).also { request.writeTo(context, it) })

        fun cancelImportIntent(context: Context): Intent =
            withToken(context, Intent(context, PlaybackService::class.java).setAction(ACTION_CANCEL_IMPORT))

        private val OWN_ACTIONS = setOf(ACTION_SPEAK, ACTION_SPEAK_NOTIFICATION, ACTION_CANCEL_IMPORT)
        private const val EXTRA_TOKEN = "app.murmur.extra.TOKEN"

        /** A random value kept in Murmur's private storage, so it also survives in saved PendingIntents. */
        private fun token(context: Context): String {
            val prefs = context.getSharedPreferences("service", Context.MODE_PRIVATE)
            prefs.getString("token", null)?.let { return it }
            val fresh = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("token", fresh).apply()
            return fresh
        }

        private fun withToken(context: Context, intent: Intent): Intent = intent.putExtra(EXTRA_TOKEN, token(context))

        private fun hasValidToken(context: Context, intent: Intent): Boolean =
            intent.getStringExtra(EXTRA_TOKEN) == token(context)
    }
}

/** Brings a shared item into the library (or finds the saved copy of a shared link). */
suspend fun SpeakRequest.import(context: Context, onStage: (String) -> Unit, importAgain: Boolean = false): Imports.Outcome {
    val c = context.container
    return when {
        uri != null -> c.imports.save(c.importer.fromUri(uri, mime))
        sharedFile != null -> {
            val f = sharedFile(context) ?: error("Nothing to read")
            try {
                // The display name after the "shared-<number>-" prefix, for the title and file type.
                c.imports.save(c.importer.fromUri(android.net.Uri.fromFile(f), mime, f.name.substringAfter('-').substringAfter('-')))
            } finally {
                f.delete()
            }
        }
        textFile != null -> {
            val f = sharedTextFile(context) ?: error("Nothing to read")
            val text = f.readText()
            f.delete()
            c.imports.text(text, subject, onStage, importAgain)
        }
        html != null -> c.imports.save(c.importer.fromHtml(html, subject))
        else -> c.imports.text(text.orEmpty(), subject, onStage, importAgain)
    }
}

/** What to do with a shared item. */
enum class ShareAction {
    /** Read it now, or queue it, depending on the Sharing settings. */
    READ,
    /** Read it after what's playing, or now if nothing is. */
    QUEUE,
    /** Save it to the library without reading it. */
    SAVE,
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
    /** A shared file copied into Murmur's cache (see [withLocalCopy]), by name; its display name follows the last "-". */
    val sharedFile: String? = null,
    /** For a link already in the library: fetch it again and replace the saved copy. */
    val importAgain: Boolean = false,
    val action: ShareAction = ShareAction.READ,
) {
    /**
     * The same request with a shared file copied into Murmur's cache. The permission to read a
     * shared file belongs to the screen it was shared to and can end when that screen closes,
     * before the service gets to it, so the service reads the copy instead.
     */
    fun withLocalCopy(context: Context): SpeakRequest {
        val shared = uri ?: return this
        val name = runCatching {
            context.contentResolver.query(shared, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: shared.lastPathSegment ?: "Document"
        val file = File(context.cacheDir, "shared-${System.nanoTime()}-${name.replace(UNSAFE_NAME_CHARS, "_").take(80)}")
        val input = context.contentResolver.openInputStream(shared) ?: return this
        input.use { i -> file.outputStream().use { i.copyTo(it) } }
        return copy(uri = null, sharedFile = file.name)
    }

    /** The copied shared file, if it has Murmur's naming and is in its cache folder. */
    fun sharedFile(context: Context): File? =
        sharedFile?.takeIf { SHARED_FILE_NAME.matches(it) }?.let { File(context.cacheDir, it) }

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
        intent.putExtra(EXTRA_ACTION, action.name)
        html?.let { intent.putExtra(EXTRA_HTML, it.take(MAX_INLINE)) }
        sharedFile?.let { intent.putExtra(EXTRA_SHARED_FILE, it) }
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
                // Only the name: the service looks for it in the cache folder, nowhere else.
                intent.putExtra(EXTRA_TEXT_FILE, f.name)
            } else {
                intent.putExtra(EXTRA_TEXT, text)
            }
        }
    }

    /** Long shared text saved by [writeTo]: a file with Murmur's naming in its cache folder, or null. */
    fun sharedTextFile(context: Context): File? =
        textFile?.takeIf { SHARE_FILE_NAME.matches(it) }?.let { File(context.cacheDir, it) }

    companion object {
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_HTML = "html"
        private const val EXTRA_SUBJECT = "subject"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_TEXT_FILE = "text_file"
        private const val EXTRA_SHARED_FILE = "shared_file"
        private val SHARED_FILE_NAME = Regex("shared-\\d+-[^/\\\\]+")
        private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._ -]")
        private const val EXTRA_IMPORT_AGAIN = "import_again"
        private const val EXTRA_ACTION = "share_action"
        private const val MAX_INLINE = 200_000
        private val SHARE_FILE_NAME = Regex("share-\\d+\\.txt")

        fun fromServiceIntent(intent: Intent): SpeakRequest? {
            val r = SpeakRequest(
                text = intent.getStringExtra(EXTRA_TEXT),
                html = intent.getStringExtra(EXTRA_HTML),
                subject = intent.getStringExtra(EXTRA_SUBJECT),
                uri = IntentCompat.getParcelableExtra(intent, EXTRA_URI, Uri::class.java),
                mime = intent.getStringExtra(EXTRA_MIME),
                textFile = intent.getStringExtra(EXTRA_TEXT_FILE),
                sharedFile = intent.getStringExtra(EXTRA_SHARED_FILE),
                importAgain = intent.getBooleanExtra(EXTRA_IMPORT_AGAIN, false),
                action = runCatching { ShareAction.valueOf(intent.getStringExtra(EXTRA_ACTION) ?: "") }
                    .getOrDefault(ShareAction.READ),
            )
            return if (r.text == null && r.html == null && r.uri == null && r.textFile == null && r.sharedFile == null) null else r
        }
    }
}
