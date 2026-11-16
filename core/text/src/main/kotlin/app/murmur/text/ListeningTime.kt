package app.murmur.text

import kotlin.math.roundToInt

/** Rough listening-time estimates for library rows and notifications. */
object ListeningTime {
    /** Typical neural TTS pace at 1x, in words per minute. */
    const val WORDS_PER_MINUTE = 160
    /** Typical silent reading pace, in words per minute. */
    const val READING_WORDS_PER_MINUTE = 238

    private val WORD = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}]+)?""")

    fun words(text: String): Int = WORD.findAll(text).count()

    fun minutes(words: Int, speed: Float = 1f): Double = words / (WORDS_PER_MINUTE * speed.coerceAtLeast(0.1f)).toDouble()

    /** "Less than 1 min", "12 min", "1 hr 5 min". */
    fun label(words: Int, speed: Float = 1f): String {
        val m = minutes(words, speed)
        if (m < 1) return "Less than 1 min"
        val total = m.roundToInt()
        if (total < 60) return "$total min"
        val h = total / 60
        val rest = total % 60
        return if (rest == 0) "$h hr" else "$h hr $rest min"
    }

    /** Silent reading time, "< 1 min" for short items. */
    fun readingLabel(words: Int): String = shortLabel(words, READING_WORDS_PER_MINUTE / WORDS_PER_MINUTE.toFloat())

    /** Like [label], but "< 1 min" for short items, to fit library rows. */
    fun shortLabel(words: Int, speed: Float = 1f): String =
        if (minutes(words, speed) < 1) "< 1 min" else label(words, speed)

    /**
     * Time left for a library row: "2 min (8 min total)" partway through, otherwise the total.
     * [progress] is the current sentence index out of [segmentCount].
     */
    fun remainingLabel(words: Int, progress: Int, segmentCount: Int, speed: Float = 1f): String {
        val total = shortLabel(words, speed)
        if (progress <= 0 || segmentCount <= 0) return total
        val left = (words * (1 - progress.toDouble() / segmentCount)).roundToInt().coerceAtLeast(0)
        return "${shortLabel(left, speed)} ($total total)"
    }
}
