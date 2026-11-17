package app.murmur.voice

import android.content.Intent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class Gender(val label: String) { FEMALE("Female"), MALE("Male") }

/** Something that is downloaded as a unit and may provide several voices (e.g. a Kokoro pack). */
data class VoicePackage(
    val id: String,
    val name: String,
    /** Download size, when known. */
    val sizeMb: Int?,
    val voiceCount: Int,
    val license: String?,
    val description: String,
    /** Short reason to pick this download, e.g. "Most natural" or "Fast to start". */
    val highlight: String,
    /** False when another app manages the download (Google voices). */
    val canDelete: Boolean,
)

/** One selectable voice, from any [VoiceSource]. */
data class Voice(
    /** "<source>:<key>", stored in settings. */
    val id: String,
    val sourceId: String,
    val name: String,
    /** "Kokoro", "Piper" or "Google". */
    val engine: String,
    /** BCP-47 tag, e.g. "en-US". */
    val language: String,
    val gender: Gender?,
    /** "Best", "Good" or "Fair"; empty when not rated. */
    val quality: String,
    /** Higher is better; 0 when not rated. */
    val qualityRank: Int,
    /** Relative start-up speed, e.g. "Fast"; empty when unremarkable. */
    val speed: String,
    val pkg: VoicePackage,
    /** How the voice sounds, as its maker describes it ("Medium-high pitch"); empty when unknown. */
    val sound: String = "",
)

/** Audio produced by a [PcmVoiceSource]. */
class Audio(val samples: FloatArray, val sampleRate: Int)

/** A provider of voices: downloads them, reports which are ready, and speaks with them. */
sealed interface VoiceSource {
    val id: String
    /** Every voice this source offers on this phone; empty when the source is unsupported. */
    val voices: StateFlow<List<Voice>>
    /** Ids of voices that are downloaded and ready to speak. */
    val installed: StateFlow<Set<String>>

    fun downloadState(pkg: VoicePackage): Flow<DownloadState>
    fun download(voice: Voice)
    fun cancelDownload(pkg: VoicePackage)
    fun delete(pkg: VoicePackage)

    /**
     * Opens the app that keeps [pkg]'s files, for downloads Murmur can't delete itself (Google
     * voices); null when Murmur deletes them.
     */
    fun manageIntent(pkg: VoicePackage): Intent? = null

    /** Checks again which voices are installed, for example after returning from another app. */
    fun refresh() {}

    /** Called once at startup; a source may wait for this before loading anything. */
    fun activate() {}

    /** Frees large resources such as loaded models. */
    fun release() {}
}

/** Returns audio for Murmur to play (sherpa-onnx voices). */
interface PcmVoiceSource : VoiceSource {
    suspend fun synthesize(voice: Voice, text: String, speed: Float): Audio
}

/** Plays audio itself (Google voices, which play through Google's speech app). */
/**
 * A failure that is likely to clear up if the same sentence is tried again, such as the speech
 * engine still settling after it was stopped for an interruption.
 */
class TransientSpeechException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

interface PlayingVoiceSource : VoiceSource {
    /**
     * Speaks [text] at [speed] (1 = normal) and returns when it has finished. [onStart] is called
     * when audio begins. Cancelling the coroutine stops speech.
     */
    suspend fun speak(voice: Voice, text: String, speed: Float, onStart: () -> Unit = {})
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Queued : DownloadState
    /** [fraction] is null while extracting or when the size is unknown. */
    data class Running(val fraction: Float?, val phase: String) : DownloadState
    data class Failed(val message: String) : DownloadState
}
