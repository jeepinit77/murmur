package app.murmur.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ShareMode { REPLACE, QUEUE }

/** Small preference store exposing each value as a StateFlow for Compose. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE).also {
        // Left over from when Murmur was also a system TTS engine.
        it.edit { remove("system_voice") }
    }

    private val _voice = MutableStateFlow(migrateVoiceId(prefs.getString(KEY_VOICE, null)))
    /** Id of the selected voice, e.g. "sherpa:kokoro-en-v0_19#1" or "mlkit:en-us-x-star02". */
    val voice: StateFlow<String?> = _voice.asStateFlow()

    private val _googleVoices = MutableStateFlow(prefs.getBoolean(KEY_GOOGLE_VOICES, false))
    /** Offer Google's voices (play build only). Off by default. */
    val googleVoices: StateFlow<Boolean> = _googleVoices.asStateFlow()

    private val _speed = MutableStateFlow(prefs.getFloat(KEY_SPEED, 1.0f))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _paragraphPauseMs = MutableStateFlow(prefs.getInt(KEY_PARA_PAUSE, 450))
    val paragraphPauseMs: StateFlow<Int> = _paragraphPauseMs.asStateFlow()

    private val _shareMode = MutableStateFlow(
        runCatching { ShareMode.valueOf(prefs.getString(KEY_SHARE_MODE, null) ?: "") }.getOrDefault(ShareMode.REPLACE),
    )
    val shareMode: StateFlow<ShareMode> = _shareMode.asStateFlow()

    fun setVoice(id: String?) {
        _voice.value = id
        prefs.edit { putString(KEY_VOICE, id) }
    }

    fun setGoogleVoices(on: Boolean) {
        _googleVoices.value = on
        prefs.edit { putBoolean(KEY_GOOGLE_VOICES, on) }
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
        /** Before voice sources, the setting held "<pack id>#<speaker>" for a sherpa-onnx pack. */
        fun migrateVoiceId(stored: String?): String? = when {
            stored.isNullOrBlank() -> null
            ':' in stored -> stored
            '#' in stored -> "sherpa:$stored"
            else -> "sherpa:$stored#0"
        }

        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        private const val KEY_VOICE = "voice"
        private const val KEY_SPEED = "speed"
        private const val KEY_GOOGLE_VOICES = "google_voices"
        private const val KEY_PARA_PAUSE = "paragraph_pause"
        private const val KEY_SHARE_MODE = "share_mode"
    }
}
