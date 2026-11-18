package studio.lightdata

import kotlin.math.pow
import kotlin.math.log2

/** Ground-glass brightness already includes the working aperture and bellows extension. */
internal data class GroundCalibration(val offsetEv: Double, val referenceAperture: Double) {
    fun equivalentSceneEv(screenEv: Double, workingAperture: Double) =
        screenEv + offsetEv + 2 * log2(workingAperture/referenceAperture)

    fun seconds(screenEv: Double, iso: Double): Double =
        referenceAperture * referenceAperture * (100.0 / iso) / 2.0.pow(screenEv + offsetEv)
}

/** Accept only distinct, consecutive stable center samples acquired after starting the read. */
internal class GroundBurst(private var lastTimestamp: Long) {
    private val readings = ArrayDeque<Double>()
    fun observe(timestamp: Long, ev: Double?): Double? {
        if (timestamp <= lastTimestamp) return null
        lastTimestamp = timestamp
        if (ev == null || !ev.isFinite()) {
            readings.clear()
            return null
        }
        readings.addLast(ev)
        if (readings.size < 5) return null
        if (readings.size > 5) readings.removeFirst()
        val sorted = readings.sorted()
        return sorted[2].takeIf { sorted.last() - sorted.first() <= .4 }
    }
}

internal data class GroundSpotMeasurement(val lens: String, val equivalentEv: Double, val workingAperture: Double)
