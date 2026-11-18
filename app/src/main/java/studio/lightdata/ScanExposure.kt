package studio.lightdata

import kotlin.math.*

/** Panorama capture is a separate camera exposure from the target film exposure. */
internal data class ScanExposure(val timeNs: Long, val iso: Int, val boost: Int = 100)

internal const val SCAN_SHUTTER_NS = 10_000_000L

// Allow sensor line-time quantization near the 1/100 s target.
internal fun slowScanShutter(timeNs: Long) = timeNs > SCAN_SHUTTER_NS * 1.1

internal fun fasterScanExposure(
    current: ScanExposure, times: LongRange, sensitivities: IntRange, requestedBoost: Int,
): ScanExposure? {
    if (!slowScanShutter(current.timeNs) || current.iso <= 0 || current.boost <= 0 ||
        requestedBoost <= 0 || times.isEmpty() || times.first <= 0 ||
        sensitivities.isEmpty() || sensitivities.first <= 0) return null
    val product = current.timeNs.toDouble() * current.iso * current.boost / requestedBoost
    val targetTime = SCAN_SHUTTER_NS.coerceIn(times)
    val iso = (product / targetTime).roundToInt().coerceIn(sensitivities)
    val time = (product / iso).roundToLong().coerceIn(times)
    // At the ISO ceiling, retain the longer shutter needed to preserve exposure.
    if (time >= current.timeNs || abs(log2(time * iso.toDouble() / product)) > .1) return null
    return ScanExposure(time,iso,requestedBoost)
}
