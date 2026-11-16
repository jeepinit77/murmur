package app.murmur.ui.theme

import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tonal palettes for Murmur's preset colors, built in OKLCH so each step of lightness looks
 * evenly spaced whatever the hue. Tone uses the same 0–100 scale as Material 3 (CIE L*), so the
 * usual Material tone choices (primary 40 in light theme, 80 in dark, and so on) apply.
 */
class TonalPalette(private val hue: Double, private val chroma: Double) {
    /** ARGB color at [tone] (0 black … 100 white), with chroma reduced as needed to stay in sRGB. */
    fun tone(tone: Int): Int = Tones.argb(tone.toDouble(), chroma, hue)
}

object Tones {
    /** OKLab lightness that gives the same luminance as CIE L* [tone]. */
    private fun okLightness(tone: Double): Double {
        val t = tone.coerceIn(0.0, 100.0)
        val fy = (t + 16) / 116
        val y = if (t > 8.0) fy * fy * fy else t / 903.2963
        return cbrt(y)
    }

    fun argb(tone: Double, chroma: Double, hueDegrees: Double): Int {
        if (tone <= 0.0) return 0xFF000000.toInt()
        if (tone >= 100.0) return 0xFFFFFFFF.toInt()
        val l = okLightness(tone)
        val h = hueDegrees * PI / 180
        // Largest chroma up to the requested one that stays inside sRGB.
        var lo = 0.0
        var hi = chroma
        var rgb = linear(l, 0.0, h)
        if (inGamut(linear(l, hi, h))) {
            rgb = linear(l, hi, h)
        } else {
            repeat(20) {
                val mid = (lo + hi) / 2
                val c = linear(l, mid, h)
                if (inGamut(c)) { lo = mid; rgb = c } else hi = mid
            }
        }
        val r = encode(rgb[0])
        val g = encode(rgb[1])
        val b = encode(rgb[2])
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun linear(l: Double, c: Double, h: Double): DoubleArray {
        val a = c * cos(h)
        val b = c * sin(h)
        val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1,
            -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1,
            -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1,
        )
    }

    private fun inGamut(c: DoubleArray) = c.all { it >= -0.0001 && it <= 1.0001 }

    private fun encode(v: Double): Int {
        val x = v.coerceIn(0.0, 1.0)
        val s = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
        return (s * 255).roundToInt().coerceIn(0, 255)
    }
}
