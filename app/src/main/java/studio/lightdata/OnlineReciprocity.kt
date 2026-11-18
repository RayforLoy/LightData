package studio.lightdata

import kotlin.math.ln
import kotlin.math.pow

private const val METERGEIST = "https://metergeist.com/film/reciprocity-calculator"
private const val F8T = "https://f8t.app/reciprocity.html"

// When the source also publishes a chart, preserve its points. Interpolate in log time;
// continue from the final point with its quoted exponent to avoid a discontinuity.
internal data class OnlineCurve(val power: Double, val url: String, val points: List<Pair<Double,Double>> = emptyList()) {
    fun seconds(time: Double, unchangedThrough: Double): Double {
        if (points.isEmpty()) return time.pow(power)
        val data = points.filter { it.first > unchangedThrough }
        var x = unchangedThrough
        var y = unchangedThrough
        for ((next, corrected) in data) {
            if (time <= next) return y * (corrected / y).pow(ln(time/x) / ln(next/x))
            x = next
            y = corrected
        }
        return y * (time/x).pow(power)
    }
}

internal val onlineReciprocity = mapOf(
    "PORTRA 800" to OnlineCurve(1.40, F8T, listOf(1.0 to 2.0, 2.0 to 4.0, 4.0 to 10.0, 8.0 to 22.0, 10.0 to 30.0, 15.0 to 50.0, 30.0 to 130.0, 60.0 to 320.0)),
    "VELVIA 100" to OnlineCurve(1.04, F8T, listOf(1.0 to 1.0, 30.0 to 30.0, 60.0 to 60.0, 120.0 to 152.0, 240.0 to 340.0, 480.0 to 780.0)),
    "FUJICOLOR C200" to OnlineCurve(1.25, F8T, listOf(1.0 to 1.0, 2.0 to 2.0, 4.0 to 5.0, 8.0 to 9.0, 16.0 to 25.0, 30.0 to 52.0, 60.0 to 120.0, 120.0 to 280.0)),
    "FUJICOLOR 100" to OnlineCurve(1.50, F8T),
    "NATURA 1600" to OnlineCurve(1.45, F8T),
    "RPX 25" to OnlineCurve(1.35, F8T, listOf(2.0 to 3.0, 10.0 to 21.0, 20.0 to 54.0, 50.0 to 181.0)),
    "INFRARED 400" to OnlineCurve(1.43, F8T),
    "RETRO 80S" to OnlineCurve(1.49, F8T),
    "RETRO 400S" to OnlineCurve(1.28, F8T),
    "SUPERPAN 200" to OnlineCurve(1.20, F8T),
    "CHS 100 II" to OnlineCurve(1.40, F8T, listOf(1.0 to 1.0, 2.0 to 3.0, 4.0 to 8.0, 8.0 to 20.0, 15.0 to 45.0, 30.0 to 120.0, 60.0 to 390.0)),
    "HR-50" to OnlineCurve(1.20, F8T, listOf(0.5 to 0.6, 1.0 to 1.3, 2.0 to 2.4, 4.0 to 5.6, 8.0 to 13.0, 15.0 to 28.0, 30.0 to 75.0)),
    "APX 100" to OnlineCurve(1.53, F8T, listOf(1.0 to 2.0, 2.0 to 4.0, 4.0 to 11.0, 8.0 to 29.0, 10.0 to 39.0, 20.0 to 107.0, 30.0 to 195.0, 40.0 to 300.0, 50.0 to 420.0, 60.0 to 552.0, 100.0 to 800.0)),
    "Vista 200" to OnlineCurve(1.25, F8T, listOf(1.0 to 1.0, 2.0 to 2.0, 4.0 to 5.0, 8.0 to 11.0, 16.0 to 25.0, 32.0 to 56.0, 64.0 to 128.0)),
    "APX 400" to OnlineCurve(1.53, F8T),
    "Vista 400" to OnlineCurve(1.30, F8T),
    "Ultra 100" to OnlineCurve(1.20, F8T),
    "P33" to OnlineCurve(1.40, F8T),
    "Earl Grey 100" to OnlineCurve(1.70, F8T),
    "Lady Grey 400" to OnlineCurve(1.62, F8T),
    "Potsdam Kino 100" to OnlineCurve(1.35, F8T),
    "Berlin Kino 400" to OnlineCurve(1.35, F8T),
    "PORTRA 400" to OnlineCurve(1.3, METERGEIST),
    "PORTRA 160" to OnlineCurve(1.3, METERGEIST),
    "GOLD 200" to OnlineCurve(1.3, METERGEIST),
    "ULTRAMAX 400" to OnlineCurve(1.3, METERGEIST),
    "EKTAR 100" to OnlineCurve(1.18, METERGEIST),
    "Color Negative 400" to OnlineCurve(1.3, METERGEIST),
    "Color Negative 800" to OnlineCurve(1.3, METERGEIST),
    "EKTACHROME E100" to OnlineCurve(1.2, METERGEIST),
    "PRO 400H" to OnlineCurve(1.25, METERGEIST),
    "VELVIA 50" to OnlineCurve(1.25, METERGEIST),
    "PROVIA 100F" to OnlineCurve(1.22, METERGEIST),
)
