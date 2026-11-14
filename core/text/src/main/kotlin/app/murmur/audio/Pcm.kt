package app.murmur.audio

/** Sample conversions shared by the in-app player and the system TTS engine. */
object Pcm {

    /** Float samples in [-1, 1] to little-endian signed 16-bit PCM bytes. */
    fun floatToPcm16(samples: FloatArray, from: Int = 0, to: Int = samples.size): ByteArray {
        val out = ByteArray((to - from) * 2)
        var j = 0
        for (i in from until to) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[j++] = (v and 0xff).toByte()
            out[j++] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    /** Trims leading/trailing near-silence that models often pad onto each sentence. */
    fun trimSilence(samples: FloatArray, sampleRate: Int, threshold: Float = 0.01f, keepMs: Int = 40): FloatArray {
        if (samples.isEmpty()) return samples
        var start = 0
        while (start < samples.size && kotlin.math.abs(samples[start]) < threshold) start++
        var end = samples.size - 1
        while (end > start && kotlin.math.abs(samples[end]) < threshold) end--
        if (start >= end) return FloatArray(0)
        val keep = sampleRate * keepMs / 1000
        return samples.copyOfRange((start - keep).coerceAtLeast(0), (end + 1 + keep).coerceAtMost(samples.size))
    }

    fun silence(sampleRate: Int, ms: Int): FloatArray = FloatArray(sampleRate * ms / 1000)
}
