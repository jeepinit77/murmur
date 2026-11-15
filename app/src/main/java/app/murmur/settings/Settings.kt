package app.murmur.settings

import android.content.Context
import androidx.core.content.edit
import app.murmur.web.CodeMode
import app.murmur.web.ExtractOptions
import app.murmur.web.TableMode
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

    // ---- Web articles ------------------------------------------------------------------

    private val _headingsAsChapters = MutableStateFlow(prefs.getBoolean(KEY_HEADINGS, true))
    val headingsAsChapters: StateFlow<Boolean> = _headingsAsChapters.asStateFlow()

    private val _spokenIntro = MutableStateFlow(prefs.getBoolean(KEY_INTRO, false))
    val spokenIntro: StateFlow<Boolean> = _spokenIntro.asStateFlow()

    private val _readCaptions = MutableStateFlow(prefs.getBoolean(KEY_CAPTIONS, false))
    val readCaptions: StateFlow<Boolean> = _readCaptions.asStateFlow()

    private val _tableMode = MutableStateFlow(enumPref(KEY_TABLES, TableMode.SKIP))
    val tableMode: StateFlow<TableMode> = _tableMode.asStateFlow()

    private val _codeMode = MutableStateFlow(enumPref(KEY_CODE, CodeMode.SKIP))
    val codeMode: StateFlow<CodeMode> = _codeMode.asStateFlow()

    private val _keepPlayingWhileLoading = MutableStateFlow(prefs.getBoolean(KEY_KEEP_PLAYING, false))
    /** In "start reading the new item" mode, keep reading the current item until the shared one is ready. */
    val keepPlayingWhileLoading: StateFlow<Boolean> = _keepPlayingWhileLoading.asStateFlow()

    fun extractOptions() = ExtractOptions(
        headingsAsChapters = headingsAsChapters.value,
        spokenIntro = spokenIntro.value,
        readCaptions = readCaptions.value,
        tables = tableMode.value,
        code = codeMode.value,
    )

    fun setHeadingsAsChapters(on: Boolean) = putBool(KEY_HEADINGS, on, _headingsAsChapters)
    fun setSpokenIntro(on: Boolean) = putBool(KEY_INTRO, on, _spokenIntro)
    fun setReadCaptions(on: Boolean) = putBool(KEY_CAPTIONS, on, _readCaptions)
    fun setKeepPlayingWhileLoading(on: Boolean) = putBool(KEY_KEEP_PLAYING, on, _keepPlayingWhileLoading)

    fun setTableMode(m: TableMode) {
        _tableMode.value = m
        prefs.edit { putString(KEY_TABLES, m.name) }
    }

    fun setCodeMode(m: CodeMode) {
        _codeMode.value = m
        prefs.edit { putString(KEY_CODE, m.name) }
    }

    /** The voice last chosen for a language ("fr"), used when an article is in that language. */
    fun voiceForLanguage(language: String): String? = prefs.getString("$KEY_LANG_VOICE$language", null)

    fun setVoiceForLanguage(language: String, id: String) {
        if (voiceForLanguage(language) != id) prefs.edit { putString("$KEY_LANG_VOICE$language", id) }
    }

    /** Clipboard links the user dismissed in the library, by URL key. */
    var dismissedClipboardKey: String?
        get() = prefs.getString(KEY_CLIPBOARD_DISMISSED, null)
        set(value) = prefs.edit { putString(KEY_CLIPBOARD_DISMISSED, value) }

    private fun putBool(key: String, on: Boolean, flow: MutableStateFlow<Boolean>) {
        flow.value = on
        prefs.edit { putBoolean(key, on) }
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        runCatching { enumValueOf<T>(prefs.getString(key, null) ?: "") }.getOrDefault(default)

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
        private const val KEY_HEADINGS = "web_headings_as_chapters"
        private const val KEY_INTRO = "web_spoken_intro"
        private const val KEY_CAPTIONS = "web_read_captions"
        private const val KEY_TABLES = "web_tables"
        private const val KEY_CODE = "web_code"
        private const val KEY_KEEP_PLAYING = "keep_playing_while_loading"
        private const val KEY_LANG_VOICE = "voice_for_"
        private const val KEY_CLIPBOARD_DISMISSED = "clipboard_dismissed"
    }
}
