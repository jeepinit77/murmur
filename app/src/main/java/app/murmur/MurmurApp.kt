package app.murmur

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.murmur.library.Importer
import app.murmur.library.LibraryStore
import app.murmur.playback.Reader
import app.murmur.settings.Settings
import app.murmur.voice.Synthesizer
import app.murmur.voice.VoiceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MurmurApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createChannels()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Neural models hold hundreds of MB; drop them when backgrounded and idle.
        if (level >= TRIM_MEMORY_BACKGROUND && !container.reader.state.value.playWhenReady) {
            container.synthesizer.release()
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
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"
        const val CHANNEL_DOWNLOADS = "downloads"
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
    val voices = VoiceRepository(context, settings)
    val synthesizer = Synthesizer(voices)
    val library = LibraryStore(context, appScope)
    val importer = Importer(context, http)
    val reader = Reader(context, appScope, synthesizer, voices, settings, library)

    init {
        voices.refresh()
    }
}

val Context.container: AppContainer get() = (applicationContext as MurmurApp).container
