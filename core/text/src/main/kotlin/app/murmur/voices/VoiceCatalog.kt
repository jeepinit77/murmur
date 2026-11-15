package app.murmur.voices

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

@Serializable
enum class EngineType(val displayName: String) {
    @SerialName("kokoro") KOKORO("Kokoro"),
    @SerialName("piper") PIPER("Piper"),
}

/** One speaker inside a multi-speaker pack. */
@Serializable
data class Speaker(
    val name: String,
    /** "female", "male" or null when not applicable (e.g. a blend). */
    val gender: String? = null,
    /** BCP-47 tag of the language this speaker is trained on; null means the pack's first language. */
    val language: String? = null,
)

/** A downloadable voice pack (one ONNX model, possibly with many speakers). */
@Serializable
data class VoicePack(
    val id: String,
    val name: String,
    val engine: EngineType,
    /** BCP-47 tags, e.g. "en-US". The first one is the primary language. */
    val languages: List<String>,
    val url: String,
    val sizeMb: Int,
    /** "Best", "Good" or "Fair". */
    val quality: String,
    /** Relative start-up speed, e.g. "Fast"; empty when unremarkable. */
    val speed: String = "",
    val license: String,
    val description: String = "",
    /** Gender of a single-speaker pack ("female" or "male"). */
    val gender: String? = null,
    /** Speakers for ids 0..n-1; empty means a single speaker. */
    val speakers: List<Speaker> = emptyList(),
    val defaultSpeaker: Int = 0,
    val sha256: String? = null,
) {
    val primaryLocale: Locale get() = Locale.forLanguageTag(languages.first())

    /** Language codes ("en", "de", ...) this pack can speak, without regions. */
    val languageCodes: Set<String> get() = languages.map { Locale.forLanguageTag(it).language }.toSet()

    /** Higher is better; unknown labels sort last. */
    val qualityRank: Int get() = when (quality.lowercase(Locale.ROOT)) {
        "best" -> 3
        "good" -> 2
        "fair" -> 1
        else -> 0
    }
}

@Serializable
data class VoiceCatalog(val version: Int, val voices: List<VoicePack>) {
    fun byId(id: String): VoicePack? = voices.firstOrNull { it.id == id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): VoiceCatalog = json.decodeFromString(serializer(), text)
    }
}

/** Files of an extracted voice pack, discovered by convention (sherpa-onnx tts-models layout). */
data class VoiceFiles(
    val model: File,
    val tokens: File,
    val espeakData: File?,
    val voicesBin: File?,
    val lexicons: List<File>,
    val dictDir: File?,
) {
    companion object {
        /** Returns null if [dir] does not contain a complete model. */
        fun discover(dir: File): VoiceFiles? {
            if (!dir.isDirectory) return null
            val files = dir.listFiles().orEmpty()
            val onnx = files.filter { it.isFile && it.name.endsWith(".onnx") }
            // Prefer the int8 model when both are present: ~2x faster on phone CPUs.
            val model = onnx.firstOrNull { "int8" in it.name } ?: onnx.firstOrNull() ?: return null
            val tokens = File(dir, "tokens.txt").takeIf { it.isFile } ?: return null
            return VoiceFiles(
                model = model,
                tokens = tokens,
                espeakData = File(dir, "espeak-ng-data").takeIf { it.isDirectory },
                voicesBin = File(dir, "voices.bin").takeIf { it.isFile },
                lexicons = files.filter { it.isFile && it.name.startsWith("lexicon") && it.name.endsWith(".txt") }
                    .sortedBy { it.name },
                dictDir = File(dir, "dict").takeIf { it.isDirectory },
            )
        }
    }
}
