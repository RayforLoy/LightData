package studio.lightdata

import kotlin.math.*

internal data class FlashFrame(val gray: DoubleArray, val curve: DoubleArray, val iso: Double,
                               val aperture: Double, val timeNs: Long)
internal enum class FlashIssue { WEAK, CLIPPED, UNSTABLE }
internal data class FlashReading(val aperture: Double?=null, val issue: FlashIssue?=null)

/** A pulse contributes integrated exposure. Scaling by shutter time applies only to ambient light. */
internal fun flashReading(before: FlashFrame, lit: FlashFrame, after: FlashFrame, filmIso: Double): FlashReading {
    if (before.gray.size!=lit.gray.size || after.gray.size!=lit.gray.size || lit.gray.isEmpty() ||
        filmIso<=0 || !filmIso.isFinite()) return FlashReading(issue=FlashIssue.UNSTABLE)
    fun normalized(frame: FlashFrame, i: Int): Double {
        val code=(frame.gray[i]*255).roundToInt().coerceIn(0,255)
        return 2.0.pow(frame.curve[code])*frame.aperture.pow(2)/frame.iso
    }
    if(lit.gray.count { it>=.975 }>.02*lit.gray.size ||
        before.gray.count { it>=.975 }>.02*lit.gray.size || after.gray.count { it>=.975 }>.02*lit.gray.size)
        return FlashReading(issue=FlashIssue.CLIPPED)
    var flash=0.0
    var drift=0.0
    var ambient=0.0
    for(i in lit.gray.indices) {
        val b=normalized(before,i)*lit.timeNs/before.timeNs
        val a=normalized(after,i)*lit.timeNs/after.timeNs
        val background=(a+b)/2
        ambient+=background
        drift+=abs(a-b)
        flash+=normalized(lit,i)-background
    }
    if (drift>max(ambient*.25,abs(flash)*.20)) return FlashReading(issue=FlashIssue.UNSTABLE)
    if (flash<=max(ambient*.06,drift*3) || lit.gray.average()<.035) return FlashReading(issue=FlashIssue.WEAK)
    val aperture=sqrt(filmIso*flash/lit.gray.size)
    return if(aperture.isFinite() && aperture>0) FlashReading(aperture) else FlashReading(issue=FlashIssue.UNSTABLE)
}

internal fun flashFitsAllRows(startNs: Long, skewNs: Long, timeNs: Long, pulseNs: Long): Boolean =
    skewNs>=0 && pulseNs>=startNs+skewNs+2_000_000L && pulseNs+7_000_000L<=startNs+timeNs
