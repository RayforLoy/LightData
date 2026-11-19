package studio.lightdata

import kotlin.math.abs

/** Taking lens relative to the phone lens. Natural display axes: right, up, toward subject. */
data class ParallaxConfig(
    val enabled: Boolean = false,
    val xMm: Double = 0.0,
    val yMm: Double = 0.0,
    val zMm: Double = 0.0,
    val distanceMm: Double = 1000.0,
) {
    fun valid(focal: Double): Boolean {
        if (listOf(xMm, yMm, zMm).any { it !in -2000.0..2000.0 }) return false
        if (distanceMm == Double.POSITIVE_INFINITY) return true
        return distanceMm in 1.0..1e9 && (!enabled ||
            (distanceMm - focal >= 1.0 && distanceMm - focal + zMm >= 1.0))
    }

    // Intersect the taking-lens corner rays with the selected subject plane and
    // project its four corners through the phone lens. Assume parallel optical axes
    // and nominal focal length as the taking lens to focal plane separation.
    fun project(field: Field, focal: Double, displayRotation: Int): FinderGate {
        if (!enabled || distanceMm == Double.POSITIVE_INFINITY || !valid(focal)) return FinderGate(field)
        val takingDepth = distanceMm - focal
        val finderDepth = takingDepth + zMm
        val scale = takingDepth / finderDepth
        val (right, up) = when (Math.floorMod(displayRotation, 4)) {
            1 -> -yMm to xMm
            2 -> -xMm to -yMm
            3 -> yMm to -xMm
            else -> xMm to yMm
        }
        return FinderGate(Field(field.x * scale, field.y * scale), right / finderDepth, up / finderDepth)
    }
}

data class FinderGate(val field: Field, val right: Double = 0.0, val up: Double = 0.0) {
    fun coveredBy(source: Field) = source.x >= field.x + abs(right) && source.y >= field.y + abs(up)
}
