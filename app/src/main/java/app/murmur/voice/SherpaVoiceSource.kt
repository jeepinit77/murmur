package app.murmur.voice

import app.murmur.voices.EngineType
import app.murmur.voices.VoicePack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

/** Kokoro and Piper voices from the bundled catalog, run with sherpa-onnx. */
class SherpaVoiceSource(
    private val packs: VoiceRepository,
    private val synthesizer: Synthesizer,
    scope: CoroutineScope,
) : PcmVoiceSource {

    override val id = ID

    override val voices: StateFlow<List<Voice>> =
        MutableStateFlow(packs.catalog.voices.flatMap(::voicesOf)).asStateFlow()

    override val installed: StateFlow<Set<String>> = packs.installed
        .map(::installedVoiceIds)
        .stateIn(scope, SharingStarted.Eagerly, installedVoiceIds(packs.installed.value))

    override fun downloadState(pkg: VoicePackage): Flow<DownloadState> = packs.downloadState(pkg.id)

    override fun download(voice: Voice) = packs.download(voice.pkg.id)

    override fun cancelDownload(pkg: VoicePackage) = packs.cancelDownload(pkg.id)

    override fun delete(pkg: VoicePackage) = packs.delete(pkg.id)

    override suspend fun synthesize(voice: Voice, text: String, speed: Float): Audio {
        val (pack, speaker) = parse(voice.id) ?: error("Unknown voice ${voice.id}")
        return synthesizer.generate(pack, speaker, text, speed)
    }

    override fun release() = synthesizer.release()

    /** The voice a pack uses by default, e.g. to pick one automatically after a download. */
    fun defaultVoiceId(pack: VoicePack): String = voiceId(pack.id, pack.defaultSpeaker)

    private fun installedVoiceIds(packIds: Set<String>): Set<String> =
        voices.value.filter { it.pkg.id in packIds }.map { it.id }.toSet()

    private fun parse(id: String): Pair<VoicePack, Int>? {
        val key = id.removePrefix("$ID:").takeIf { it != id } ?: return null
        val pack = packs.catalog.byId(key.substringBefore('#')) ?: return null
        return pack to (key.substringAfter('#', "0").toIntOrNull() ?: 0)
    }

    private fun voicesOf(pack: VoicePack): List<Voice> {
        val pkg = VoicePackage(
            id = pack.id,
            name = downloadName(pack),
            sizeMb = pack.sizeMb,
            voiceCount = pack.speakers.size.coerceAtLeast(1),
            license = pack.license,
            description = pack.description,
            highlight = when {
                pack.qualityRank >= 3 -> "Most natural"
                pack.speed.isNotBlank() -> "Fast to start"
                else -> ""
            },
            canDelete = true,
        )
        fun voice(speaker: Int, name: String, gender: String?, language: String) = Voice(
            id = voiceId(pack.id, speaker),
            sourceId = ID,
            name = name,
            engine = pack.engine.displayName,
            language = language,
            gender = gender.toGender(),
            quality = pack.quality,
            qualityRank = pack.qualityRank,
            speed = pack.speed,
            pkg = pkg,
        )
        if (pack.speakers.isEmpty()) {
            // Single-speaker Piper packs are named like "Lessac (US English)"; the voice is "Lessac".
            return listOf(voice(0, pack.name.substringBefore(" (").trim(), pack.gender, pack.languages.first()))
        }
        return pack.speakers.mapIndexed { i, s -> voice(i, s.name, s.gender, s.language ?: pack.languages.first()) }
    }

    companion object {
        const val ID = "sherpa"

        fun voiceId(packId: String, speaker: Int) = "$ID:$packId#$speaker"

        /** "Kokoro English", "Kokoro multilingual", "Piper Lessac". */
        private fun downloadName(pack: VoicePack): String = when {
            pack.engine == EngineType.KOKORO && pack.languageCodes.size > 2 -> "Kokoro multilingual"
            pack.engine == EngineType.KOKORO ->
                "Kokoro " + pack.primaryLocale.getDisplayLanguage(Locale.getDefault())
                    .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            else -> "${pack.engine.displayName} ${pack.name.substringBefore(" (").trim()}"
        }

        private fun String?.toGender(): Gender? = when (this) {
            "female" -> Gender.FEMALE
            "male" -> Gender.MALE
            else -> null
        }
    }
}
