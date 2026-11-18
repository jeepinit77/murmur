package app.murmur

import app.murmur.share.ShareShortcuts
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.murmur.library.Importer
import app.murmur.library.Imports
import app.murmur.library.PendingTableSummaries
import app.murmur.notifications.NotificationInbox
import app.murmur.notifications.NotificationReaderService
import app.murmur.web.PageFetcher
import app.murmur.web.SiteIcons
import app.murmur.web.WebImporter
import app.murmur.web.tableAi
import app.murmur.web.WebViewRenderer
import app.murmur.library.LibraryStore
import app.murmur.playback.Reader
import app.murmur.playback.SoundCues
import app.murmur.settings.Settings
import app.murmur.voice.SherpaVoiceSource
import app.murmur.voice.Synthesizer
import app.murmur.voice.VoiceRepository
import app.murmur.voice.VoiceSources
import app.murmur.voice.extraVoiceSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MurmurApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createChannels()
        // A call to the system that can be slow while the phone is busy; not needed for startup.
        container.appScope.launch(Dispatchers.IO) { ShareShortcuts.publish(this@MurmurApp) }
        // The listener unbinds itself while the setting is off; reconnect it if it's on now.
        if (container.settings.notificationReading.value) NotificationReaderService.applySetting(this, true)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Neural models hold hundreds of MB; drop them when backgrounded and idle.
        if (level >= TRIM_MEMORY_BACKGROUND && !container.reader.state.value.playWhenReady) {
            container.voices.release()
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_IMPORTS, getString(R.string.channel_imports), NotificationManager.IMPORTANCE_LOW),
        )
        // Normal importance so these appear with other notifications, not under Silent. They're
        // posted without sound or vibration, since the original notification already alerted.
        // A channel's importance can't be raised later, so this replaces the earlier low channel.
        nm.deleteNotificationChannel("notification_reading")
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NOTIFICATION_READING, getString(R.string.channel_notification_reading), NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"
        const val CHANNEL_IMPORTS = "imports"
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_NOTIFICATION_READING = "notification_reading_items"
    }
}

/** Manual dependency container; everything here is a process-wide singleton. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    val settings = Settings(context)
    /** Downloadable sherpa-onnx voice packs (Kokoro, Piper). */
    val packs = VoiceRepository(context)
    private val synthesizer = Synthesizer(packs)
    private val sherpa = SherpaVoiceSource(packs, synthesizer, appScope)
    /** Every voice the app can speak with, from all sources available in this build and on this phone. */
    val voices = VoiceSources(
        listOf(sherpa) + extraVoiceSources(context, appScope),
        sherpa, packs, settings, appScope,
    )
    val library = LibraryStore(context, appScope)
    /** On-device table summaries; null in the foss build. */
    // Import machinery is created on first use rather than at every launch.
    val tableAi by lazy { tableAi(context, appScope) }
    val importer by lazy { Importer(context, settings, tableAi) }
    val web by lazy { WebImporter(PageFetcher(http), WebViewRenderer(context), importer::readPdf, tableAi) }
    /** Site icons for library rows. */
    val siteIcons = SiteIcons(context, http, appScope)
    val imports by lazy { Imports(context, importer, web, library, settings, siteIcons) }
    val reader = Reader(context, appScope, voices, settings, library)
    /** Table summaries postponed because Gemini Nano only runs while Murmur is on screen. */
    val pendingTables by lazy { PendingTableSummaries(library, settings, tableAi, reader, appScope) }
    /** Loading and error sounds, driven by the reader's state. */
    val cues = SoundCues(reader, settings, appScope).also { it.start() }
    /** Notifications from other apps waiting to be read; kept in memory only. */
    val notificationInbox = NotificationInbox(context, settings, voices, reader, cues, appScope)
    /** Set when the app is opened from the playback notification; the player opens expanded and clears it. */
    val playerExpandRequest = MutableStateFlow(false)
}

val Context.container: AppContainer get() = (applicationContext as MurmurApp).container
