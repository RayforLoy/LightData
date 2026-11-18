package studio.lightdata

import kotlin.math.*

internal data class LevelReading(val upX: Double, val upY: Double, val upZ: Double) {
    // The rear camera looks down device -Z. Positive pitch points toward the sky.
    val pitchRadians get() = atan2(-upZ, hypot(upX, upY))
    val rollRadians get() = atan2(upX, upY)
    val overhead get() = abs(pitchRadians) >= Math.toRadians(65.0)
    val bubbleX get() = upX
    val bubbleY get() = -upY
}

/** Rotation matrix columns are screen-right, screen-up and screen-out in world coordinates. */
internal fun levelReading(axes: FloatArray): LevelReading =
    LevelReading(axes[6].toDouble(), axes[7].toDouble(), axes[8].toDouble())
