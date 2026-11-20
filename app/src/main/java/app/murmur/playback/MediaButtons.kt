package app.murmur.playback

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.murmur.settings.Settings
import app.murmur.ui.screens.formatSpeed
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.abs

/**
 * Murmur's own buttons in the system media player (notification, lock screen, watch, car):
 * Slower and Faster, which move one step through [SPEED_STEPS], and Rewind by the distance set in
 * Settings. Each speed button shows the speed it moves to.
 *
 * Android's own media controls (notification and lock screen) have room for two of these beside
 * previous and next, so they show Slower and Faster; Rewind appears where there's more room.
 */
@OptIn(UnstableApi::class)
object MediaButtons {
    private const val ACTION_REWIND = "app.murmur.REWIND"
    private const val ACTION_SLOWER = "app.murmur.SLOWER"
    private const val ACTION_FASTER = "app.murmur.FASTER"
    /** The phone's lock screen and quick settings media controls. */
    private const val SYSTEM_UI = "com.android.systemui"
    private val PLAY_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)
    private val rewindCommand = SessionCommand(ACTION_REWIND, Bundle.EMPTY)
    private val slowerCommand = SessionCommand(ACTION_SLOWER, Bundle.EMPTY)
    private val fasterCommand = SessionCommand(ACTION_FASTER, Bundle.EMPTY)

    /** Speeds the Slower and Faster buttons move through; each one has a matching Media3 icon. */
    val SPEED_STEPS = listOf(0.5f, 0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)

    /** The step below [current], or null at the slowest. */
    fun slowerSpeed(current: Float): Float? = SPEED_STEPS.lastOrNull { it < current - 0.01f }

    /** The step above [current], or null at the fastest. */
    fun fasterSpeed(current: Float): Float? = SPEED_STEPS.firstOrNull { it > current + 0.01f }

    fun buttons(rewindSeconds: Int, speed: Float): List<CommandButton> = listOfNotNull(
        slowerSpeed(speed)?.let {
            CommandButton.Builder(speedIcon(it))
                .setSessionCommand(slowerCommand)
                .setDisplayName("Slower, ${formatSpeed(it)}")
                .build()
        },
        fasterSpeed(speed)?.let {
            CommandButton.Builder(speedIcon(it))
                .setSessionCommand(fasterCommand)
                .setDisplayName("Faster, ${formatSpeed(it)}")
                .build()
        },
        CommandButton.Builder(rewindIcon(rewindSeconds))
            .setSessionCommand(rewindCommand)
            .setDisplayName("Rewind $rewindSeconds seconds")
            .build(),
    )

    private fun rewindIcon(seconds: Int): Int = when (seconds) {
        5 -> CommandButton.ICON_SKIP_BACK_5
        10 -> CommandButton.ICON_SKIP_BACK_10
        15 -> CommandButton.ICON_SKIP_BACK_15
        30 -> CommandButton.ICON_SKIP_BACK_30
        else -> CommandButton.ICON_SKIP_BACK
    }

    private fun speedIcon(speed: Float): Int {
        fun near(v: Float) = abs(speed - v) < 0.01f
        return when {
            near(0.5f) -> CommandButton.ICON_PLAYBACK_SPEED_0_5
            near(0.8f) -> CommandButton.ICON_PLAYBACK_SPEED_0_8
            near(1.0f) -> CommandButton.ICON_PLAYBACK_SPEED_1_0
            near(1.2f) -> CommandButton.ICON_PLAYBACK_SPEED_1_2
            near(1.5f) -> CommandButton.ICON_PLAYBACK_SPEED_1_5
            near(1.8f) -> CommandButton.ICON_PLAYBACK_SPEED_1_8
            near(2.0f) -> CommandButton.ICON_PLAYBACK_SPEED_2_0
            else -> CommandButton.ICON_PLAYBACK_SPEED
        }
    }

    /** Lets controllers (including the system notification) use Murmur's buttons. */
    class Callback(
        private val reader: Reader,
        private val settings: Settings,
        private val ownPackage: String,
    ) : MediaSession.Callback {
        /**
         * Headphones, car stereos and Android Auto often send Play when they connect. After a long
         * pause that isn't the listener asking, so only Murmur itself and the phone's own media
         * controls (lock screen, quick settings) can start playback again.
         */
        private fun ignorePlayFrom(packageName: String): Boolean =
            packageName != ownPackage && packageName != SYSTEM_UI &&
                !reader.state.value.playWhenReady && reader.idleMillis() > Reader.DEVICE_PLAY_LIMIT_MS

        /** Media keys from headphones and Bluetooth devices arrive here, with the sender's package. */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            val isPlay = key?.keyCode in PLAY_KEYS
            // Returning true marks the key as handled, so it's ignored.
            if (isPlay && ignorePlayFrom(controllerInfo.packageName)) return true
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        /** Play from other apps' controllers, such as Android Auto and Bluetooth transport controls. */
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            if (playerCommand == Player.COMMAND_PLAY_PAUSE && ignorePlayFrom(controller.packageName)) {
                return SessionError.ERROR_PERMISSION_DENIED
            }
            @Suppress("DEPRECATION")
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(rewindCommand)
                .add(slowerCommand)
                .add(fasterCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(buttons(settings.rewindSeconds.value, reader.state.value.speed))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_REWIND -> reader.skipSeconds(-settings.rewindSeconds.value)
                ACTION_SLOWER -> slowerSpeed(reader.state.value.speed)?.let(reader::setSpeed)
                ACTION_FASTER -> fasterSpeed(reader.state.value.speed)?.let(reader::setSpeed)
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
