package studio.lightdata

import android.text.InputType
import kotlin.math.*

/** A dial moves to the next physical mark, even when starting at a typed value. */
internal fun stepFromValue(stops: List<Double>, value: Double, steps: Int): Double {
    if (steps == 0) return value
    val hit = stops.binarySearch(value)
    val origin = if (hit >= 0) hit else if (steps > 0) -hit - 2 else -hit - 1
    return stops[(origin + steps).coerceIn(stops.indices)]
}

internal fun parseShutterSeconds(text: String): Double? {
    val parts = text.trim().split('/')
    if (parts.size > 2) return null
    val numerator = parts[0].trim().toDoubleOrNull() ?: return null
    val denominator = if (parts.size == 2) parts[1].trim().toDoubleOrNull() ?: return null else 1.0
    if (!numerator.isFinite() || !denominator.isFinite() || numerator <= 0 || denominator <= 0) return null
    return (numerator / denominator).takeIf { it in 0.000001..86400.0 }
}

internal fun MainActivity.editSubjectDistance() {
    val near = max(1.0, ceil(focal + 1 + max(0.0, -parallax.zMm)))
    inputValue("被摄物至焦平面的距离（mm，支持 ∞）",
        if (parallax.distanceMm.isFinite()) isoLabel(parallax.distanceMm) else "∞",
        InputType.TYPE_CLASS_TEXT, "请输入至少 ${n(near)} mm 的有效距离，或输入 ∞", { text ->
            if (text == "∞" || text == "无限远" || text.equals("inf", ignoreCase = true)) Double.POSITIVE_INFINITY
            else text.toDoubleOrNull()?.takeIf { it.isFinite() && parallax.copy(distanceMm = it).valid(focal) }
        }) { value ->
            updateParallax(parallax.copy(distanceMm = value))
        }
}

internal fun MainActivity.editExposureValue(shutter: Boolean) {
    if (frozen) return
    val priority = if (shutter) "S" else "A"
    val changeMode = mode != "M" && mode != priority
    val suffix = if (changeMode) " · 应用后切换到 $priority 档" else ""
    fun apply(value: Double) {
        if (frozen) return
        if (changeMode) modeTo(priority)
        setValues(if (shutter) manual.copy(t = value) else manual.copy(a = value))
    }
    if (shutter) {
        val current = exposure.t
        val reciprocal = 1 / current
        val initial = if (current < 1 && abs(reciprocal - round(reciprocal)) < 1e-8)
            "1/${round(reciprocal).toInt()}" else isoLabel(current)
        inputValue("快门速度（秒，如 1/125 或 0.5）$suffix", initial,
            InputType.TYPE_CLASS_TEXT, "请输入 1/1000000 至 86400 秒，可使用分数", ::parseShutterSeconds, ::apply)
    } else input("光圈（f 值）$suffix", exposure.a, 0.1..1024.0, ::apply)
}
