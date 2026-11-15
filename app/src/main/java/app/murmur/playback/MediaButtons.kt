package app.murmur.playback

import android.os.Bundle
import androidx.annotation.OptIn
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
 * Rewind by the distance set in Settings, and Speed, which steps through [SPEED_STEPS].
 */
@OptIn(UnstableApi::class)
object MediaButtons {
    private const val ACTION_REWIND = "app.murmur.REWIND"
    private const val ACTION_SPEED = "app.murmur.SPEED"
    private val rewindCommand = SessionCommand(ACTION_REWIND, Bundle.EMPTY)
    private val speedCommand = SessionCommand(ACTION_SPEED, Bundle.EMPTY)

    /** Speeds the Speed button steps through; each one has a matching Media3 icon. */
    val SPEED_STEPS = listOf(1.0f, 1.2f, 1.5f, 1.8f, 2.0f)

    /** The next step above [current], wrapping back to 1×. */
    fun nextSpeed(current: Float): Float = SPEED_STEPS.firstOrNull { it > current + 0.01f } ?: SPEED_STEPS.first()

    fun buttons(rewindSeconds: Int, speed: Float): List<CommandButton> = listOf(
        CommandButton.Builder(rewindIcon(rewindSeconds))
            .setSessionCommand(rewindCommand)
            .setDisplayName("Rewind $rewindSeconds seconds")
            .build(),
        CommandButton.Builder(speedIcon(speed))
            .setSessionCommand(speedCommand)
            .setDisplayName("Speed ${formatSpeed(speed)}")
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

    /** Lets controllers (including the system notification) use the two buttons. */
    class Callback(private val reader: Reader, private val settings: Settings) : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(rewindCommand)
                .add(speedCommand)
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
                ACTION_SPEED -> reader.setSpeed(nextSpeed(reader.state.value.speed))
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
