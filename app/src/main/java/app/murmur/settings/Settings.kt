package app.murmur.settings

import android.content.Context
import androidx.core.content.edit
import app.murmur.notifications.SpokenParts
import app.murmur.ui.theme.Palette
import app.murmur.ui.theme.ThemeMode
import app.murmur.web.CodeMode
import app.murmur.web.ExtractOptions
import app.murmur.web.TableMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ShareMode { REPLACE, QUEUE }

/** What to do when a shared web page is already in the library. */
enum class SavedPageMode { ASK, RESUME, IMPORT_AGAIN }

/** Typeface for article text in the reader. */
enum class ReaderFont(val label: String) {
    SANS_SERIF("Sans serif"),
    SERIF("Serif"),
    CONDENSED("Condensed"),
    MONOSPACE("Monospace"),
}

/** How article text lines up in the reader. */
enum class ReaderAlignment(val label: String) { LEFT("Left"), JUSTIFIED("Justified") }

/** Line height for article text in the reader, as a multiple of the text size. */
enum class LineSpacing(val label: String, val multiplier: Float) {
    COMPACT("Compact", 1.35f),
    NORMAL("Normal", 1.6f),
    WIDE("Wide", 1.9f),
}

/** Background and text colors for articles in the reader. */
enum class ReadingColors(val label: String) {
    APP("Match app"),
    WHITE("White"),
    GRAY("Gray"),
    SEPIA("Sepia"),
    DARK("Dark"),
    BLACK("Black"),
    /** Red text on black, for reading at night. */
    RED("Red"),
}

/** Side margins for article text in the reader. */
enum class ReaderMargins(val label: String, val dp: Int) {
    NARROW("Narrow", 12),
    NORMAL("Normal", 20),
    WIDE("Wide", 36),
}

/** When notifications may be read automatically, depending on the screen. */
enum class AutoPlayScreen { SCREEN_ON, SCREEN_OFF, ANY }

/**
 * How one app's notifications are read: what's read aloud, and the conditions for reading them
 * automatically when the app has auto-play on.
 */
data class ReadingRule(
    val headphonesOnly: Boolean = false,
    /** Don't auto-play while Do Not Disturb is on. */
    val respectDnd: Boolean = true,
    val screen: AutoPlayScreen = AutoPlayScreen.SCREEN_ON,
    /** Don't auto-play while the ringer is set to silent. */
    val respectSilent: Boolean = true,
    /** Don't auto-play while the ringer is set to vibrate. */
    val respectVibrate: Boolean = false,
    val parts: SpokenParts = SpokenParts.APP_SENDER_AND_MESSAGE,
    /** Play a short sound before reading automatically. */
    val alertSound: Boolean = true,
) {
    fun encode() = "$headphonesOnly;$respectDnd;${screen.name};$respectSilent;$respectVibrate;${parts.name};$alertSound"

    companion object {
        fun decode(s: String?): ReadingRule {
            val p = s?.split(';') ?: return ReadingRule()
            val dnd = p.getOrNull(1)?.toBooleanStrictOrNull() ?: true
            return ReadingRule(
                headphonesOnly = p.getOrNull(0)?.toBooleanStrictOrNull() ?: false,
                respectDnd = dnd,
                screen = runCatching { AutoPlayScreen.valueOf(p.getOrNull(2) ?: "") }.getOrDefault(AutoPlayScreen.SCREEN_ON),
                // Rules saved before these were separate had one switch for all three modes.
                respectSilent = p.getOrNull(3)?.toBooleanStrictOrNull() ?: dnd,
                respectVibrate = p.getOrNull(4)?.toBooleanStrictOrNull() ?: dnd,
                parts = runCatching { SpokenParts.valueOf(p.getOrNull(5) ?: "") }.getOrDefault(SpokenParts.APP_SENDER_AND_MESSAGE),
                alertSound = p.getOrNull(6)?.toBooleanStrictOrNull() ?: true,
            )
        }
    }
}

/** Small preference store exposing each value as a StateFlow for Compose. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE).also {
        // Left over from when Murmur was also a system TTS engine.
        it.edit { remove("system_voice") }
        // Left over from when Google voices had to be turned on; they're now offered when available.
        it.edit { remove("google_voices") }
    }

    private val _voice = MutableStateFlow(migrateVoiceId(prefs.getString(KEY_VOICE, null)))
    /** Id of the selected voice, e.g. "sherpa:kokoro-en-v0_19#1" or "mlkit:en-us-x-star02". */
    val voice: StateFlow<String?> = _voice.asStateFlow()

    private val _speed = MutableStateFlow(prefs.getFloat(KEY_SPEED, 1.0f))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _paragraphPauseMs = MutableStateFlow(prefs.getInt(KEY_PARA_PAUSE, 450))
    val paragraphPauseMs: StateFlow<Int> = _paragraphPauseMs.asStateFlow()

    private val _rewindSeconds = MutableStateFlow(prefs.getInt(KEY_REWIND, 15))
    /** How far the Rewind button goes back, in seconds of listening. */
    val rewindSeconds: StateFlow<Int> = _rewindSeconds.asStateFlow()

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

    // ---- Appearance ----------------------------------------------------------------------

    private val _themeMode = MutableStateFlow(enumPref(KEY_THEME_MODE, ThemeMode.SYSTEM))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    fun setThemeMode(m: ThemeMode) {
        _themeMode.value = m
        prefs.edit { putString(KEY_THEME_MODE, m.name) }
    }

    private val _palette = MutableStateFlow(enumPref(KEY_PALETTE, Palette.VIOLET))
    val palette: StateFlow<Palette> = _palette.asStateFlow()
    fun setPalette(p: Palette) {
        _palette.value = p
        prefs.edit { putString(KEY_PALETTE, p.name) }
    }

    private val _blackBackground = MutableStateFlow(prefs.getBoolean(KEY_BLACK_BACKGROUND, false))
    /** Pure black instead of dark gray in the dark theme. */
    val blackBackground: StateFlow<Boolean> = _blackBackground.asStateFlow()
    fun setBlackBackground(on: Boolean) = putBool(KEY_BLACK_BACKGROUND, on, _blackBackground)

    // ---- Reader text ---------------------------------------------------------------------

    private val _textSize = MutableStateFlow(prefs.getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE))
    /** Article text size in the reader, in sp. */
    val textSize: StateFlow<Int> = _textSize.asStateFlow()
    fun setTextSize(sp: Int) {
        val v = sp.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        _textSize.value = v
        prefs.edit { putInt(KEY_TEXT_SIZE, v) }
    }

    private val _readerFont = MutableStateFlow(enumPref(KEY_READER_FONT, ReaderFont.SANS_SERIF))
    val readerFont: StateFlow<ReaderFont> = _readerFont.asStateFlow()
    fun setReaderFont(f: ReaderFont) {
        _readerFont.value = f
        prefs.edit { putString(KEY_READER_FONT, f.name) }
    }

    private val _lineSpacing = MutableStateFlow(enumPref(KEY_LINE_SPACING, LineSpacing.NORMAL))
    val lineSpacing: StateFlow<LineSpacing> = _lineSpacing.asStateFlow()
    fun setLineSpacing(l: LineSpacing) {
        _lineSpacing.value = l
        prefs.edit { putString(KEY_LINE_SPACING, l.name) }
    }

    private val _readingColors = MutableStateFlow(enumPref(KEY_READING_COLORS, ReadingColors.APP))
    val readingColors: StateFlow<ReadingColors> = _readingColors.asStateFlow()
    fun setReadingColors(c: ReadingColors) {
        _readingColors.value = c
        prefs.edit { putString(KEY_READING_COLORS, c.name) }
    }

    private val _readerMargins = MutableStateFlow(enumPref(KEY_READER_MARGINS, ReaderMargins.NORMAL))
    val readerMargins: StateFlow<ReaderMargins> = _readerMargins.asStateFlow()
    fun setReaderMargins(m: ReaderMargins) {
        _readerMargins.value = m
        prefs.edit { putString(KEY_READER_MARGINS, m.name) }
    }

    private val _readerAlignment = MutableStateFlow(enumPref(KEY_READER_ALIGNMENT, ReaderAlignment.LEFT))
    val readerAlignment: StateFlow<ReaderAlignment> = _readerAlignment.asStateFlow()
    fun setReaderAlignment(a: ReaderAlignment) {
        _readerAlignment.value = a
        prefs.edit { putString(KEY_READER_ALIGNMENT, a.name) }
    }

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(KEY_KEEP_SCREEN_ON, false))
    /** Keep the screen on while an article is open and not playing. */
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()
    fun setKeepScreenOn(on: Boolean) = putBool(KEY_KEEP_SCREEN_ON, on, _keepScreenOn)

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
    fun setReadApp(pkg: String, on: Boolean) {
        putSet(KEY_NR_APPS, _readApps, pkg, on)
        if (on && pkg !in _readingRules.value) _readingRules.value = _readingRules.value + (pkg to readingRule(pkg))
    }

    private val _autoPlayApps = MutableStateFlow(prefs.getStringSet(KEY_NR_AUTOPLAY_APPS, null).orEmpty().toSet())
    /** Package names of the apps whose notifications are read as soon as they arrive. */
    val autoPlayApps: StateFlow<Set<String>> = _autoPlayApps.asStateFlow()
    fun setAutoPlayApp(pkg: String, on: Boolean) {
        putSet(KEY_NR_AUTOPLAY_APPS, _autoPlayApps, pkg, on)
        if (on && pkg !in _readingRules.value) _readingRules.value = _readingRules.value + (pkg to readingRule(pkg))
    }

    private val _seenApps = MutableStateFlow(prefs.getStringSet(KEY_NR_SEEN, null).orEmpty().toSet())
    /** Apps that have posted a notification since access was granted (package names only). */
    val seenApps: StateFlow<Set<String>> = _seenApps.asStateFlow()

    private val _lastNotified = MutableStateFlow(
        prefs.all.mapNotNull { (k, v) -> if (k.startsWith(KEY_NR_LAST) && v is Long) k.removePrefix(KEY_NR_LAST) to v else null }.toMap(),
    )
    /** When each app last posted a notification, in epoch milliseconds. Only the time is kept. */
    val lastNotified: StateFlow<Map<String, Long>> = _lastNotified.asStateFlow()

    /** Records that [pkg] posted a notification at [time]. */
    fun addSeenApp(pkg: String, time: Long = System.currentTimeMillis()) {
        if (pkg !in _seenApps.value) putSet(KEY_NR_SEEN, _seenApps, pkg, true)
        val last = _lastNotified.value[pkg] ?: 0L
        // Busy apps post often; a minute's precision is enough for sorting and display.
        if (time - last >= 60_000L) {
            _lastNotified.value = _lastNotified.value + (pkg to time)
            prefs.edit { putLong("$KEY_NR_LAST$pkg", time) }
        }
    }

    private val _readingRules = MutableStateFlow(
        (_readApps.value + _autoPlayApps.value).associateWith { ReadingRule.decode(prefs.getString("$KEY_NR_RULE$it", null)) },
    )
    /** How each app in [readApps] is read. */
    val readingRules: StateFlow<Map<String, ReadingRule>> = _readingRules.asStateFlow()

    fun readingRule(pkg: String): ReadingRule = _readingRules.value[pkg] ?: ReadingRule.decode(prefs.getString("$KEY_NR_RULE$pkg", null))

    fun setReadingRule(pkg: String, rule: ReadingRule) {
        _readingRules.value = _readingRules.value + (pkg to rule)
        prefs.edit { putString("$KEY_NR_RULE$pkg", rule.encode()) }
    }

    /** The welcome flow was finished or skipped. */
    var welcomeDone: Boolean
        get() = prefs.getBoolean(KEY_WELCOME_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_WELCOME_DONE, value) }

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
        private const val KEY_PARA_PAUSE = "paragraph_pause"
        private const val KEY_SHARE_MODE = "share_mode"
        private const val KEY_SAVED_PAGE = "saved_page_mode"
        private const val KEY_REWIND = "rewind_seconds"
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
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_PALETTE = "palette"
        private const val KEY_BLACK_BACKGROUND = "black_background"
        private const val KEY_LANG_VOICE = "voice_for_"
        private const val KEY_TEXT_SIZE = "reader_text_size"
        private const val KEY_READER_FONT = "reader_font"
        private const val KEY_LINE_SPACING = "reader_line_spacing"
        private const val KEY_KEEP_SCREEN_ON = "reader_keep_screen_on"
        private const val KEY_READING_COLORS = "reader_colors"
        private const val KEY_READER_MARGINS = "reader_margins"
        private const val KEY_READER_ALIGNMENT = "reader_alignment"
        const val DEFAULT_TEXT_SIZE = 19
        const val MIN_TEXT_SIZE = 14
        const val MAX_TEXT_SIZE = 30
        private const val KEY_CLIPBOARD_DISMISSED = "clipboard_dismissed"
        private const val KEY_NR_ENABLED = "notification_reading"
        private const val KEY_NR_APPS = "notification_reading_apps"
        private const val KEY_NR_AUTOPLAY_APPS = "notification_autoplay_apps"
        private const val KEY_NR_SEEN = "notification_seen_apps"
        private const val KEY_NR_LAST = "notification_last_"
        /** Followed by a package name. */
        private const val KEY_NR_RULE = "notification_autoplay_rule_"
        private const val KEY_NR_TIP_DISMISSED = "notification_tip_dismissed"
        private const val KEY_WELCOME_DONE = "welcome_done"
    }
}
