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
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.murmur.container
import app.murmur.playback.PlaybackService
import app.murmur.ui.screens.LibraryScreen
import app.murmur.ui.theme.MurmurTheme
import app.murmur.ui.screens.NotificationReadingScreen
import app.murmur.ui.screens.ReaderScreen
import app.murmur.ui.screens.SettingsScreen
import app.murmur.ui.screens.SettingsPage
import app.murmur.ui.screens.SettingsPageScreen
import app.murmur.ui.screens.DownloadVoicesScreen
import app.murmur.ui.screens.VoicesScreen
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private fun AnimatedContentTransitionScope<NavBackStackEntry>.toReaderMode() =
    targetState.destination.route?.startsWith(MainActivity.ROUTE_READER_MODE) == true

private fun AnimatedContentTransitionScope<NavBackStackEntry>.fromReaderMode() =
    initialState.destination.route?.startsWith(MainActivity.ROUTE_READER_MODE) == true

private const val READER_MODE_FADE_MS = 220

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

        // Gemini Nano only runs while Murmur is on screen. Summarize tables postponed during
        // imports now, and whenever an item with postponed tables is saved while Murmur is open.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.library.docs.collect { docs ->
                    if (docs.any { it.pendingTables }) container.pendingTables.run()
                }
            }
        }

        setContent {
            MurmurTheme {
                val nav = rememberNavController()
                // Remembered: a changed start destination makes NavHost rebuild its graph and reset the back stack.
                val start = remember { if (container.voices.hasAnyInstalled()) ROUTE_LIBRARY else ROUTE_VOICES }
                NavHost(navController = nav, startDestination = start) {
                    composable(ROUTE_LIBRARY) {
                        LibraryScreen(
                            onOpen = { id -> nav.navigate("$ROUTE_READER/$id") },
                            onOpenFind = { id, query -> nav.navigate("$ROUTE_READER/$id?find=${Uri.encode(query)}") },
                            onVoices = { nav.navigate(ROUTE_VOICES) },
                            onSettings = { nav.navigate(ROUTE_SETTINGS) },
                            onNotificationReading = { nav.navigate(ROUTE_NOTIFICATION_READING) },
                            onNowPlaying = { nav.navigate(ROUTE_NOW_PLAYING) },
                        )
                    }
                    composable(
                        "$ROUTE_READER/{id}?find={find}",
                        arguments = listOf(navArgument("find") { type = NavType.StringType; nullable = true; defaultValue = null }),
                        exitTransition = { if (toReaderMode()) fadeOut(tween(READER_MODE_FADE_MS)) else null },
                        popEnterTransition = { if (fromReaderMode()) fadeIn(tween(READER_MODE_FADE_MS)) else null },
                    ) { entry ->
                        ReaderScreen(
                            docId = entry.arguments?.getString("id"),
                            initialFind = entry.arguments?.getString("find"),
                            onBack = { nav.popBackStack() },
                            onVoices = { nav.navigate(ROUTE_VOICES) },
                            onReaderMode = { id -> nav.navigate("$ROUTE_READER_MODE/$id") },
                            onSettings = { nav.navigate(ROUTE_SETTINGS) },
                        )
                    }
                    // Reader mode fades in and out over the article: a different view of the same
                    // article, not a new screen.
                    composable(
                        "$ROUTE_READER_MODE/{id}",
                        enterTransition = { fadeIn(tween(READER_MODE_FADE_MS)) },
                        popExitTransition = { fadeOut(tween(READER_MODE_FADE_MS)) },
                    ) { entry ->
                        ReaderScreen(
                            docId = entry.arguments?.getString("id"),
                            onBack = { nav.popBackStack() },
                            onVoices = { nav.navigate(ROUTE_VOICES) },
                            readerMode = true,
                            onSettings = { nav.navigate(ROUTE_SETTINGS) },
                        )
                    }
                    composable(
                        ROUTE_NOW_PLAYING,
                        exitTransition = { if (toReaderMode()) fadeOut(tween(READER_MODE_FADE_MS)) else null },
                        popEnterTransition = { if (fromReaderMode()) fadeIn(tween(READER_MODE_FADE_MS)) else null },
                    ) {
                        ReaderScreen(
                            docId = null,
                            onBack = { if (!nav.popBackStack()) nav.navigate(ROUTE_LIBRARY) },
                            onVoices = { nav.navigate(ROUTE_VOICES) },
                            onReaderMode = { id -> nav.navigate("$ROUTE_READER_MODE/$id") },
                            onSettings = { nav.navigate(ROUTE_SETTINGS) },
                        )
                    }
                    composable(ROUTE_VOICES) {
                        VoicesScreen(
                            onDone = {
                                if (!nav.popBackStack()) nav.navigate(ROUTE_LIBRARY) { popUpTo(ROUTE_VOICES) { inclusive = true } }
                            },
                            onDownloads = { nav.navigate(ROUTE_VOICE_DOWNLOADS) },
                        )
                    }
                    composable(ROUTE_VOICE_DOWNLOADS) { DownloadVoicesScreen(onBack = { nav.popBackStack() }) }
                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            onBack = { nav.popBackStack() },
                            onPage = { page -> nav.navigate("$ROUTE_SETTINGS/${page.route}") },
                            onVoices = { nav.navigate(ROUTE_VOICES) },
                            onNotificationReading = { nav.navigate(ROUTE_NOTIFICATION_READING) },
                        )
                    }
                    composable("$ROUTE_SETTINGS/{page}") { entry ->
                        val page = SettingsPage.fromRoute(entry.arguments?.getString("page"))
                        if (page != null) SettingsPageScreen(page, onBack = { nav.popBackStack() })
                    }
                    composable(ROUTE_NOTIFICATION_READING) { NotificationReadingScreen(onBack = { nav.popBackStack() }) }
                }
                // Routes from intents (notification, share, "open with"). Read through snapshotFlow so
                // composition never depends on pendingRoute; on a cold start, wait until NavHost has
                // set its graph, since navigating before that would be undone.
                LaunchedEffect(nav) {
                    nav.currentBackStackEntryFlow.first()
                    snapshotFlow { pendingRoute.value }.filterNotNull().collect { route ->
                        nav.navigate(route) { launchSingleTop = true }
                        pendingRoute.value = null
                    }
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
        if (intent.getBooleanExtra(EXTRA_SHOW_PLAYER, false)) container.playerExpandRequest.value = true
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { importAndPlay(it, intent.type) }
    }

    /** "Open with Murmur" for PDF/EPUB files: import, show the reader and start reading. */
    fun importAndPlay(uri: Uri, mime: String?) {
        val c = container
        lifecycleScope.launch {
            try {
                val outcome = c.imports.save(c.importer.fromUri(uri, mime))
                c.reader.open(outcome.meta.id, outcome.document, startIndex = 0, autoplay = c.voices.hasAnyInstalled())
                pendingRoute.value = ROUTE_NOW_PLAYING
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Couldn't open file", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        /** Opened from the playback notification: show the player expanded. */
        const val EXTRA_SHOW_PLAYER = "show_player"
        const val ROUTE_LIBRARY = "library"
        const val ROUTE_READER = "reader"
        const val ROUTE_READER_MODE = "reader_mode"
        const val ROUTE_NOW_PLAYING = "now_playing"
        const val ROUTE_VOICES = "voices"
        const val ROUTE_VOICE_DOWNLOADS = "voice_downloads"
        const val ROUTE_SETTINGS = "settings"
        const val ROUTE_NOTIFICATION_READING = "notification_reading"
    }
}
