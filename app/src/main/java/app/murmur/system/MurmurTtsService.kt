package app.murmur.system

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import app.murmur.audio.Pcm
import app.murmur.container
import app.murmur.settings.VoiceSelection
import app.murmur.text.SentenceSegmenter
import app.murmur.text.SpeechText
import app.murmur.voices.EngineType
import app.murmur.voices.VoicePack
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.MissingResourceException

/**
 * Makes Murmur's neural voices available to every app (TalkBack, Chrome, e-book readers,
 * navigation) via Settings › Accessibility › Text-to-speech output.
 */
class MurmurTtsService : TextToSpeechService() {

    @Volatile private var stopRequested = false
    @Volatile private var loaded: VoiceSelection? = null

    private val voices get() = container.voices
    private val settings get() = container.settings
    private val segmenter = SentenceSegmenter()

    // ---- Languages -----------------------------------------------------------------------

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val wanted = iso3Lang(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        val wantedCountry = iso3Country(country)
        var best = TextToSpeech.LANG_NOT_SUPPORTED
        for (pack in voices.catalog.voices) {
            val installed = voices.isInstalled(pack.id)
            for (tag in pack.languages) {
                val loc = Locale.forLanguageTag(tag)
                if (iso3Lang(loc.language) != wanted) continue
                val result = when {
                    !installed -> TextToSpeech.LANG_MISSING_DATA
                    wantedCountry != null && iso3Country(loc.country) == wantedCountry -> TextToSpeech.LANG_COUNTRY_AVAILABLE
                    else -> TextToSpeech.LANG_AVAILABLE
                }
                if (rank(result) > rank(best)) best = result
            }
        }
        return best
    }

    override fun onGetLanguage(): Array<String> {
        val pack = currentPack()?.first ?: return arrayOf("eng", "USA", "")
        val loc = pack.primaryLocale
        return arrayOf(iso3Lang(loc.language) ?: "", iso3Country(loc.country) ?: "", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            onGetDefaultVoiceNameFor(lang, country, variant)?.let { loaded = parseVoiceName(it) }
        }
        return result
    }

    // ---- Voices --------------------------------------------------------------------------

    override fun onGetVoices(): List<Voice> = voices.installedPacks().flatMap { pack ->
        speakersOf(pack).flatMap { speaker ->
            pack.languages.map { tag ->
                Voice(
                    voiceName(pack, speaker, tag),
                    Locale.forLanguageTag(tag),
                    if (pack.engine == EngineType.KOKORO) Voice.QUALITY_VERY_HIGH else Voice.QUALITY_HIGH,
                    if (pack.engine == EngineType.KOKORO) Voice.LATENCY_HIGH else Voice.LATENCY_NORMAL,
                    false,
                    emptySet(),
                )
            }
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        val sel = voiceName?.let { parseVoiceName(it) } ?: return TextToSpeech.ERROR
        return if (voices.isInstalled(sel.packId)) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onLoadVoice(voiceName: String?): Int {
        val sel = voiceName?.let { parseVoiceName(it) } ?: return TextToSpeech.ERROR
        if (!voices.isInstalled(sel.packId)) return TextToSpeech.ERROR
        loaded = sel
        return TextToSpeech.SUCCESS
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        val wanted = iso3Lang(lang) ?: return null
        val wantedCountry = iso3Country(country)
        val preferred = settings.systemVoice.value ?: settings.voice.value
        val candidates = voices.installedPacks().filter { pack ->
            pack.languages.any { iso3Lang(Locale.forLanguageTag(it).language) == wanted }
        }
        val pack = candidates.firstOrNull { it.id == preferred?.packId } ?: candidates.firstOrNull() ?: return null
        val tag = pack.languages.firstOrNull {
            val l = Locale.forLanguageTag(it)
            iso3Lang(l.language) == wanted && (wantedCountry == null || iso3Country(l.country) == wantedCountry)
        } ?: pack.languages.first { iso3Lang(Locale.forLanguageTag(it).language) == wanted }
        val speaker = if (preferred?.packId == pack.id) preferred.speaker else pack.defaultSpeaker
        return voiceName(pack, speaker, tag)
    }

    // ---- Synthesis -----------------------------------------------------------------------

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false
        val sel = request.voiceName?.let { parseVoiceName(it) }?.takeIf { voices.isInstalled(it.packId) }
            ?: loaded?.takeIf { voices.isInstalled(it.packId) }
        val (pack, speaker) = voices.resolve(sel ?: settings.systemVoice.value ?: settings.voice.value) ?: run {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return
        }
        val text = request.charSequenceText?.toString().orEmpty()
        // System rate 100 = normal. Clamp to what the model handles well.
        val speed = (request.speechRate / 100f).coerceIn(0.5f, 2.5f)
        val synth = container.synthesizer

        try {
            runBlocking {
                val rate = synth.prepare(pack)
                if (callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return@runBlocking
                val pieces = segmenter.sentenceRanges(text).map { text.substring(it.first, it.last + 1) }
                    .ifEmpty { listOf(text) }
                    .map { SpeechText.normalize(it) }
                    .filter { p -> p.any { it.isLetterOrDigit() } }
                val maxBytes = callback.maxBufferSize
                for (piece in pieces) {
                    if (stopRequested) return@runBlocking
                    val audio = synth.generate(pack, speaker, piece, speed)
                    if (stopRequested) return@runBlocking
                    val bytes = Pcm.floatToPcm16(Pcm.trimSilence(audio.samples, audio.sampleRate, keepMs = 60))
                    var offset = 0
                    while (offset < bytes.size) {
                        if (stopRequested) return@runBlocking
                        val n = minOf(maxBytes, bytes.size - offset)
                        if (callback.audioAvailable(bytes, offset, n) != TextToSpeech.SUCCESS) return@runBlocking
                        offset += n
                    }
                }
                callback.done()
            }
        } catch (e: Exception) {
            Log.e("MurmurTts", "Synthesis failed", e)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
        }
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun currentPack(): Pair<VoicePack, Int>? =
        voices.resolve(loaded ?: settings.systemVoice.value ?: settings.voice.value)

    /** Packs with many unnamed speakers expose only their default one, to keep the list usable. */
    private fun speakersOf(pack: VoicePack): List<Int> =
        if (pack.speakers.isNotEmpty()) pack.speakers.indices.toList() else listOf(pack.defaultSpeaker)

    private fun voiceName(pack: VoicePack, speaker: Int, tag: String): String {
        val who = pack.speakers.getOrNull(speaker)?.substringBefore(' ')?.lowercase() ?: "s$speaker"
        return "${tag.lowercase()}-murmur-$who~${pack.id}~$speaker"
    }

    private fun parseVoiceName(name: String): VoiceSelection? {
        val parts = name.split('~')
        if (parts.size != 3) return null
        return VoiceSelection(parts[1], parts[2].toIntOrNull() ?: return null)
    }

    private fun rank(result: Int) = when (result) {
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> 4
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> 3
        TextToSpeech.LANG_AVAILABLE -> 2
        TextToSpeech.LANG_MISSING_DATA -> 1
        else -> 0
    }

    companion object {
        fun iso3Lang(lang: String?): String? {
            if (lang.isNullOrEmpty()) return null
            return try { Locale(lang).isO3Language } catch (_: MissingResourceException) { lang }
        }

        fun iso3Country(country: String?): String? {
            if (country.isNullOrEmpty()) return null
            return try { Locale("", country).isO3Country } catch (_: MissingResourceException) { country }
        }
    }
}
