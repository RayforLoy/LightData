package studio.lightdata

import kotlin.math.*

fun srgbToLinear(value: Double): Double =
    if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)

fun linearToSrgb(value: Double): Double =
    if (value <= .0031308) 12.92 * value else 1.055 * value.pow(1 / 2.4) - .055

private val toLinear16 = IntArray(256) { (srgbToLinear(it / 255.0) * 65535).roundToInt() }
private val grayCode = IntArray(65536) { (255 * linearToSrgb(it / 65535.0)).roundToInt() }

internal fun scaleSrgbCode(code: Int, linearGain: Double): Int =
    grayCode[(toLinear16[code] * linearGain).roundToInt().coerceIn(0, 65535)]

internal fun blendSrgbCode(a: Int, b: Int, weight: Double): Int =
    grayCode[(toLinear16[a] + weight*(toLinear16[b]-toLinear16[a])).roundToInt().coerceIn(0,65535)]

/** Gray code with the same linear Rec.709 luminance as this sRGB pixel. */
fun luma(rgb: Int): Int {
    val r = toLinear16[rgb ushr 16 and 255]
    val g = toLinear16[rgb ushr 8 and 255]
    val b = toLinear16[rgb and 255]
    return grayCode[(r * .2126 + g * .7152 + b * .0722).roundToInt()]
}

/** Camera2 uses full-range JFIF and sRGB unless the image declares a different encoding. */
data class YuvEncoding(val limited: Boolean = false, val bt709: Boolean = false, val videoTransfer: Boolean = false) {
    private val offset = if (limited) 16 else 0
    private val yScale = if (limited) 298 else 256
    private val uvScale = if (limited) 255 / 224.0 else 1.0
    private val kr = if (bt709) .2126 else .299
    private val kb = if (bt709) .0722 else .114
    private val redV = (512 * (1 - kr) * uvScale).roundToInt()
    private val blueU = (512 * (1 - kb) * uvScale).roundToInt()
    private val greenU = (-kb * blueU / (1 - kr - kb)).roundToInt()
    private val greenV = (-kr * redV / (1 - kr - kb)).roundToInt()
    internal val gpuYuv by lazy { floatArrayOf(offset.toFloat(),yScale.toFloat(),redV.toFloat(),
        blueU.toFloat(),greenU.toFloat(),greenV.toFloat()) }
    private val rgbCodes = IntArray(256) { i ->
        if (!videoTransfer) i else {
            val v = i / 255.0
            val linear = if (v < .081) v / 4.5 else ((v + .099) / 1.099).pow(1 / .45)
            (255 * linearToSrgb(linear)).roundToInt().coerceIn(0, 255)
        }
    }

    fun rgb(y: Int, u: Int, v: Int): Int {
        val base = (y - offset) * yScale + 128
        val cb = u - 128
        val cr = v - 128
        val r = rgbCodes[((base + redV * cr) shr 8).coerceIn(0, 255)]
        val g = rgbCodes[((base + greenU * cb + greenV * cr) shr 8).coerceIn(0, 255)]
        val b = rgbCodes[((base + blueU * cb) shr 8).coerceIn(0, 255)]
        return -0x1000000 or (r shl 16) or (g shl 8) or b
    }

    /** Old reference-card curves were indexed by a limited-range decode for every stream. */
    fun legacyLut(original: DoubleArray): DoubleArray = DoubleArray(256) { gray ->
        val linear = srgbToLinear(gray / 255.0)
        val encoded = if (!videoTransfer) gray / 255.0 else if (linear < .018) 4.5 * linear
            else 1.099 * linear.pow(.45) - .099
        val y = if (limited) 16 + 219 * encoded else 255 * encoded
        original[((y - 16) * 255 / 219).roundToInt().coerceIn(0, 255)]
    }

    companion object { val default = YuvEncoding() }
}

enum class FilmLimit { SHADOW, IN_RANGE, HIGHLIGHT }
fun Film.limit(relativeEv: Double): FilmLimit = when {
    relativeEv < low -> FilmLimit.SHADOW
    relativeEv > high -> FilmLimit.HIGHLIGHT
    else -> FilmLimit.IN_RANGE
}

data class HistogramWindow(val left: Double = -4.0, val span: Double = 24.0, val gain: Double = 1.0) {
    companion object {
        fun automatic(sceneEv: Double?, zoneV: Double, followZone: Boolean): HistogramWindow =
            if (followZone) HistogramWindow(zoneV - 8.0, 16.0) else following(sceneEv)

        fun following(sceneEv: Double?) = if (sceneEv == null) HistogramWindow()
            else HistogramWindow(2 * round(sceneEv / 2) - 8, 16.0)
    }
    val right get() = left + span
    fun fraction(ev: Double) = (ev - left) / span
    /** Keep the scene EV under the finger fixed as horizontal span changes. Positive pan moves the trace right. */
    fun transform(pan: Double, anchor: Double, zoomX: Double, zoomY: Double): HistogramWindow {
        val newSpan = (span / zoomX).coerceIn(2.0, 64.0)
        val newLeft = (left + span * anchor - newSpan * (anchor + pan)).coerceIn(-64.0, 64.0 - newSpan)
        return HistogramWindow(newLeft, newSpan, (gain * zoomY).coerceIn(.25, 16.0))
    }
    fun bins(hist: IntArray, response: DoubleArray, cameraEv: Double, count: Int): IntArray {
        val bins = IntArray(count)
        for (i in hist.indices) {
            val fraction = fraction(cameraEv + response[i])
            if (fraction in 0.0..1.0) bins[min(count - 1, (fraction * count).toInt())] += hist[i]
        }
        return bins
    }
}
