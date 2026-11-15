package app.murmur.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.murmur.audio.Pcm
import app.murmur.library.LibraryStore
import app.murmur.settings.Settings
import app.murmur.text.Document
import app.murmur.text.Segment
import app.murmur.text.SentenceSegmenter
import app.murmur.text.SpeechText
import app.murmur.voice.PcmVoiceSource
import app.murmur.voice.PlayingVoiceSource
import app.murmur.voice.Voice
import app.murmur.voice.VoiceSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.min

data class ReaderState(
    val docId: String? = null,
    val title: String = "",
    val segmentCount: Int = 0,
    val currentIndex: Int = 0,
    /** The user wants audio (may still be loading the model or fetching the article). */
    val playWhenReady: Boolean = false,
    /** Waiting for the first audio: importing, loading the model or synthesizing. */
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    val speed: Float = 1f,
    val sleepAtElapsed: Long? = null,
    val queueSize: Int = 0,
    /** Something worth knowing that isn't an error, such as a missing voice for the article's language. */
    val notice: String? = null,
) {
    val isPlaying: Boolean get() = playWhenReady && !buffering && !ended
}

/** The loaded document and its segmentation, for the reader UI. */
class ReaderContent(val docId: String, val document: Document, val segments: List<Segment>) {
    /** Cumulative character count before each segment; used as a pseudo time axis. */
    val charOffsets: IntArray = IntArray(segments.size + 1).also { o ->
        segments.forEachIndexed { i, s -> o[i + 1] = o[i] + s.text.length }
    }

    fun indexForChar(char: Int): Int {
        var lo = 0
        var hi = segments.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (charOffsets[mid] <= char) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(0)
    }
}

/**
 * Streams a document as speech: synthesizes a few sentences ahead of playback on the TTS
 * thread while an [AudioTrack] plays what is ready. All public methods must be called on the
 * main thread; state is exposed as a [StateFlow].
 */
class Reader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val voices: VoiceSources,
    private val settings: Settings,
    private val library: LibraryStore,
) {
    private val _state = MutableStateFlow(ReaderState(speed = settings.speed.value))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _content = MutableStateFlow<ReaderContent?>(null)
    val content: StateFlow<ReaderContent?> = _content.asStateFlow()

    private val segmenter = SentenceSegmenter()
    private val queue = ArrayDeque<String>()
    private var playJob: Job? = null
    private var sleepJob: Job? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var trackRate = 0
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false

    // ---- Loading -------------------------------------------------------------------------

    /** Shows a placeholder while a shared item is being fetched, so controls appear at once. */
    fun showPreparing(title: String) {
        stopAudio()
        _content.value = null
        _state.update {
            it.copy(
                docId = PREPARING_ID, title = title, segmentCount = 0, currentIndex = 0,
                playWhenReady = true, buffering = true, ended = false, error = null, notice = null,
            )
        }
    }

    /** Clears the "Preparing" placeholder when an import is canceled. */
    fun clearPreparing() {
        if (_state.value.docId == PREPARING_ID) {
            _state.update { it.copy(docId = null, title = "", playWhenReady = false, buffering = false) }
            abandonFocus()
        }
    }

    fun showError(message: String) {
        stopAudio()
        if (_state.value.docId == PREPARING_ID) {
            _state.update { it.copy(docId = null, playWhenReady = false, buffering = false, error = message) }
        } else {
            _state.update { it.copy(playWhenReady = false, buffering = false, error = message) }
        }
        abandonFocus()
    }

    /** Loads a document (segmenting off the main thread) and optionally starts speaking. */
    suspend fun open(docId: String, document: Document, startIndex: Int? = null, autoplay: Boolean) {
        if (_content.value?.docId == docId) {
            if (autoplay) play()
            return
        }
        stopAudio()
        val segments = withContext(Dispatchers.Default) { segmenter.segment(document) }
        val content = ReaderContent(docId, document, segments)
        _content.value = content
        val start = (startIndex ?: library.progressOf(docId)).let { if (it >= segments.size) 0 else it }
        _state.update {
            it.copy(
                docId = docId, title = document.title, segmentCount = segments.size, currentIndex = start,
                playWhenReady = false, buffering = false, ended = false, error = null,
                notice = languageNotice(document),
            )
        }
        if (autoplay) play()
    }

    /**
     * Swaps in a new version of the open document (after a reload), continuing at [index] and
     * keeping playback going if it was.
     */
    suspend fun replaceDocument(docId: String, document: Document, index: Int) {
        if (_content.value?.docId != docId) return
        val wasPlaying = _state.value.playWhenReady
        stopAudio()
        val segments = withContext(Dispatchers.Default) { segmenter.segment(document) }
        _content.value = ReaderContent(docId, document, segments)
        _state.update {
            it.copy(
                title = document.title, segmentCount = segments.size,
                currentIndex = index.coerceIn(0, (segments.size - 1).coerceAtLeast(0)),
                ended = false, error = null, notice = languageNotice(document),
            )
        }
        if (wasPlaying) play()
    }

    /** "No voice installed for French" when the article's language has no installed voice. */
    private fun languageNotice(document: Document): String? {
        val lang = document.language ?: return null
        val current = voices.resolve() ?: return null
        if (voices.languageOf(current) == lang || voices.resolveFor(lang) != null) return null
        val name = java.util.Locale.forLanguageTag(lang).getDisplayLanguage(java.util.Locale.getDefault())
        return if (name.isBlank() || name == lang) null else "No voice installed for $name"
    }

    suspend fun openFromLibrary(docId: String, autoplay: Boolean): Boolean {
        val doc = library.load(docId) ?: return false
        open(docId, doc, autoplay = autoplay)
        return true
    }

    fun enqueue(docId: String) {
        queue.addLast(docId)
        _state.update { it.copy(queueSize = queue.size) }
    }

    /** True when something real is loaded and playing or about to play. */
    fun isBusy(): Boolean = _state.value.let { it.playWhenReady && it.docId != null && !it.ended }

    // ---- Transport -----------------------------------------------------------------------

    fun play() {
        val content = _content.value ?: return
        if (content.segments.isEmpty()) return
        if (!requestFocus()) return
        val from = if (_state.value.ended) 0 else _state.value.currentIndex
        _state.update { it.copy(playWhenReady = true, ended = false, error = null, currentIndex = from) }
        startLoop(content, from)
    }

    fun pause() {
        _state.update { it.copy(playWhenReady = false, buffering = false) }
        stopAudio()
        resumeOnFocusGain = false
    }

    fun toggle() = if (_state.value.playWhenReady) pause() else play()

    fun seekTo(index: Int) {
        val content = _content.value ?: return
        val i = index.coerceIn(0, (content.segments.size - 1).coerceAtLeast(0))
        _state.update { it.copy(currentIndex = i, ended = false) }
        library.setProgress(content.docId, i)
        if (_state.value.playWhenReady) startLoop(content, i)
    }

    fun next() = seekTo(_state.value.currentIndex + 1)

    fun previous() = seekTo(_state.value.currentIndex - 1)

    /** Jumps by roughly [seconds] of speech, using ~15 characters per second at 1x. */
    fun skipSeconds(seconds: Int) {
        val content = _content.value ?: return
        val here = content.charOffsets[_state.value.currentIndex]
        val target = (here + seconds * CHARS_PER_SECOND * _state.value.speed).toInt()
        seekTo(content.indexForChar(target.coerceAtLeast(0)))
    }

    fun nextChapter() {
        val content = _content.value ?: return
        val chapter = content.segments.getOrNull(_state.value.currentIndex)?.chapter ?: return
        content.segments.firstOrNull { it.chapter > chapter }?.let { seekTo(it.index) }
    }

    /** Jumps to the start of chapter [chapter]. */
    fun seekToChapter(chapter: Int) {
        val content = _content.value ?: return
        content.segments.firstOrNull { it.chapter >= chapter }?.let { seekTo(it.index) }
    }

    fun previousChapter() {
        val content = _content.value ?: return
        val cur = content.segments.getOrNull(_state.value.currentIndex) ?: return
        val chapterStart = content.segments.first { it.chapter == cur.chapter }.index
        val target = if (cur.index - chapterStart > 3 || cur.chapter == 0) chapterStart
        else content.segments.first { it.chapter == cur.chapter - 1 }.index
        seekTo(target)
    }

    fun setSpeed(speed: Float) {
        settings.setSpeed(speed)
        _state.update { it.copy(speed = settings.speed.value) }
        val content = _content.value
        if (_state.value.playWhenReady && content != null) startLoop(content, _state.value.currentIndex)
    }

    /** Re-synthesizes from the current sentence, e.g. after the voice changed. */
    fun restartIfPlaying() {
        val content = _content.value ?: return
        if (_state.value.playWhenReady) startLoop(content, _state.value.currentIndex)
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) {
            _state.update { it.copy(sleepAtElapsed = null) }
            return
        }
        val at = SystemClock.elapsedRealtime() + minutes * 60_000L
        _state.update { it.copy(sleepAtElapsed = at) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _state.update { it.copy(sleepAtElapsed = null) }
        }
    }

    fun close() {
        pause()
        _content.value = null
        _state.update { ReaderState(speed = it.speed) }
    }

    // ---- Streaming pipeline --------------------------------------------------------------

    private class Chunk(val index: Int, val samples: FloatArray, val sampleRate: Int)

    private fun startLoop(content: ReaderContent, from: Int) {
        playJob?.cancel()
        track?.let { releaseTrack(it) }
        _state.update { it.copy(buffering = true) }
        registerNoisy()
        playJob = scope.launch {
            val voice = voices.resolveFor(content.document.language) ?: voices.resolve()
            if (voice == null) {
                showError("Download a voice first")
                return@launch
            }
            try {
                when (val source = voices.sourceOf(voice)) {
                    is PcmVoiceSource -> streamPcm(source, voice, content, from)
                    is PlayingVoiceSource -> speakDirectly(source, voice, content, from)
                }
                onEnded(content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!coroutineContext.isActive) return@launch
                Log.e(TAG, "Playback failed", e)
                showError(e.message ?: "Speech failed")
            }
        }
    }

    /** Synthesizes a few segments ahead while an [AudioTrack] plays what is ready. */
    private suspend fun streamPcm(source: PcmVoiceSource, voice: Voice, content: ReaderContent, from: Int) = coroutineScope {
        val speed = _state.value.speed
        // Neural models sound best up to ~1.6x; go faster with pitch-preserving time-stretch.
        val modelSpeed = min(speed, MAX_MODEL_SPEED)
        val trackSpeed = speed / modelSpeed
        val pauseShort = 90
        val pausePara = settings.paragraphPauseMs.value

        val chunks = Channel<Chunk>(capacity = LOOKAHEAD)
        launch(Dispatchers.Default) {
            try {
                var first = true
                for (i in from until content.segments.size) {
                    val seg = content.segments[i]
                    val text = SpeechText.normalize(seg.text)
                    val pieces = if (first) SpeechText.splitForFastStart(text) else listOf(text)
                    first = false
                    var rate = 0
                    for (piece in pieces) {
                        if (!isSpeakable(piece)) continue
                        val audio = source.synthesize(voice, piece, modelSpeed)
                        rate = audio.sampleRate
                        chunks.send(Chunk(i, Pcm.trimSilence(audio.samples, rate), rate))
                    }
                    if (rate > 0) {
                        chunks.send(Chunk(i, Pcm.silence(rate, if (seg.endsParagraph) pausePara else pauseShort), rate))
                    }
                }
                chunks.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                chunks.close(e)
            }
        }

        withContext(Dispatchers.IO) {
            var written = 0L
            var t: AudioTrack? = null
            for (chunk in chunks) {
                if (t == null || trackRate != chunk.sampleRate) {
                    t?.let { releaseTrack(it) }
                    t = newTrack(chunk.sampleRate, trackSpeed)
                    written = 0L
                }
                if (chunk.index != _state.value.currentIndex || _state.value.buffering) {
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(currentIndex = chunk.index, buffering = false) }
                        library.setProgress(content.docId, chunk.index)
                    }
                }
                written += writeBlocking(t, chunk.samples)
            }
            // Let the tail of the last sentence play out.
            if (t != null) {
                while ((t.playbackHeadPosition.toLong() and 0xffffffffL) < written) {
                    coroutineContext.ensureActive()
                    delay(50)
                }
            }
        }
    }

    /**
     * For sources that play audio themselves: one request per sentence, highlighting each
     * sentence as its audio starts.
     */
    private suspend fun speakDirectly(source: PlayingVoiceSource, voice: Voice, content: ReaderContent, from: Int) {
        val pausePara = settings.paragraphPauseMs.value
        for (i in from until content.segments.size) {
            val seg = content.segments[i]
            val text = SpeechText.normalize(seg.text)
            if (!isSpeakable(text)) continue
            if (i != _state.value.currentIndex) {
                _state.update { it.copy(currentIndex = i) }
                library.setProgress(content.docId, i)
            }
            source.speak(voice, text, _state.value.speed) {
                if (_state.value.buffering) _state.update { it.copy(buffering = false) }
            }
            if (seg.endsParagraph && pausePara > 0) delay(pausePara.toLong())
        }
    }

    private fun isSpeakable(text: String) = text.isNotBlank() && text.any { it.isLetterOrDigit() }

    private suspend fun onEnded(content: ReaderContent) {
        library.setProgress(content.docId, 0)
        val nextId = queue.removeFirstOrNull()
        _state.update { it.copy(queueSize = queue.size) }
        if (nextId != null) {
            // Launched separately: opening cancels the current play job, which is this one.
            scope.launch { openFromLibrary(nextId, autoplay = true) }
            return
        }
        _state.update { it.copy(playWhenReady = false, buffering = false, ended = true, currentIndex = 0) }
        unregisterNoisy()
        abandonFocus()
    }

    private suspend fun writeBlocking(t: AudioTrack, samples: FloatArray): Long {
        var offset = 0
        val step = (trackRate / 10).coerceAtLeast(256) // ~100 ms slices keep pause/seek responsive
        while (offset < samples.size) {
            coroutineContext.ensureActive()
            val n = t.write(samples, offset, min(step, samples.size - offset), AudioTrack.WRITE_BLOCKING)
            if (n < 0) {
                coroutineContext.ensureActive() // the track was released because we were cancelled
                throw IllegalStateException("AudioTrack write failed: $n")
            }
            offset += n
        }
        return samples.size.toLong()
    }

    /** A fresh track per play loop, so nothing stale from before a pause or seek can play. */
    private fun newTrack(rate: Int, speed: Float): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, rate)) // 4-byte floats: ~250 ms
            .build()
        if (speed != 1f) t.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
        t.play()
        track = t
        trackRate = rate
        return t
    }

    private fun releaseTrack(t: AudioTrack) {
        runCatching {
            t.pause()
            t.flush()
            t.release()
        }
        if (track === t) {
            track = null
            trackRate = 0
        }
    }

    private fun stopAudio() {
        playJob?.cancel()
        playJob = null
        track?.let { releaseTrack(it) }
        unregisterNoisy()
    }

    // ---- Audio focus / headphones --------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        scope.launch {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Speech is hard to follow when ducked, so pause briefly instead.
                    val wasPlaying = _state.value.playWhenReady
                    pause()
                    resumeOnFocusGain = wasPlaying
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { context.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    companion object {
        private const val TAG = "MurmurReader"
        const val PREPARING_ID = "__preparing__"
        const val CHARS_PER_SECOND = 15f
        private const val LOOKAHEAD = 4
        private const val MAX_MODEL_SPEED = 1.6f
    }
}
