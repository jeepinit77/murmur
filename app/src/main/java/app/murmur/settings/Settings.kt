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

/** What to do when a shared web page is already in the library. */
enum class SavedPageMode { ASK, RESUME, IMPORT_AGAIN }

/** When notifications may be read automatically, depending on the screen. */
enum class AutoPlayScreen { SCREEN_ON, SCREEN_OFF, ANY }

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

    private val _rewindSeconds = MutableStateFlow(prefs.getInt(KEY_REWIND, 15))
    /** How far the Rewind button goes back, in seconds of listening. */
    val rewindSeconds: StateFlow<Int> = _rewindSeconds.asStateFlow()

    private val _playerCard = MutableStateFlow(prefs.getBoolean(KEY_PLAYER_CARD, false))
    /** Show the player card at the top of the reader. */
    val playerCard: StateFlow<Boolean> = _playerCard.asStateFlow()

    private val _shareMode = MutableStateFlow(
        runCatching { ShareMode.valueOf(prefs.getString(KEY_SHARE_MODE, null) ?: "") }.getOrDefault(ShareMode.REPLACE),
    )
    val shareMode: StateFlow<ShareMode> = _shareMode.asStateFlow()

    private val _savedPageMode = MutableStateFlow(enumPref(KEY_SAVED_PAGE, SavedPageMode.ASK))
    /** When sharing a page that's already in the library: ask, resume the saved copy, or import it again. */
    val savedPageMode: StateFlow<SavedPageMode> = _savedPageMode.asStateFlow()

    // ---- Web articles ------------------------------------------------------------------

    private val _headingsAsChapters = MutableStateFlow(prefs.getBoolean(KEY_HEADINGS, true))
    val headingsAsChapters: StateFlow<Boolean> = _headingsAsChapters.asStateFlow()

    private val _spokenIntro = MutableStateFlow(prefs.getBoolean(KEY_INTRO, false))
    val spokenIntro: StateFlow<Boolean> = _spokenIntro.asStateFlow()

    private val _readCaptions = MutableStateFlow(prefs.getBoolean(KEY_CAPTIONS, false))
    val readCaptions: StateFlow<Boolean> = _readCaptions.asStateFlow()

    private val _tableMode = MutableStateFlow(enumPref(KEY_TABLES, TableMode.SKIP))
    val tableMode: StateFlow<TableMode> = _tableMode.asStateFlow()

    private val _labelAiSummaries = MutableStateFlow(prefs.getBoolean(KEY_TABLE_AI_LABEL, false))
    /** Announce AI table summaries as "Table summary by AI". */
    val labelAiSummaries: StateFlow<Boolean> = _labelAiSummaries.asStateFlow()
    fun setLabelAiSummaries(on: Boolean) = putBool(KEY_TABLE_AI_LABEL, on, _labelAiSummaries)

    private val _codeMode = MutableStateFlow(enumPref(KEY_CODE, CodeMode.SKIP))
    val codeMode: StateFlow<CodeMode> = _codeMode.asStateFlow()

    private val _keepPlayingWhileLoading = MutableStateFlow(prefs.getBoolean(KEY_KEEP_PLAYING, false))
    /** In "start reading the new item" mode, keep reading the current item until the shared one is ready. */
    val keepPlayingWhileLoading: StateFlow<Boolean> = _keepPlayingWhileLoading.asStateFlow()

    private val _soundCues = MutableStateFlow(prefs.getBoolean(KEY_SOUND_CUES, true))
    /** A quiet tone while audio is being prepared, and a short sound when reading can't start. */
    val soundCues: StateFlow<Boolean> = _soundCues.asStateFlow()

    fun extractOptions() = ExtractOptions(
        headingsAsChapters = headingsAsChapters.value,
        spokenIntro = spokenIntro.value,
        readCaptions = readCaptions.value,
        tables = tableMode.value,
        labelAiSummaries = labelAiSummaries.value,
        code = codeMode.value,
    )

    fun setHeadingsAsChapters(on: Boolean) = putBool(KEY_HEADINGS, on, _headingsAsChapters)
    fun setSpokenIntro(on: Boolean) = putBool(KEY_INTRO, on, _spokenIntro)
    fun setReadCaptions(on: Boolean) = putBool(KEY_CAPTIONS, on, _readCaptions)
    fun setKeepPlayingWhileLoading(on: Boolean) = putBool(KEY_KEEP_PLAYING, on, _keepPlayingWhileLoading)
    fun setSoundCues(on: Boolean) = putBool(KEY_SOUND_CUES, on, _soundCues)

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

    // ---- Notification reading -----------------------------------------------------------

    private val _notificationReading = MutableStateFlow(prefs.getBoolean(KEY_NR_ENABLED, false))
    /** Read notifications from the chosen apps. Off by default. */
    val notificationReading: StateFlow<Boolean> = _notificationReading.asStateFlow()
    fun setNotificationReading(on: Boolean) = putBool(KEY_NR_ENABLED, on, _notificationReading)

    private val _readApps = MutableStateFlow(prefs.getStringSet(KEY_NR_APPS, null).orEmpty().toSet())
    /** Package names of the apps whose notifications Murmur reads. */
    val readApps: StateFlow<Set<String>> = _readApps.asStateFlow()
    fun setReadApp(pkg: String, on: Boolean) = putSet(KEY_NR_APPS, _readApps, pkg, on)

    private val _autoPlayApps = MutableStateFlow(prefs.getStringSet(KEY_NR_AUTOPLAY_APPS, null).orEmpty().toSet())
    /** Package names of the apps whose notifications are read as soon as they arrive. */
    val autoPlayApps: StateFlow<Set<String>> = _autoPlayApps.asStateFlow()
    fun setAutoPlayApp(pkg: String, on: Boolean) = putSet(KEY_NR_AUTOPLAY_APPS, _autoPlayApps, pkg, on)

    private val _seenApps = MutableStateFlow(prefs.getStringSet(KEY_NR_SEEN, null).orEmpty().toSet())
    /** Apps that have posted a notification since access was granted (package names only). */
    val seenApps: StateFlow<Set<String>> = _seenApps.asStateFlow()
    fun addSeenApp(pkg: String) {
        if (pkg !in _seenApps.value) putSet(KEY_NR_SEEN, _seenApps, pkg, true)
    }

    private val _notificationAutoPlay = MutableStateFlow(prefs.getBoolean(KEY_NR_AUTOPLAY, false))
    val notificationAutoPlay: StateFlow<Boolean> = _notificationAutoPlay.asStateFlow()
    fun setNotificationAutoPlay(on: Boolean) = putBool(KEY_NR_AUTOPLAY, on, _notificationAutoPlay)

    private val _autoPlayHeadphonesOnly = MutableStateFlow(prefs.getBoolean(KEY_NR_HEADPHONES, false))
    val autoPlayHeadphonesOnly: StateFlow<Boolean> = _autoPlayHeadphonesOnly.asStateFlow()
    fun setAutoPlayHeadphonesOnly(on: Boolean) = putBool(KEY_NR_HEADPHONES, on, _autoPlayHeadphonesOnly)

    private val _autoPlayRespectDnd = MutableStateFlow(prefs.getBoolean(KEY_NR_DND, true))
    val autoPlayRespectDnd: StateFlow<Boolean> = _autoPlayRespectDnd.asStateFlow()
    fun setAutoPlayRespectDnd(on: Boolean) = putBool(KEY_NR_DND, on, _autoPlayRespectDnd)

    private val _autoPlayScreen = MutableStateFlow(enumPref(KEY_NR_SCREEN, AutoPlayScreen.SCREEN_ON))
    val autoPlayScreen: StateFlow<AutoPlayScreen> = _autoPlayScreen.asStateFlow()
    fun setAutoPlayScreen(m: AutoPlayScreen) {
        _autoPlayScreen.value = m
        prefs.edit { putString(KEY_NR_SCREEN, m.name) }
    }

    /** The home screen card suggesting notification reading was dismissed. */
    var notificationTipDismissed: Boolean
        get() = prefs.getBoolean(KEY_NR_TIP_DISMISSED, false)
        set(value) = prefs.edit { putBoolean(KEY_NR_TIP_DISMISSED, value) }

    private fun putSet(key: String, flow: MutableStateFlow<Set<String>>, item: String, on: Boolean) {
        val v = if (on) flow.value + item else flow.value - item
        flow.value = v
        prefs.edit { putStringSet(key, v) }
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

    fun setRewindSeconds(seconds: Int) {
        _rewindSeconds.value = seconds
        prefs.edit { putInt(KEY_REWIND, seconds) }
    }

    fun setPlayerCard(on: Boolean) = putBool(KEY_PLAYER_CARD, on, _playerCard)

    fun setShareMode(m: ShareMode) {
        _shareMode.value = m
        prefs.edit { putString(KEY_SHARE_MODE, m.name) }
    }

    fun setSavedPageMode(m: SavedPageMode) {
        _savedPageMode.value = m
        prefs.edit { putString(KEY_SAVED_PAGE, m.name) }
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
        private const val KEY_SAVED_PAGE = "saved_page_mode"
        private const val KEY_REWIND = "rewind_seconds"
        private const val KEY_PLAYER_CARD = "player_card"
        /** Choices for [rewindSeconds]. */
        val REWIND_CHOICES = listOf(5, 10, 15, 30, 60)
        private const val KEY_HEADINGS = "web_headings_as_chapters"
        private const val KEY_INTRO = "web_spoken_intro"
        private const val KEY_CAPTIONS = "web_read_captions"
        private const val KEY_TABLES = "web_tables"
        private const val KEY_TABLE_AI_LABEL = "table_ai_label"
        private const val KEY_CODE = "web_code"
        private const val KEY_KEEP_PLAYING = "keep_playing_while_loading"
        private const val KEY_SOUND_CUES = "sound_cues"
        private const val KEY_LANG_VOICE = "voice_for_"
        private const val KEY_CLIPBOARD_DISMISSED = "clipboard_dismissed"
        private const val KEY_NR_ENABLED = "notification_reading"
        private const val KEY_NR_APPS = "notification_reading_apps"
        private const val KEY_NR_AUTOPLAY_APPS = "notification_autoplay_apps"
        private const val KEY_NR_SEEN = "notification_seen_apps"
        private const val KEY_NR_AUTOPLAY = "notification_autoplay"
        private const val KEY_NR_HEADPHONES = "notification_autoplay_headphones"
        private const val KEY_NR_DND = "notification_autoplay_dnd"
        private const val KEY_NR_SCREEN = "notification_autoplay_screen"
        private const val KEY_NR_TIP_DISMISSED = "notification_tip_dismissed"
    }
}
