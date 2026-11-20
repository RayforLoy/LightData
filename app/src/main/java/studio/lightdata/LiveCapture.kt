package studio.lightdata

import kotlin.math.*

/** The same Camera2 image and crop are used by the shader, full histogram and frozen spots. */
class LiveCapture(val image: Rig.Packet, val ev: Double, val id: String, val timestamp: Long,
                  val turn: Int, source: Field, target: Field, val right: Double = 0.0, val up: Double = 0.0) {
    internal var trackingPose: GatePose? = null
    private val uprightWidth = if (turn % 180 == 0) image.w else image.h
    private val uprightHeight = if (turn % 180 == 0) image.h else image.w
    private val gateWidth = uprightWidth * target.x / source.x
    private val gateHeight = uprightHeight * target.y / source.y
    private val scale = min(1.0, max(uprightWidth,uprightHeight) / max(gateWidth,gateHeight))
    val width = max(1,(gateWidth*scale).roundToInt())
    val height = max(1,(gateHeight*scale).roundToInt())
    private val covered = source.covers(target)
    private val offsetX = uprightWidth * right / (2 * source.x)
    private val offsetY = -uprightHeight * up / (2 * source.y)
    private val sx = if (covered) width.toDouble()/uprightWidth else gateWidth/uprightWidth
    private val sy = if (covered) height.toDouble()/uprightHeight else gateHeight/uprightHeight
    private val left = (if (covered) ((uprightWidth-width)/2).toDouble()/uprightWidth else (1-sx)/2) + offsetX/uprightWidth
    private val top = (if (covered) ((uprightHeight-height)/2).toDouble()/uprightHeight else (1-sy)/2) + offsetY/uprightHeight
    val gate = floatArrayOf(left.toFloat(),top.toFloat(),sx.toFloat(),sy.toFloat())
    val faces = image.faces?.mapNotNull { it.inGate(turn,gate) }?.sortedByDescending { it.width * it.height }
    private fun pixel(x: Int, y: Int): Int {
        if (x !in 0 until uprightWidth || y !in 0 until uprightHeight) return 0
        return when(turn) {
            90 -> image.pixel(y,image.h-1-x)
            180 -> image.pixel(image.w-1-x,image.h-1-y)
            270 -> image.pixel(image.w-1-y,x)
            else -> image.pixel(x,y)
        }
    }
    private fun xAt(u: Double) = if (covered) (uprightWidth-width)/2 + floor(u*width + offsetX).toInt()
        else floor((u-.5)*gateWidth + uprightWidth*.5 + offsetX).toInt()
    private fun yAt(v: Double) = if (covered) (uprightHeight-height)/2 + floor(v*height + offsetY).toInt()
        else floor((v-.5)*gateHeight + uprightHeight*.5 + offsetY).toInt()
    fun panoramaFrame(): Frame {
        val ratio = min(1.0,LONG_EDGE.toDouble()/max(uprightWidth,uprightHeight))
        val w = max(1,(uprightWidth*ratio).roundToInt())
        val h = max(1,(uprightHeight*ratio).roundToInt())
        val xs = IntArray(w) { ((it+.5)*uprightWidth/w).toInt() }
        val rgb = IntArray(w*h)
        for (row in 0 until h) {
            val y = ((row+.5)*uprightHeight/h).toInt()
            for (col in xs.indices) rgb[row*w+col] = pixel(xs[col],y)
        }
        return Frame(w,h,rgb,ev,id,timestamp,image.encoding)
    }
    fun trackingImage() = TrackingImage(uprightWidth,uprightHeight) { u,v ->
        pixel((u*uprightWidth).toInt(),(v*uprightHeight).toInt())
    }
    fun signature() = IntArray(96) { i ->
        luma(pixel(xAt((i%12+.5)/12),yAt((i/12+.5)/8)))
    }
    fun fullFrame(): Frame {
        val xs = IntArray(width) { xAt((it+.5)/width) }
        val colors = IntArray(width*height)
        for (row in 0 until height) {
            val y = yAt((row+.5)/height)
            for (col in xs.indices) colors[row*width+col] = pixel(xs[col],y)
        }
        return Frame(width,height,colors,ev,id,timestamp,image.encoding,image.sensorReading,faces,image.whitePoint)
    }
}
