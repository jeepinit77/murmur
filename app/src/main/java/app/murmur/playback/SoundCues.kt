package app.murmur.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.murmur.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Quiet audio feedback for the share path, where there is often nothing on screen:
 * a soft ambient tone while the first audio is being prepared, and a short falling
 * two-note sound when reading can't start.
 *
 * Driven entirely by [Reader.state], so it plays for the initial start, a seek or a
 * restart, and never between sentences (buffering only goes true when a play loop starts).
 */
class SoundCues(private val reader: Reader, private val settings: Settings, private val scope: CoroutineScope) {

    private var ambience: Ambience? = null

    fun start() {
        scope.launch {
            reader.state.map { it.playWhenReady && it.buffering }.distinctUntilChanged().collectLatest { loading ->
                if (loading) {
                    // Quick starts stay silent.
                    delay(SILENT_MS)
                    if (settings.soundCues.value) startAmbience()
                } else {
                    stopAmbience()
                }
            }
        }
        scope.launch {
            var last: String? = reader.state.value.error
            reader.state.collect { s ->
                if (s.error != null && s.error != last) playError()
                last = s.error
            }
        }
    }

    /** For failures that don't reach the player, such as an import that fails while something else keeps playing. */
    fun playError() {
        if (!settings.soundCues.value) return
        scope.launch(Dispatchers.Default) {
            runCatching { playErrorTone() }.onFailure { Log.w(TAG, "Error sound failed", it) }
        }
    }

    private fun startAmbience() {
        if (ambience != null) return
        ambience = Ambience().also { it.start() }
    }

    private fun stopAmbience() {
        ambience?.fadeOut()
        ambience = null
    }

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun format() = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    /**
     * An open fifth (G3, D4, G4) with slightly detuned pairs, a faint high shimmer and a breath
     * of filtered noise. Each layer swells on its own slow, unrelated cycle, so the sound drifts
     * without a pattern you can hear repeating.
     */
    private inner class Ambience {
        @Volatile private var stopping = false
        private val thread = Thread(::run, "MurmurLoadingTone")

        fun start() = thread.start()

        fun fadeOut() {
            stopping = true
        }

        private fun run() {
            val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(format())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    // Small buffer (~60 ms) so the fade-out is heard promptly.
                    .setBufferSizeInBytes(maxOf(minBuf, RATE / 16 * 4))
                    .build()
            }.getOrElse {
                Log.w(TAG, "Loading tone unavailable", it)
                return
            }
            try {
                track.play()
                val block = FloatArray(RATE / 50) // 20 ms
                val random = Random(System.nanoTime())
                val fadeIn = 1f / (RATE * FADE_IN_S)
                val fadeOut = 1f / (RATE * FADE_OUT_S)
                var gain = 0f
                var n = 0L
                var low = 0f
                var high = 0f
                while (true) {
                    for (i in block.indices) {
                        gain = if (stopping) gain - fadeOut else min(1f, gain + fadeIn)
                        if (gain <= 0f) {
                            gain = 0f
                        }
                        val t = n++ / RATE.toDouble()
                        val swell = 0.65 + 0.35 * lfo(t, 0.071, 0.0)
                        var v = 0.0
                        v += partial(t, 196.0, 0.4) * (0.55 + 0.45 * lfo(t, 0.13, 1.1)) * 0.45
                        v += partial(t, 293.66, 0.3) * (0.5 + 0.5 * lfo(t, 0.083, 2.3)) * 0.30
                        v += partial(t, 392.0, 0.5) * (0.4 + 0.6 * lfo(t, 0.057, 0.4)) * 0.18
                        v += sin(2 * PI * 1174.66 * t) * (0.5 + 0.5 * lfo(t, 0.19, 3.0)) * 0.035
                        // Band-limited noise for "air".
                        val white = random.nextFloat() * 2f - 1f
                        low += 0.08f * (white - low)
                        high += 0.6f * (low - high)
                        v += (low - high) * 1.6 * (0.5 + 0.5 * lfo(t, 0.047, 0.9))
                        block[i] = (v * swell * VOLUME * gain * gain).toFloat()
                    }
                    var off = 0
                    while (off < block.size) {
                        val w = track.write(block, off, block.size - off, AudioTrack.WRITE_BLOCKING)
                        if (w < 0) return
                        off += w
                    }
                    if (stopping && gain <= 0f) {
                        // Let the faded tail drain, then stop.
                        Thread.sleep(80)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Loading tone failed", e)
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }

        private fun partial(t: Double, f: Double, detune: Double) =
            0.5 * (sin(2 * PI * f * t) + sin(2 * PI * (f + detune) * t))

        /** 0..1 slow sine. */
        private fun lfo(t: Double, hz: Double, phase: Double) = 0.5 + 0.5 * sin(2 * PI * hz * t + phase)
    }

    /** Two soft notes falling a major third (E5 to C5). */
    private fun playErrorTone() {
        val noteLen = (RATE * 0.17).toInt()
        val gap = (RATE * 0.03).toInt()
        val samples = FloatArray(noteLen * 2 + gap + RATE / 10)
        fun note(start: Int, f: Double) {
            for (i in 0 until noteLen) {
                val t = i / RATE.toDouble()
                val attack = min(1.0, t / 0.012)
                val env = attack * exp(-t * 9.0)
                val v = sin(2 * PI * f * t) + 0.25 * sin(2 * PI * 2 * f * t)
                samples[start + i] += (v * env * ERROR_VOLUME).toFloat()
            }
        }
        note(0, 659.25)
        note(noteLen + gap, 523.25)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(format())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 4)
            .build()
        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            Thread.sleep(samples.size * 1000L / RATE + 50)
        } finally {
            runCatching { track.release() }
        }
    }

    companion object {
        private const val TAG = "MurmurCues"
        private const val RATE = 44_100
        private const val SILENT_MS = 300L
        private const val FADE_IN_S = 0.8f
        private const val FADE_OUT_S = 0.12f
        private const val VOLUME = 0.11
        private const val ERROR_VOLUME = 0.22
    }
}
