package app.murmur.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.murmur.container
import app.murmur.playback.PlaybackService
import app.murmur.text.SentenceSegmenter
import app.murmur.ui.screens.LibraryScreen
import app.murmur.ui.screens.ReaderScreen
import app.murmur.ui.screens.SettingsScreen
import app.murmur.ui.screens.VoicesScreen
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pendingRoute = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleIntent(intent)

        setContent {
            MurmurTheme {
                val nav = rememberNavController()
                val start = if (container.voices.hasAnyInstalled()) ROUTE_LIBRARY else ROUTE_VOICES
                LaunchedEffect(pendingRoute.value) {
                    pendingRoute.value?.let { route ->
                        nav.navigate(route) { launchSingleTop = true }
                        pendingRoute.value = null
                    }
                }
                NavHost(navController = nav, startDestination = start) {
                    composable(ROUTE_LIBRARY) {
                        LibraryScreen(
                            onOpen = { id -> nav.navigate("$ROUTE_READER/$id") },
                            onVoices = { nav.navigate(ROUTE_VOICES) },
                            onSettings = { nav.navigate(ROUTE_SETTINGS) },
                            onNowPlaying = { nav.navigate(ROUTE_NOW_PLAYING) },
                        )
                    }
                    composable("$ROUTE_READER/{id}") { entry ->
                        ReaderScreen(docId = entry.arguments?.getString("id"), onBack = { nav.popBackStack() })
                    }
                    composable(ROUTE_NOW_PLAYING) {
                        ReaderScreen(docId = null, onBack = {
                            if (!nav.popBackStack()) nav.navigate(ROUTE_LIBRARY)
                        })
                    }
                    composable(ROUTE_VOICES) {
                        VoicesScreen(onDone = {
                            if (!nav.popBackStack()) nav.navigate(ROUTE_LIBRARY) { popUpTo(ROUTE_VOICES) { inclusive = true } }
                        })
                    }
                    composable(ROUTE_SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller keeps the session service alive while the UI is open, so the
        // media notification appears as soon as playback starts from inside the app.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { importAndPlay(it, intent.type) }
    }

    /** "Open with Murmur" for PDF/EPUB files: import, show the reader and start reading. */
    fun importAndPlay(uri: Uri, mime: String?) {
        val c = container
        lifecycleScope.launch {
            try {
                val imported = c.importer.fromUri(uri, mime)
                val count = SentenceSegmenter().segment(imported.document).size
                val meta = c.library.add(imported.document, imported.source, imported.kind, count)
                c.reader.open(meta.id, imported.document, startIndex = 0, autoplay = c.voices.hasAnyInstalled())
                pendingRoute.value = ROUTE_NOW_PLAYING
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Couldn't open file", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_LIBRARY = "library"
        const val ROUTE_READER = "reader"
        const val ROUTE_NOW_PLAYING = "now_playing"
        const val ROUTE_VOICES = "voices"
        const val ROUTE_SETTINGS = "settings"
    }
}
