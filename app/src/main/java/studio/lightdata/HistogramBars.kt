package studio.lightdata

import androidx.compose.runtime.*
import android.os.SystemClock

/** Display-only counts. Exposure, spot readings and reports use the original measurements. */
internal class HistogramBars(initial: IntArray) {
    private var start = FloatArray(initial.size) { initial[it].toFloat() }
    private var target by mutableStateOf(initial)
    private var startPeak = initial.max().coerceAtLeast(1).toFloat()
    private var targetPeak by mutableFloatStateOf(startPeak)
    var fraction by mutableFloatStateOf(1f)
        private set
    var source: IntArray? = null
    var sourceEv = 0.0
    var left = Double.NaN
    var span = Double.NaN
    var lastUpdate = 0L

    fun value(index: Int) = start[index] + (target[index] - start[index]) * fraction
    fun peak() = startPeak + (targetPeak - startPeak) * fraction
    fun retarget(next: IntArray, projected: IntArray?, animate: Boolean) {
        val from = FloatArray(next.size) { projected?.get(it)?.toFloat() ?: value(it) }
        startPeak = projected?.max()?.coerceAtLeast(1)?.toFloat() ?: peak()
        start = from
        target = next
        targetPeak = next.max().coerceAtLeast(1).toFloat()
        fraction = if (animate) 0f else 1f
    }
    fun advance(amount: Float) { fraction = amount.coerceIn(0f, 1f) }
}

@Composable
internal fun displayHistogram(bins: IntArray, raw: IntArray?, ev: Double, chart: HistogramWindow,
    response: DoubleArray, enabled: Boolean, lens: String?, scanning: Boolean): HistogramBars {
    val bars = remember(enabled, lens, scanning, response, raw == null) { HistogramBars(bins) }
    LaunchedEffect(bars, bins) {
        val now = SystemClock.elapsedRealtime()
        val old = bars.source
        val interpolate = enabled && raw != null && old != null && old !== raw
        val projected = if (interpolate && (chart.left != bars.left || chart.span != bars.span))
            chart.bins(requireNotNull(old), response, bars.sourceEv, bins.size) else null
        val duration = (now - bars.lastUpdate).coerceIn(16L, 180L)
        bars.retarget(bins, projected, interpolate)
        bars.source = raw
        bars.sourceEv = ev
        bars.left = chart.left
        bars.span = chart.span
        if (old !== raw) bars.lastUpdate = now
        if (interpolate) {
            val first = withFrameNanos { it }
            do {
                val elapsed = withFrameNanos { it - first }
                bars.advance(elapsed / (duration * 1_000_000f))
            } while (bars.fraction < 1f)
        }
    }
    return bars
}
