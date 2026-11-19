package studio.lightdata

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/** Physical bellows draw is measured from the lens principal plane to the film. */
internal fun opticalLossStops(filterStops: Double, drawMm: Double, focalMm: Double): Double =
    filterStops + if (drawMm > focalMm && focalMm > 0) 2 * log2(drawMm / focalMm) else 0.0

internal fun measuredTransmissionLoss(subjectEv: Double, screenEv: Double): Double? =
    (subjectEv - screenEv).takeIf { it.isFinite() && it >= 0 }

internal fun zoneMiddleEv(spotEv: Double, zone: Int) = spotEv - (zone - 5)

/** Dialed camera exposure, before film-specific reciprocity correction. */
internal fun shootingExposure(mode: String, manual: Exposure, middleEv: Double, loss: Double,
                              compensationEv: Double, placing: Boolean = false): Exposure {
    // Explicit placement chooses the shutter in M, retaining the selected aperture.
    val solveMode = if (placing && mode == "M") "A" else mode
    val target = middleEv - loss - if (placing) 0.0 else compensationEv
    val dialed = solve(solveMode, manual, target)
    if (solveMode == "A" && (loss > 0 || placing)) {
        val t = manual.a * manual.a * 100 / manual.iso / 2.0.pow(target)
        if (t.isFinite() && t > 30 && abs(log2(t / dialed.t)) > .18) return manual.copy(t = t)
    }
    return dialed
}
