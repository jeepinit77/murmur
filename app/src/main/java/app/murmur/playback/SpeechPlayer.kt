package app.murmur.playback

import kotlin.math.roundToInt
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Exposes the [Reader] as a Media3 [Player] so the media session provides the notification,
 * lock-screen, Bluetooth/headset and Android Auto controls.
 *
 * Time is synthetic: each character counts as 1/15 s at 1x, so the lock-screen progress bar
 * moves roughly with speech and scrubbing it jumps to the matching sentence.
 */
@OptIn(UnstableApi::class)
class SpeechPlayer(private val reader: Reader, scope: CoroutineScope) : SimpleBasePlayer(Looper.getMainLooper()) {

    init {
        scope.launch {
            combine(reader.state, reader.content) { s, c -> s to c }.collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val s = reader.state.value
        val content = reader.content.value
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_RELEASE,
            )
            .build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(s.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(s.speed))
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)

        if (s.docId == null) {
            return builder.setPlaybackState(Player.STATE_IDLE).setPlaylist(emptyList()).build()
        }

        val subtitle = when {
            s.error != null -> s.error
            s.segmentCount > 0 && content != null -> {
                val remaining = content.charOffsets.last() - content.charOffsets[s.currentIndex.coerceIn(0, content.segments.size)]
                "Sentence ${s.currentIndex + 1} of ${s.segmentCount} · ${timeLeft(remaining, s.speed)}"
            }
            s.segmentCount > 0 -> "Sentence ${s.currentIndex + 1} of ${s.segmentCount}"
            else -> "Preparing…"
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(subtitle)
            .setDisplayTitle(s.title)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        val durationMs = content?.let { msForChars(it.charOffsets.last()) } ?: C.TIME_UNSET
        val item = MediaItemData.Builder(s.docId)
            .setMediaItem(MediaItem.Builder().setMediaId(s.docId).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsSeekable(content != null)
            .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000)
            .build()
        val positionMs = content?.let { msForChars(it.charOffsets[s.currentIndex.coerceIn(0, it.segments.size)]) } ?: 0L

        return builder
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(positionMs)
            .setPlaybackState(
                when {
                    s.ended -> Player.STATE_ENDED
                    s.buffering -> Player.STATE_BUFFERING
                    else -> Player.STATE_READY
                },
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) reader.play() else reader.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        reader.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val content = reader.content.value
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> reader.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> reader.previous()
            Player.COMMAND_SEEK_FORWARD -> reader.skipSeconds((SKIP_MS / 1000).toInt())
            Player.COMMAND_SEEK_BACK -> reader.skipSeconds(-(SKIP_MS / 1000).toInt())
            else -> if (content != null && positionMs != C.TIME_UNSET) {
                reader.seekTo(content.indexForChar(charsForMs(positionMs)))
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        reader.setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun msForChars(chars: Int): Long = (chars * 1000f / Reader.CHARS_PER_SECOND).toLong()

    /** "12 min left" for the remaining characters at the current speed. */
    private fun timeLeft(chars: Int, speed: Float): String {
        val minutes = chars / (Reader.CHARS_PER_SECOND * 60f * speed.coerceAtLeast(0.1f))
        return when {
            minutes < 1f -> "Less than 1 min left"
            minutes < 60f -> "${minutes.roundToInt()} min left"
            else -> "${(minutes / 60).toInt()} hr ${(minutes % 60).roundToInt()} min left"
        }
    }

    private fun charsForMs(ms: Long): Int = (ms * Reader.CHARS_PER_SECOND / 1000f).toInt()

    companion object {
        private const val SKIP_MS = 15_000L
    }
}

/**
 * Media buttons on headsets and the notification send "next/previous"; with a single
 * playlist item Media3 would treat those as no-ops or restarts, so route them to sentence skips.
 */
@OptIn(UnstableApi::class)
class SessionPlayer(player: SpeechPlayer, private val reader: Reader) : ForwardingPlayer(player) {
    override fun seekToNext() = reader.next()
    override fun seekToNextMediaItem() = reader.next()
    override fun seekToPrevious() = reader.previous()
    override fun seekToPreviousMediaItem() = reader.previous()
    override fun hasNextMediaItem() = true
    override fun hasPreviousMediaItem() = true
}
