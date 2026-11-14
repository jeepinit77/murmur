package app.murmur.voice

import app.murmur.voices.EngineType
import app.murmur.voices.VoiceFiles
import app.murmur.voices.VoicePack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class Audio(val samples: FloatArray, val sampleRate: Int)

/**
 * Owns the single loaded neural TTS model. All inference runs on one dedicated thread,
 * so the reader and the system TTS engine share one copy of the (large) model.
 *
 * This is the only file that touches the sherpa-onnx API.
 */
class Synthesizer(private val voices: VoiceRepository) {

    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "murmur-tts").apply { priority = Thread.MAX_PRIORITY - 1 }
    }
    private val dispatcher = thread.asCoroutineDispatcher()

    private var loadedId: String? = null
    private var tts: OfflineTts? = null

    /** Loads [pack] if needed and returns its output sample rate. */
    suspend fun prepare(pack: VoicePack): Int = withContext(dispatcher) { ensure(pack).sampleRate() }

    suspend fun generate(pack: VoicePack, speaker: Int, text: String, speed: Float): Audio =
        withContext(dispatcher) {
            val engine = ensure(pack)
            val sid = speaker.coerceIn(0, (engine.numSpeakers() - 1).coerceAtLeast(0))
            val audio = engine.generate(text = text, sid = sid, speed = speed)
            Audio(audio.samples, audio.sampleRate)
        }

    /** Frees the model; it will be reloaded on next use. Safe to call from any thread. */
    fun release() {
        thread.execute {
            tts?.release()
            tts = null
            loadedId = null
        }
    }

    private fun ensure(pack: VoicePack): OfflineTts {
        tts?.let { if (loadedId == pack.id) return it }
        tts?.release()
        tts = null
        loadedId = null
        val files = voices.filesFor(pack.id) ?: error("Voice ${pack.name} is not installed")
        val engine = OfflineTts(assetManager = null, config = configFor(pack.engine, files))
        tts = engine
        loadedId = pack.id
        return engine
    }

    private fun configFor(type: EngineType, f: VoiceFiles): OfflineTtsConfig {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val model = when (type) {
            EngineType.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = f.model.absolutePath,
                    voices = f.voicesBin?.absolutePath ?: error("Kokoro voice pack is missing voices.bin"),
                    tokens = f.tokens.absolutePath,
                    dataDir = f.espeakData?.absolutePath.orEmpty(),
                    lexicon = f.lexicons.joinToString(",") { it.absolutePath },
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            EngineType.PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = f.model.absolutePath,
                    tokens = f.tokens.absolutePath,
                    dataDir = f.espeakData?.absolutePath.orEmpty(),
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
        }
        return OfflineTtsConfig(model = model, maxNumSentences = 1)
    }
}
