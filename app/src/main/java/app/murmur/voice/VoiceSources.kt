package app.murmur.voice

import app.murmur.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** All voice sources together: the one place the app asks "which voices exist" and "speak with which". */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSources(
    /** Every source in this build, including ones the user hasn't turned on. */
    val sources: List<VoiceSource>,
    private val sherpa: SherpaVoiceSource,
    private val packs: VoiceRepository,
    private val settings: Settings,
    scope: CoroutineScope,
) {
    /** Sources in use: opt-in sources (Google voices) only while their setting is on. */
    private val active: StateFlow<List<VoiceSource>> = settings.googleVoices
        .map { on -> sources.filter { !it.requiresOptIn || on } }
        .onEach { list -> list.forEach { it.activate() } }
        .stateIn(scope, SharingStarted.Eagerly, activeNow())

    val voices: StateFlow<List<Voice>> = active
        .flatMapLatest { list -> combine(list.map { it.voices }) { lists -> lists.flatMap { it } } }
        .stateIn(scope, SharingStarted.Eagerly, activeNow().flatMap { it.voices.value })

    val installed: StateFlow<Set<String>> = active
        .flatMapLatest { list -> combine(list.map { it.installed }) { sets -> sets.flatMap { it }.toSet() } }
        .stateIn(scope, SharingStarted.Eagerly, activeNow().flatMap { it.installed.value }.toSet())

    /** True when this build has a source that needs turning on in Settings. */
    val hasOptInSources: Boolean get() = sources.any { it.requiresOptIn }

    init {
        activeNow().forEach { it.activate() }
        // Keep a downloaded voice selected, so share-to-speak works right after the first download.
        scope.launch { installed.collect { ensureSelection(it) } }
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

    private fun activeNow(): List<VoiceSource> = sources.filter { !it.requiresOptIn || settings.googleVoices.value }

    private fun ensureSelection(ready: Set<String>) {
        val current = settings.voice.value
        if (current != null && current in ready) return
        // An active source (Google voices) may still be loading at startup; don't replace its voice yet.
        val currentSource = current?.substringBefore(':')
        val stillLoading = current != null && byId(current) == null &&
            currentSource != SherpaVoiceSource.ID && active.value.any { it.id == currentSource }
        if (stillLoading) return
        fallback(ready)?.let { settings.setVoice(it) }
    }

    private fun fallback(ready: Set<String>): String? {
        packs.installedPacks().firstOrNull()?.let { return sherpa.defaultVoiceId(it) }
        return voices.value.firstOrNull { it.id in ready }?.id
    }
}
