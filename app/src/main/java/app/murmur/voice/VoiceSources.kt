package app.murmur.voice

import app.murmur.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** All voice sources together: the one place the app asks "which voices exist" and "speak with which". */
class VoiceSources(
    /** Every source in this build. Google voices are offered whenever the phone has them. */
    val sources: List<VoiceSource>,
    private val sherpa: SherpaVoiceSource,
    private val packs: VoiceRepository,
    private val settings: Settings,
    scope: CoroutineScope,
) {
    val voices: StateFlow<List<Voice>> = combine(sources.map { it.voices }) { lists -> lists.flatMap { it } }
        .stateIn(scope, SharingStarted.Eagerly, sources.flatMap { it.voices.value })

    val installed: StateFlow<Set<String>> = combine(sources.map { it.installed }) { sets -> sets.flatMap { it }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, sources.flatMap { it.installed.value }.toSet())

    init {
        // Re-check the selection when a slow source finishes loading.
        scope.launch {
            combine(sources.map { it.loaded }) { it.all { done -> done } }.collect { ensureSelection(installed.value) }
        }
        val selected = settings.voice.value
        sources.filter { !it.startsOnDemand || selected?.startsWith("${it.id}:") == true }.forEach { it.activate() }
        // Keep a downloaded voice selected, so share-to-speak works right after the first download.
        scope.launch { installed.collect { ensureSelection(it) } }
        // Remember the user's choice per language, for articles in that language.
        scope.launch {
            combine(settings.voice, voices) { id, _ -> byId(id) }.collect { v ->
                v?.let { settings.setVoiceForLanguage(languageOf(it), it.id) }
            }
        }
    }

    /** A voice to read a document in [language] ("fr"), or null when none is installed for it. */
    fun resolveFor(language: String?): Voice? {
        val current = resolve()
        if (language == null || current == null || languageOf(current) == language) return current
        val ready = installed.value
        byId(settings.voiceForLanguage(language))?.takeIf { it.id in ready }?.let { return it }
        return voices.value.filter { it.id in ready && languageOf(it) == language }.maxByOrNull { it.qualityRank }
    }

    fun hasAnyInstalled(): Boolean = installed.value.isNotEmpty()

    fun byId(id: String?): Voice? = id?.let { wanted -> voices.value.firstOrNull { it.id == wanted } }

    fun sourceOf(voice: Voice): VoiceSource = sources.first { it.id == voice.sourceId }

    /** The selected voice if it is ready, otherwise any ready voice. */
    fun resolve(): Voice? {
        val ready = installed.value
        byId(settings.voice.value)?.takeIf { it.id in ready }?.let { return it }
        return fallback(ready)?.let(::byId)
    }

    fun release() = sources.forEach { it.release() }

    fun refresh() = sources.forEach { it.refresh() }

    /** Starts every source, including on-demand ones, for screens that list or download voices. */
    fun activateAll() = sources.forEach { it.activate() }

    fun languageOf(voice: Voice): String = java.util.Locale.forLanguageTag(voice.language).language

    private fun ensureSelection(ready: Set<String>) {
        val current = settings.voice.value
        if (current != null && current in ready) return
        // A source (Google voices) may still be loading at startup; don't replace its voice yet.
        // Once it has loaded, a voice it doesn't have (Google's app removed) is replaced.
        val currentSource = current?.substringBefore(':')
        val stillLoading = current != null && byId(current) == null &&
            currentSource != SherpaVoiceSource.ID && sources.any { it.id == currentSource && !it.loaded.value }
        if (stillLoading) return
        fallback(ready)?.let { settings.setVoice(it) }
    }

    private fun fallback(ready: Set<String>): String? {
        packs.installedPacks().firstOrNull()?.let { return sherpa.defaultVoiceId(it) }
        return voices.value.firstOrNull { it.id in ready }?.id
    }
}
