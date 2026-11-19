package studio.lightdata

import kotlin.math.*

/** Normalized gate bounds after projection, or sensor-pixel bounds before projection. */
data class FaceBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top

    fun inStream(crop: FaceBox, streamAspect: Double): FaceBox {
        val w = min(crop.width, crop.height * streamAspect)
        val h = w / streamAspect
        val x = (crop.left + crop.right - w) * .5
        val y = (crop.top + crop.bottom - h) * .5
        return FaceBox((left-x)/w,(top-y)/h,(right-x)/w,(bottom-y)/h)
    }

    fun inGate(turn: Int, gate: FloatArray): FaceBox? {
        val box = when (turn) {
            90 -> FaceBox(1-bottom,left,1-top,right)
            180 -> FaceBox(1-right,1-bottom,1-left,1-top)
            270 -> FaceBox(top,1-right,bottom,1-left)
            else -> this
        }
        val x = gate[0]; val y = gate[1]
        val sx = gate[2]; val sy = gate[3]
        val u = (box.left + box.right - 2*x)/(2*sx)
        val v = (box.top + box.bottom - 2*y)/(2*sy)
        if (u !in 0.0..1.0 || v !in 0.0..1.0) return null
        return FaceBox(((box.left-x)/sx).coerceIn(0.0,1.0),((box.top-y)/sy).coerceIn(0.0,1.0),
            ((box.right-x)/sx).coerceIn(0.0,1.0),((box.bottom-y)/sy).coerceIn(0.0,1.0))
            .takeIf { it.width > 0 && it.height > 0 }
    }

    /** Sample the inner ellipse to limit hair/background influence. At most 48×48 reads. */
    fun histogram(frame: Frame): IntArray? {
        val rx = width * .31
        val ry = height * .34
        val cx = (left+right)*.5
        val cy = (top+bottom)*.5
        val cols = (2*rx*frame.w).roundToInt().coerceIn(1,48)
        val rows = (2*ry*frame.h).roundToInt().coerceIn(1,48)
        val hist = IntArray(256)
        for (j in 0 until rows) {
            val dy = (j+.5)*2/rows - 1
            for (i in 0 until cols) {
                val dx = (i+.5)*2/cols - 1
                if (dx*dx+dy*dy > 1) continue
                val pixel = frame.sample(cx+rx*dx,cy+ry*dy)
                if (pixel ushr 24 != 0) hist[luma(pixel)]++
            }
        }
        return hist.takeIf { it.any { n -> n > 0 } }
    }
}
