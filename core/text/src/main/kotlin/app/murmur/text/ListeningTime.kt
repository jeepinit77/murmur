package app.murmur.text

import kotlin.math.roundToInt

/** Rough listening-time estimates for library rows and notifications. */
object ListeningTime {
    /** Typical neural TTS pace at 1x, in words per minute. */
    const val WORDS_PER_MINUTE = 160

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
}
