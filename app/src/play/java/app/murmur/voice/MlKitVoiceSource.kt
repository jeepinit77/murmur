package app.murmur.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.edit
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.texttospeech.SpeechSynthesis
import com.google.mlkit.genai.texttospeech.SpeechSynthesizer
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerRequest
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerResponse
import com.google.mlkit.genai.texttospeech.VoiceInfo
import com.google.mlkit.genai.texttospeech.speechSynthesizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Field
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Google's on-device voices through the ML Kit GenAI Text-to-Speech API (alpha). Supported on
 * recent Pixel and Galaxy phones with Google's Speech Recognition & Synthesis app; elsewhere
 * [voices] stays empty and the source is invisible.
 *
 * Google's speech app plays the audio itself. The API has no speed or pitch setting, so Google's
 * engine would use Android's system text-to-speech rate and pitch; [applyRateAndPitch] sets them
 * on the client's internal TextToSpeech instead.
 *
 * ML Kit can report a voice as available while Google's speech app hasn't installed it, and by
 * default the speech app then reads with another voice without reporting an error. So voice
 * fallback is turned off, and a voice counts as ready only when the speech app lists it as
 * installed ([installedInEngine]).
 *
 * Murmur can't delete these voices: the speech app keeps them, and its own voice data screen
 * doesn't list them. Deleting a language here removes its voices from Murmur ([removed]) until
 * it's downloaded again.
 */
class MlKitVoiceSource(context: Context, private val scope: CoroutineScope) : PlayingVoiceSource {

    override val id = ID

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("mlkit_voices", Context.MODE_PRIVATE)
    private val client: SpeechSynthesizer by lazy {
        // Without fallback, a voice that isn't installed fails instead of being read by another voice.
        SpeechSynthesis.getClient(speechSynthesizerOptions(Locale.US) { setVoiceFallbackEnabled(false) })
    }
    private val infos = ConcurrentHashMap<String, VoiceInfo>()
    private val downloads = ConcurrentHashMap<String, MutableStateFlow<DownloadState>>()
    private val downloadJobs = ConcurrentHashMap<String, Job>()

    private val _voices = MutableStateFlow<List<Voice>>(emptyList())
    override val voices: StateFlow<List<Voice>> = _voices.asStateFlow()

    /** Packages (one per language) removed from Murmur with Delete; their voices count as not installed. */
    private var removed: Set<String> = prefs.getStringSet(KEY_REMOVED, null).orEmpty().toSet()

    // Seeded from the last run so a Google voice counts as ready before the status check finishes.
    private val _installed = MutableStateFlow(prefs.getStringSet(KEY_INSTALLED, null).orEmpty().toSet())
    override val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val activated = AtomicBoolean(false)

    override val startsOnDemand = true

    /** Loads the voice list, off the main thread, the first time Google voices are needed. */
    override fun activate() {
        if (!activated.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            try {
                load()
                refreshStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Google voices unavailable", e)
            }
        }
    }

    override fun downloadState(pkg: VoicePackage): Flow<DownloadState> = stateFor(pkg.id)

    override fun download(voice: Voice) {
        val info = infos[voice.id] ?: return
        val pkgId = voice.pkg.id
        setRemoved(pkgId, false)
        if (downloadJobs[pkgId]?.isActive == true) return
        val state = stateFor(pkgId)
        state.value = DownloadState.Running(null, "Downloading")
        downloadJobs[pkgId] = scope.launch {
            var total = 0L
            try {
                client.download(info).collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> total = status.bytesToDownload
                        is DownloadStatus.DownloadProgress -> state.value = DownloadState.Running(
                            if (total > 0) (status.totalBytesDownloaded.toFloat() / total).coerceIn(0f, 1f) else null,
                            "Downloading",
                        )
                        is DownloadStatus.DownloadCompleted -> state.value = DownloadState.Running(null, "Finishing")
                        is DownloadStatus.DownloadFailed -> throw status.e
                        else -> Unit
                    }
                }
                refreshStatus()
                // ML Kit can finish a download that the speech app still hasn't installed.
                state.value = if (voice.id in _installed.value) DownloadState.Idle else DownloadState.Failed(NOT_INSTALLED_PACKAGE)
            } catch (e: CancellationException) {
                state.value = DownloadState.Idle
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Download failed", e)
                state.value = DownloadState.Failed(messageFor(e))
            }
        }
    }

    override fun cancelDownload(pkg: VoicePackage) {
        downloadJobs.remove(pkg.id)?.cancel()
        stateFor(pkg.id).value = DownloadState.Idle
    }

    /** Removes the language's voices from Murmur. Google's speech app keeps the files. */
    override fun delete(pkg: VoicePackage) {
        setRemoved(pkg.id, true)
        val gone = _voices.value.filter { it.pkg.id == pkg.id }.map { it.id }.toSet()
        _installed.value = _installed.value - gone
        prefs.edit { putStringSet(KEY_INSTALLED, _installed.value) }
    }

    private fun setRemoved(pkgId: String, on: Boolean) {
        removed = if (on) removed + pkgId else removed - pkgId
        prefs.edit { putStringSet(KEY_REMOVED, removed) }
    }

    override fun refresh() {
        if (!activated.get()) return
        scope.launch {
            runCatching { refreshStatus() }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Status check failed", it) }
        }
    }

    override suspend fun speak(voice: Voice, text: String, speed: Float, onStart: () -> Unit) {
        val info = infos[voice.id] ?: error("This Google voice isn't available on this phone")
        applyRateAndPitch(speed)
        val request = SpeechSynthesizerRequest(
            SpeechSynthesizerRequest.Input.Text(text, info),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            SpeechSynthesizerRequest.QueueMode.QUEUE_FLUSH,
        )
        var started = 0L
        try {
            client.synthesize(request).collect { r ->
                when (r) {
                    is SpeechSynthesizerResponse.StatusUpdate -> {
                        if (r.status == SpeechSynthesizerResponse.Status.START) {
                            started = SystemClock.elapsedRealtime()
                            onStart()
                        }
                        if (r.status == SpeechSynthesizerResponse.Status.DONE && started > 0) {
                            val ms = SystemClock.elapsedRealtime() - started
                            Log.d(TAG, "Spoke ${text.length} chars in $ms ms at ${speed}x (${text.length * 1000 / ms.coerceAtLeast(1)} chars/s)")
                        }
                    }
                    is SpeechSynthesizerResponse.Error -> {
                        // The voice was removed or never installed: stop listing it as ready.
                        if (r.errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) scope.launch { refreshStatus() }
                        throw errorFor(r.errorCode, null)
                    }
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            runCatching { client.stop() }
            throw e
        } catch (e: GenAiException) {
            throw errorFor(e.errorCode, e)
        }
    }

    /**
     * Google's engine can fail the first sentence after it was stopped (for example when Murmur
     * pauses for a notification sound), so those errors are worth retrying.
     */
    private fun errorFor(code: Int, cause: Throwable?): Exception = when (code) {
        TextToSpeech.STOPPED, TextToSpeech.ERROR_SYNTHESIS, TextToSpeech.ERROR_SERVICE,
        TextToSpeech.ERROR_OUTPUT, GenAiException.ErrorCode.BUSY,
        -> TransientSpeechException(messageFor(code), cause)
        else -> IllegalStateException(messageFor(code), cause)
    }

    /**
     * ML Kit 1.0.0-alpha1 speaks through a private [TextToSpeech] it never sets a rate or pitch on,
     * so Google's engine falls back to the system settings (which can be several times normal).
     * An app's own rate and pitch take precedence over those, so set them on that instance.
     * If a later ML Kit version changes this, speech still works at the system rate.
     */
    private fun applyRateAndPitch(speed: Float) {
        val field = ttsField ?: return
        val tts = runCatching { field.get(client) as? TextToSpeech }.getOrNull() ?: return
        tts.setSpeechRate(speed)
        tts.setPitch(1f)
    }

    private val ttsField: Field? by lazy {
        SpeechSynthesizer::class.java.declaredFields
            .firstOrNull { it.type == TextToSpeech::class.java }
            ?.apply { isAccessible = true }
            .also { if (it == null) Log.w(TAG, "Can't set speed: ML Kit's TextToSpeech field not found") }
    }

    private fun stateFor(pkgId: String) = downloads.getOrPut(pkgId) { MutableStateFlow(DownloadState.Idle) }

    private suspend fun load() {
        val list = mutableListOf<Voice>()
        for (tag in LOCALES) {
            val locale = Locale.forLanguageTag(tag)
            val found = runCatching { client.getSupportedVoices(locale) }
                .onFailure { Log.w(TAG, "No Google voices for $tag", it) }
                .getOrNull().orEmpty()
            if (found.isEmpty()) continue
            val pkg = VoicePackage(
                id = "$ID-$tag",
                name = "Google ${localeLabel(tag)}",
                sizeMb = null,
                voiceCount = found.size,
                license = null,
                description = "Downloaded and updated by Google's Speech Recognition & Synthesis app. " +
                    "One download includes all ${found.size} voices for this language.",
                highlight = "Fast to start",
                canDelete = true,
            )
            for (info in found) {
                val voiceId = "$ID:${info.name}"
                infos[voiceId] = info
                val documented = DOCUMENTED[info.name.substringAfterLast("-x-")]
                list += Voice(
                    id = voiceId,
                    sourceId = ID,
                    name = displayName(info.name),
                    engine = "Google",
                    language = tag,
                    gender = when (info.gender) {
                        VoiceInfo.Gender.FEMALE -> Gender.FEMALE
                        VoiceInfo.Gender.MALE -> Gender.MALE
                        else -> documented?.first
                    },
                    quality = "",
                    qualityRank = 0,
                    speed = "Fast",
                    pkg = pkg,
                    sound = documented?.second.orEmpty(),
                )
            }
        }
        _voices.value = list
    }

    private suspend fun refreshStatus() {
        val engine = installedInEngine()
        val ready = mutableSetOf<String>()
        val notInEngine = mutableListOf<String>()
        for ((voiceId, info) in infos) {
            val status = runCatching { client.checkStatus(info) }.getOrDefault(FeatureStatus.UNAVAILABLE)
            if (status != FeatureStatus.AVAILABLE) continue
            if (engine == null || info.name.lowercase(Locale.ROOT) in engine) ready += voiceId else notInEngine += info.name
        }
        if (notInEngine.isNotEmpty()) {
            Log.w(TAG, "ML Kit reports these voices as available, but Google's speech app hasn't installed them: ${notInEngine.sorted()}")
        }
        // Languages deleted in Murmur stay hidden even though the speech app still has them.
        val shown = ready.filterTo(mutableSetOf()) { id -> _voices.value.firstOrNull { it.id == id }?.pkg?.id !in removed }
        _installed.value = shown
        prefs.edit { putStringSet(KEY_INSTALLED, shown) }
    }

    /**
     * Lowercase names of the voices Google's speech app has installed, or null when it can't be
     * asked. ML Kit finds a voice by this name (the speech app adds "-local") when it speaks.
     */
    private suspend fun installedInEngine(): Set<String>? = withTimeoutOrNull(ENGINE_TIMEOUT_MS) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var tts: TextToSpeech? = null
                tts = TextToSpeech(appContext, { status ->
                    val engine = tts
                    val names = if (status == TextToSpeech.SUCCESS && engine != null) {
                        runCatching { engine.voices }.getOrNull()
                            ?.filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
                            ?.map { it.name.removeSuffix("-local").lowercase(Locale.ROOT) }
                            ?.toSet()
                    } else {
                        null
                    }
                    engine?.shutdown()
                    if (cont.isActive) cont.resume(names)
                }, ENGINE)
                cont.invokeOnCancellation { tts.shutdown() }
            }
        }
    }

    private fun messageFor(e: Throwable): String =
        if (e is GenAiException) messageFor(e.errorCode) else e.message ?: "Google voice error"

    private fun messageFor(code: Int): String = when (code) {
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "This Google voice isn't installed on this phone. Choose another voice."
        GenAiException.ErrorCode.BUSY -> "Google voices are busy. Try again in a moment."
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
            "Google voices reached their daily usage limit. Choose another voice or try again tomorrow."
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "Google voices only work while Murmur is open."
        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "Not enough storage to download this voice."
        GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_SUPPORTED ->
            "Google voices aren't available on this phone."
        else -> "Google voice error (code $code)"
    }

    companion object {
        const val ID = "mlkit"
        private const val TAG = "MlKitVoices"
        private const val KEY_INSTALLED = "installed"
        private const val KEY_REMOVED = "removed"
        private const val ENGINE = "com.google.android.tts"
        private const val ENGINE_TIMEOUT_MS = 5_000L
        private const val NOT_INSTALLED_PACKAGE =
            "These voices aren't installed in Google's Speech Recognition & Synthesis app."

        /** Locales documented for ML Kit GenAI Text-to-Speech 1.0.0-alpha1. */
        private val LOCALES = listOf(
            "en-US", "en-GB", "ar-XA", "da-DK", "de-DE", "es-ES", "es-US", "fr-CA", "fr-FR", "hi-IN", "id-ID",
            "it-IT", "ja-JP", "ko-KR", "nb-NO", "nl-NL", "pl-PL", "pt-BR", "ru-RU", "sv-SE", "tr-TR",
        )

        /**
         * Gender and pitch of each voice, as listed on ML Kit's Text-to-Speech page
         * (developers.google.com/ml-kit/genai/text-to-speech/android). Google gives them no names.
         */
        private val DOCUMENTED = mapOf(
            "star02" to (Gender.FEMALE to "Medium pitch"),
            "star04" to (Gender.MALE to "Medium-high pitch"),
            "star05" to (Gender.MALE to "Low pitch"),
            "star08" to (Gender.FEMALE to "Low pitch"),
            "star11" to (Gender.MALE to "Medium pitch, younger sound"),
            "star12" to (Gender.FEMALE to "Medium-high pitch"),
            "star13" to (Gender.MALE to "Medium pitch"),
            "star16" to (Gender.FEMALE to "Medium-low pitch"),
        )

        /** "en-us-x-star02" -> "Google voice 2". */
        private fun displayName(name: String): String {
            val number = name.substringAfterLast("-x-", name).dropWhile { it.isLetter() }.toIntOrNull()
            return if (number != null) "Google voice $number" else "Google voice"
        }

        private fun localeLabel(tag: String): String {
            val locale = Locale.forLanguageTag(tag)
            val language = locale.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
            val region = when (locale.country) {
                "US" -> "US"
                "GB" -> "UK"
                "XA" -> null
                else -> locale.getDisplayCountry(Locale.getDefault())
            }
            return if (region == null) language else "$language ($region)"
        }
    }
}
