package app.murmur.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A voice pack plus one of its speakers. */
data class VoiceSelection(val packId: String, val speaker: Int) {
    fun encode() = "$packId#$speaker"

    companion object {
        fun decode(s: String?): VoiceSelection? {
            if (s.isNullOrBlank()) return null
            val id = s.substringBefore('#')
            val speaker = s.substringAfter('#', "0").toIntOrNull() ?: 0
            return VoiceSelection(id, speaker)
        }
    }
}

enum class ShareMode { REPLACE, QUEUE }

/** Small preference store exposing each value as a StateFlow for Compose. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _voice = MutableStateFlow(VoiceSelection.decode(prefs.getString(KEY_VOICE, null)))
    val voice: StateFlow<VoiceSelection?> = _voice.asStateFlow()

    private val _systemVoice = MutableStateFlow(VoiceSelection.decode(prefs.getString(KEY_SYSTEM_VOICE, null)))
    /** Voice used by the system TTS engine; null follows [voice]. */
    val systemVoice: StateFlow<VoiceSelection?> = _systemVoice.asStateFlow()

    private val _speed = MutableStateFlow(prefs.getFloat(KEY_SPEED, 1.0f))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _paragraphPauseMs = MutableStateFlow(prefs.getInt(KEY_PARA_PAUSE, 450))
    val paragraphPauseMs: StateFlow<Int> = _paragraphPauseMs.asStateFlow()

    private val _shareMode = MutableStateFlow(
        runCatching { ShareMode.valueOf(prefs.getString(KEY_SHARE_MODE, null) ?: "") }.getOrDefault(ShareMode.REPLACE),
    )
    val shareMode: StateFlow<ShareMode> = _shareMode.asStateFlow()

    fun setVoice(v: VoiceSelection?) {
        _voice.value = v
        prefs.edit { putString(KEY_VOICE, v?.encode()) }
    }

    fun setSystemVoice(v: VoiceSelection?) {
        _systemVoice.value = v
        prefs.edit { putString(KEY_SYSTEM_VOICE, v?.encode()) }
    }

    fun setSpeed(s: Float) {
        val v = s.coerceIn(MIN_SPEED, MAX_SPEED)
        _speed.value = v
        prefs.edit { putFloat(KEY_SPEED, v) }
    }

    fun setParagraphPauseMs(ms: Int) {
        _paragraphPauseMs.value = ms
        prefs.edit { putInt(KEY_PARA_PAUSE, ms) }
    }

    fun setShareMode(m: ShareMode) {
        _shareMode.value = m
        prefs.edit { putString(KEY_SHARE_MODE, m.name) }
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        private const val KEY_VOICE = "voice"
        private const val KEY_SYSTEM_VOICE = "system_voice"
        private const val KEY_SPEED = "speed"
        private const val KEY_PARA_PAUSE = "paragraph_pause"
        private const val KEY_SHARE_MODE = "share_mode"
    }
}
