package studio.lightdata

import java.util.Locale
import kotlin.math.*

data class Format(val name: String, val w: Double, val h: Double)

enum class FocalAxis(val label: String) { DIAGONAL("对角线"), WIDTH("横边"), HEIGHT("竖边") }

fun Format.equivalentFocal(focal: Double, axis: FocalAxis): Double {
    val referenceWidth = if (w >= h) 36.0 else 24.0
    val referenceHeight = if (w >= h) 24.0 else 36.0
    return focal * when (axis) {
        FocalAxis.DIAGONAL -> hypot(36.0, 24.0) / hypot(w, h)
        FocalAxis.WIDTH -> referenceWidth / w
        FocalAxis.HEIGHT -> referenceHeight / h
    }
}

val Format.isFullFrame: Boolean
    get() = abs(max(w, h) - 36.0) < .000001 && abs(min(w, h) - 24.0) < .000001

val formatGroups = linkedMapOf(
    "数码" to listOf(
        Format("1 英寸", 13.2, 8.8),
        Format("M43", 17.3, 13.0),
        Format("APS-C", 23.5, 15.6),
        Format("APS-C Canon", 22.3, 14.9),
        Format("全幅", 36.0, 24.0),
    ),
    "数码后背" to listOf(
        Format("44×33", 44.0, 33.0),
        Format("48×36", 48.0, 36.0),
        Format("54×40", 54.0, 40.0),
    ),
    "胶片" to listOf(
        Format("135", 36.0, 24.0),
        Format("645", 56.0, 41.5),
        Format("6×6", 56.0, 56.0),
        Format("6×7", 69.0, 56.0),
        Format("6×9", 84.0, 56.0),
        Format("6×12", 112.0, 56.0),
        Format("6×17", 168.0, 56.0),
        Format("4×5", 120.0, 96.0),
        Format("8×10", 245.0, 194.0),
        Format("宝丽来 i-Type / 600 / SX-70", 78.94, 76.80),
        Format("宝丽来 Go", 47.0, 46.0),
        Format("instax mini", 62.0, 46.0),
        Format("instax SQUARE", 62.0, 62.0),
        Format("instax WIDE", 99.0, 62.0),
    ),
)
val formats = formatGroups.values.flatten()

fun mm(value: Double, inch: Boolean) = value * if (inch) 25.4 else 1.0

data class Field(val x: Double, val y: Double) { // tangents of the two half angles
    val aspect
        get() = x / y

    fun covers(other: Field) = x >= other.x && y >= other.y

    fun swap() = Field(y, x)
}

fun field(w: Double, h: Double, f: Double) = Field(w / (2 * f), h / (2 * f))

fun n(v: Double) =
    if (v == floor(v)) "%.0f".format(Locale.US, v)
    else "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')

fun signed(v: Double) = "%+.1f".format(Locale.US, v)

val apertures =
    listOf(
        0.7,
        0.8,
        0.9,
        1.0,
        1.1,
        1.2,
        1.4,
        1.6,
        1.8,
        2.0,
        2.2,
        2.5,
        2.8,
        3.2,
        3.5,
        4.0,
        4.5,
        5.0,
        5.6,
        6.3,
        7.1,
        8.0,
        9.0,
        10.0,
        11.0,
        13.0,
        14.0,
        16.0,
        18.0,
        20.0,
        22.0,
        25.0,
        29.0,
        32.0,
        36.0,
        40.0,
        45.0,
        51.0,
        57.0,
        64.0,
        72.0,
        81.0,
        90.0,
    )
val shutters =
    listOf(
            8000,
            6400,
            5000,
            4000,
            3200,
            2500,
            2000,
            1600,
            1250,
            1000,
            800,
            640,
            500,
            400,
            320,
            250,
            200,
            160,
            125,
            100,
            80,
            60,
            50,
            40,
            30,
            25,
            20,
            15,
            13,
            10,
            8,
            6,
            5,
            4,
            3,
        )
        .map { 1.0 / it } +
        listOf(
            0.4,
            0.5,
            0.6,
            0.8,
            1.0,
            1.3,
            1.6,
            2.0,
            2.5,
            3.2,
            4.0,
            5.0,
            6.0,
            8.0,
            10.0,
            13.0,
            15.0,
            20.0,
            25.0,
            30.0,
            40.0,
            50.0,
            60.0,
            120.0,
            240.0,
        )
val speeds =
    listOf(
            3,
            4,
            5,
            6,
            8,
            10,
            12,
            16,
            20,
            25,
            32,
            40,
            50,
            64,
            80,
            100,
            125,
            160,
            200,
            250,
            320,
            400,
            500,
            640,
            800,
            1000,
            1250,
            1600,
            2000,
            2500,
            3200,
            4000,
            5000,
            6400,
            12800,
            25600,
        )
        .map { it.toDouble() }

fun timeLabel(t: Double) = if (t < 0.4) "1/" + (1 / t).roundToInt() else n(t) + "″"

data class Exposure(val t: Double, val a: Double, val iso: Double) {
    val ev
        get() = log2(a * a / t * 100 / iso)
}

fun storedMode(value: String?) = when (value) {
    "S", "M" -> value
    else -> "A"
}

fun solve(mode: String, manual: Exposure, ev: Double, compensationThirds: Int = 0): Exposure {
    if (mode == "M") return manual
    val targetEv = ev - compensationThirds.coerceIn(-15, 15) / 3.0
    val light = 2.0.pow(targetEv) * manual.iso / 100
    fun closest(values: List<Double>, x: Double) = values.minBy { abs(log2(it / x)) }
    return when (mode) {
        "S" -> manual.copy(a = closest(apertures, sqrt(light * manual.t)))
        "A" -> manual.copy(t = closest(shutters, manual.a * manual.a / light))
        else -> error("Unknown exposure mode: $mode")
    }
}

// Treat the density curve as a negative viewed through a print. A low-gradient
// negative needs more print contrast for a one-stop midtone change to read naturally.
internal class NegativePrint(private val points: List<Pair<Double, Double>>) {
    private val grayDensity = densityAt(points, 0.0)
    private val midtoneSpan = (densityAt(points, 1.0) - densityAt(points, -1.0)).coerceAtLeast(.01)
    private val stopsPerDensity = (2.0 / (.82 * midtoneSpan)).coerceIn(log2(10.0), 8 * log2(10.0))

    fun positive(ev: Double): Double {
        val stops = (densityAt(points, ev) - grayDensity) * stopsPerDensity
        return 1.0 / (1.0 + (.82 / .18) * 2.0.pow(-stops))
    }
}

private fun densityAt(points: List<Pair<Double, Double>>, ev: Double): Double {
    val hit = points.binarySearchBy(ev) { it.first }
    if (hit >= 0) return points[hit].second
    val right = -hit - 1
    if (right == 0) return points.first().second
    if (right == points.size) return points.last().second
    val (a, lo) = points[right - 1]
    val (b, hi) = points[right]
    return lo + (hi - lo) * (ev - a) / (b - a)
}

data class Film(
    val name: String,
    val low: Double,
    val high: Double,
    val dots: List<Pair<Double, Double>>,
    val info: String,
    val url: String = "",
    val digital: Boolean = false,
) {
    fun density(ev: Double) = densityAt(dots, ev)
    private val negativePrint by lazy { NegativePrint(dots) }

    // Normalize the viewed positive around the reference gray.
    fun positive(ev: Double): Double {
        val direction = if (dots.last().second > dots.first().second) 1 else -1
        if (digital) return (.18 * 2.0.pow(ev.coerceIn(low, high))).coerceIn(0.0, 1.0)
        // Negative density is inverted on printing/scanning. A print shoulder retains highlight
        // separation.
        if (direction < 0) return (.18 * 10.0.pow(density(0.0) - density(ev))).coerceIn(0.0, 1.0)
        return negativePrint.positive(ev)
    }
}

fun customFilm(digital: Boolean, total: Double, above: Double): Film {
    val low = above - total
    val pts =
        (0..32).map {
            val x = it / 32.0
            (low + total * x) to if (digital) 2.6 * x else 2.6 * x * x * (3 - 2 * x)
        }
    return Film(
        if (digital) "自定义数码" else "自定义胶片",
        low,
        above,
        pts,
        "按用户输入的宽容度生成近似响应。",
        digital = digital,
    )
}

val defaultResponse = DoubleArray(256) { log2(srgbToLinear(max(.5, it.toDouble()) / 255) / .18) }

data class SensorReading(val exposure: Exposure, val postRawBoost: Double)

data class Frame(
    val w: Int,
    val h: Int,
    val pixels: IntArray,
    val cameraEv: Double,
    val id: String,
    val timestampNs: Long = 0,
    val encoding: YuvEncoding = YuvEncoding.default,
    val sensorReading: SensorReading? = null,
) {
    fun sample(x: Double, y: Double): Int =
        pixels[(y * h).toInt().coerceIn(0, h - 1) * w + (x * w).toInt().coerceIn(0, w - 1)]

    fun distribution(): IntArray {
        val hist = IntArray(256)
        for (pixel in pixels) if (pixel ushr 24 != 0) hist[luma(pixel)]++
        return hist
    }

    fun spot(x: Double, y: Double, response: DoubleArray): Double {
        var sum = 0.0
        var count = 0
        for (dy in -2..2) for (dx in -2..2) {
            val pixel = sample(x + dx.toDouble() / w, y + dy.toDouble() / h)
            if (pixel ushr 24 != 0) {
                sum += 2.0.pow(response[luma(pixel)])
                count++
            }
        }
        return if (count > 0) cameraEv + log2(sum / count) else Double.NaN
    }
}

fun percentile(h: IntArray, q: Double): Int {
    val target = max(1, (h.sum() * q).roundToInt())
    var c = 0
    for (i in h.indices) {
        c += h[i]
        if (c >= target) return i
    }
    return h.lastIndex
}

// Conservative scene-level advisory on pre-preview measurements. The EV thresholds
// are heuristics, independent of film speed and the chosen target exposure.
internal fun dimMeterWarning(sceneEv100: Double?, histogram: IntArray): Boolean {
    if (sceneEv100 == null || !sceneEv100.isFinite() || histogram.none { it > 0 }) return false
    return sceneEv100 <= 0.0 || (sceneEv100 <= 3.0 && percentile(histogram, .9) <= 16)
}

fun meter(hist: IntArray, response: DoubleArray): Double {
    var s = 0.0
    val lo = percentile(hist, .02)
    val hi = percentile(hist, .98)
    for (i in hist.indices) s += hist[i] * 2.0.pow(response[i.coerceIn(lo, hi)])
    return log2(max(1e-8, s / hist.sum().coerceAtLeast(1)))
}
