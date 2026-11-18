package studio.lightdata

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.ArrayDeque
import kotlin.math.*

class Pose(ctx: Context) : SensorEventListener {
    private val sensors = ctx.getSystemService(SensorManager::class.java)
    private val sensor =
        sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravitySensor = if (sensor == null)
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        else null
    @Volatile private var fallbackUp: FloatArray? = null

    private data class Reading(val timestamp: Long, val rotation: FloatArray)

    private val history = ArrayDeque<Reading>()
    val available
        get() = sensor != null

    @Volatile
    var matrix: FloatArray? = null
        private set

    fun start() {
        sensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gravitySensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensors.unregisterListener(this)
        fallbackUp = null
        synchronized(history) {
            history.clear()
            matrix = null
        }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor == gravitySensor) {
            val prior = fallbackUp
            val alpha = if (prior != null && e.sensor.type == Sensor.TYPE_ACCELEROMETER) .15f else 1f
            val (x,y,z) = e.values
            val norm = sqrt(x*x+y*y+z*z)
            if (norm !in 6f..14f) return
            val up = FloatArray(3) { i -> (prior?.get(i) ?: 0f) * (1-alpha) + alpha * e.values[i]/norm }
            fallbackUp = up
            return
        }
        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, e.values)
        synchronized(history) {
            matrix = rotation
            history.addLast(Reading(e.timestamp, rotation))
            while (history.size > 80) history.removeFirst()
        }
    }

    // Only pass image timestamps from cameras reporting SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME.
    fun axes(rotation: Int, timestampNs: Long = 0L): FloatArray? {
        val device =
            if (timestampNs == 0L) matrix
            else
                synchronized(history) {
                    history.minByOrNull { abs(it.timestamp - timestampNs) }?.rotation
                }
        return device?.let { displayAxes(it, rotation) }
    }

    internal fun level(rotation: Int): LevelReading? {
        axes(rotation)?.let { return levelReading(it) }
        val up = fallbackUp ?: return null
        val norm = sqrt(up.sumOf { (it*it).toDouble() }).toFloat()
        if (norm < .25f) return null
        val basis = FloatArray(9)
        for (i in up.indices) basis[6+i] = up[i]/norm
        return levelReading(displayAxes(basis, rotation))
    }
}

// Camera basis uses +X right, +Y up, -Z through the back of the device.
// These column permutations are equivalent to SensorManager.remapCoordinateSystem.
internal fun displayAxes(device: FloatArray, rotation: Int): FloatArray {
    val output = FloatArray(9)
    for (row in 0..2) {
        val i = row * 3
        val x = device[i]
        val y = device[i + 1]
        output[i] =
            when (rotation) {
                1 -> -y
                2 -> -x
                3 -> y
                else -> x
            }
        output[i + 1] =
            when (rotation) {
                1 -> x
                2 -> -y
                3 -> -x
                else -> y
            }
        output[i + 2] = device[i + 2]
    }
    return output
}

fun upright(f: Frame, turn: Int): Frame {
    val angle = Math.floorMod(turn, 360)
    if (angle == 0) return f
    require(angle == 90 || angle == 180 || angle == 270)
    val width = if (angle == 180) f.w else f.h
    val height = if (angle == 180) f.h else f.w
    val buffer = IntArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        val index =
            when (angle) {
                90 -> (f.h - 1 - x) * f.w + y
                180 -> (f.h - 1 - y) * f.w + f.w - 1 - x
                else -> x * f.w + f.w - 1 - y
            }
        buffer[y * width + x] = f.pixels[index]
    }
    return f.copy(w = width, h = height, pixels = buffer)
}

fun crop(f: Frame, source: Field, target: Field): Frame {
    val pixelsWide = f.w * target.x / source.x
    val pixelsHigh = f.h * target.y / source.y
    // A wider-than-available lens shows unobserved areas as transparent. Bound allocations
    // even for a large custom format coupled to a very short focal length.
    val scale = min(1.0, max(f.w, f.h) / max(pixelsWide, pixelsHigh))
    val width = max(1, (pixelsWide * scale).roundToInt())
    val height = max(1, (pixelsHigh * scale).roundToInt())
    if (width == f.w && height == f.h && source == target) return f
    val buffer = IntArray(width * height)
    if (source.covers(target)) {
        val left = (f.w - width) / 2
        val top = (f.h - height) / 2
        for (y in 0 until height) {
            val start = (top + y) * f.w + left
            f.pixels.copyInto(buffer, y * width, start, start + width)
        }
    } else {
        val xs = IntArray(width) { floor(((it + .5) / width - .5) * pixelsWide + f.w * .5).toInt() }
        for (y in 0 until height) {
            val sy = floor(((y + .5) / height - .5) * pixelsHigh + f.h * .5).toInt()
            if (sy !in 0 until f.h) continue
            for (x in xs.indices) if (xs[x] in 0 until f.w)
                buffer[y * width + x] = f.pixels[sy * f.w + xs[x]]
        }
    }
    return f.copy(w = width, h = height, pixels = buffer)
}

// R_to transposed times R_from transforms a ray expressed in the "from" camera into "to".
private fun relative(to: FloatArray, from: FloatArray) =
    DoubleArray(9) { i -> (0..2).sumOf { k -> to[k * 3 + i / 3].toDouble() * from[k * 3 + i % 3] } }

private const val MARGIN = 1.15
internal const val LONG_EDGE = 720
internal const val GRID_WIDTH = 9
internal const val GRID_HEIGHT = 7

data class FinderPoint(val x: Double, val y: Double) {
    fun turned(turn: Int): FinderPoint =
        when (Math.floorMod(turn, 360)) {
            90 -> FinderPoint(1 - y, x)
            180 -> FinderPoint(1 - x, 1 - y)
            270 -> FinderPoint(y, 1 - x)
            else -> this
        }
}

data class StartMark(val center: FinderPoint, val right: FinderPoint)

data class ScanGuide(
    val captures: Int,
    val coverage: Double,
    val aim: FinderPoint,
    val next: FinderPoint?,
    val needsOverlap: Boolean,
    val tiles: DoubleArray,
    val mapTurn: Int,
    val homeDirection: FinderPoint?,
    val settling: Boolean,
    val startMark: StartMark? = null,
    val startRollRadians: Double? = null,
    val startPitchRadians: Double? = null,
    val startOverhead: Boolean = false,
    val lowLight: Boolean = false,
)

internal data class ScanWarp(val a: Double = 1.0, val b: Double = 0.0,
                            val tx: Double = 0.0, val ty: Double = 0.0,
                            val width: Int = 1, val height: Int = 1,
                            val offsetsX: DoubleArray? = null, val offsetsY: DoubleArray? = null) {
    private fun local(values: DoubleArray?, u: Double, v: Double): Double {
        if (values == null) return 0.0
        val x = (u*3/width).coerceIn(0.0,3.0)
        val y = (v*3/height).coerceIn(0.0,3.0)
        val col = min(2,x.toInt())
        val row = min(2,y.toInt())
        val i = row*4+col
        val fx = x-col
        val fy = y-row
        return (values[i]*(1-fx)+values[i+1]*fx)*(1-fy) +
            (values[i+4]*(1-fx)+values[i+5]*fx)*fy
    }
    fun x(u: Double,v: Double) = a*u-b*v+tx+width*local(offsetsX,u,v)
    fun y(u: Double,v: Double) = b*u+a*v+ty+height*local(offsetsY,u,v)
}

internal fun interface ScanAligner {
    val rawReferences: Boolean get() = false
    fun align(w: Int, h: Int, reference: ByteArray, incoming: ByteArray, mask: ByteArray): ScanWarp?
    fun candidates(w: Int, h: Int, reference: ByteArray, incoming: ByteArray, mask: ByteArray): Sequence<ScanWarp> =
        sequence { align(w,h,reference,incoming,mask)?.let { yield(it) } }
}

internal class Mosaic(
    val target: Field,
    private val center: FloatArray,
    private val initial: Frame,
    private val response: DoubleArray = defaultResponse,
    private val aligner: ScanAligner? = null,
) {
    private val references = if (aligner?.rawReferences==true) ScanReferences() else null
    fun close() { references?.close() }
    private val bounds = Field(target.x * MARGIN, target.y * MARGIN)
    val w = max(1, (LONG_EDGE * min(1.0, bounds.aspect)).roundToInt())
    val h = max(1, (LONG_EDGE * min(1.0, 1 / bounds.aspect)).roundToInt())
    private val atlas = IntArray(w * h)
    private val confidence = FloatArray(w * h)
    private val tileCount = IntArray(GRID_WIDTH * GRID_HEIGHT)
    private val tileSize = IntArray(GRID_WIDTH * GRID_HEIGHT)
    private val xs = DoubleArray(w) { (2 * (it + .5) / w - 1) * bounds.x }
    private val ys = DoubleArray(h) { (1 - 2 * (it + .5) / h) * bounds.y }
    private var count = 0
    val coverage
        get() = count.toDouble() / atlas.size

    val ready
        get() = count == atlas.size

    init {
        for (y in 0 until h) for (x in 0 until w) tileSize[tile(x, y)]++
    }

    private fun tile(x: Int, y: Int) = y * GRID_HEIGHT / h * GRID_WIDTH + x * GRID_WIDTH / w

    fun grid() = BooleanArray(tileSize.size) { tileCount[it] == tileSize[it] }

    fun tileCoverage() = DoubleArray(tileSize.size) {
        if (tileSize[it] == 0) 1.0 else tileCount[it].toDouble()/tileSize[it]
    }

    fun aim(pose: FloatArray): FinderPoint {
        val r = relative(center, pose)
        val z = max(.001, r[8])
        return FinderPoint(.5 - .5 * r[2] / (bounds.x * z), .5 + .5 * r[5] / (bounds.y * z))
    }

    fun nextUncovered(aim: FinderPoint, field: Field): FinderPoint? {
        if (ready) return null
        // Score each candidate footprint by new area, keeping enough overlap for
        // registration. Fractional tile counts keep narrow unfilled borders selectable.
        var bestScore = Double.NEGATIVE_INFINITY
        var selected = -1
        val coverage = tileCoverage()
        for (i in tileSize.indices) {
            if (tileSize[i] == tileCount[i]) continue
            val x = (i % GRID_WIDTH + .5) / GRID_WIDTH
            val y = (i / GRID_WIDTH + .5) / GRID_HEIGHT
            val yaw = atan((2*x-1)*bounds.x)
            val pitch = atan((2*y-1)*bounds.y*cos(yaw))
            var empty = 0.0
            var occupied = 0.0
            for (j in coverage.indices) {
                val dx = (2*(j%GRID_WIDTH+.5)/GRID_WIDTH-1)*bounds.x
                val dy = (2*(j/GRID_WIDTH+.5)/GRID_HEIGHT-1)*bounds.y
                val forward = cos(yaw)+dx*sin(yaw)
                val depth = forward*cos(pitch)+dy*sin(pitch)
                if (depth <= 0 || abs(dx*cos(yaw)-sin(yaw)) > field.x*depth ||
                    abs(dy*cos(pitch)-forward*sin(pitch)) > field.y*depth) continue
                empty += 1-coverage[j]
                occupied += coverage[j]
            }
            val fraction = occupied/max(.001,occupied+empty)
            val score = empty*min(1.0,fraction/.23) -
                .1*hypot((x-aim.x)*bounds.x,(y-aim.y)*bounds.y)
            if (score > bestScore) { bestScore = score; selected = i }
        }
        if (selected < 0) return null
        val col = selected % GRID_WIDTH
        val row = selected / GRID_WIDTH
        fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
        val left = ceilDiv(w * col, GRID_WIDTH)
        val right = ceilDiv(w * (col + 1), GRID_WIDTH)
        val top = ceilDiv(h * row, GRID_HEIGHT)
        val bottom = ceilDiv(h * (row + 1), GRID_HEIGHT)
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        var best = Int.MAX_VALUE
        var point: FinderPoint? = null
        for (y in top until bottom) for (x in left until right) {
            if (atlas[y * w + x] != 0) continue
            val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (d < best) {
                best = d
                point = FinderPoint((x + .5) / w, (y + .5) / h)
            }
        }
        return point
    }

    // Incoming code values are brought into the center frame's exposure. The inverse
    // response is calibrated on luminance, so apply its gain to RGB without altering hue.
    private fun gains(delta: Double): DoubleArray {
        if (abs(delta) < .002) return DoubleArray(256) { 1.0 }
        if (response === defaultResponse) {
            val gain = 2.0.pow(delta)
            return DoubleArray(256) { gain }
        }
        return DoubleArray(256) { y ->
            val desired = response[y] + delta
            var l = 0
            var r = 255
            while (l < r) {
                val m = (l + r) / 2
                if (response[m] < desired) l = m + 1 else r = m
            }
            val below = max(0, l - 1)
            val span = response[l] - response[below]
            val fraction = if (span > 0.0) ((desired - response[below]) / span).coerceIn(0.0, 1.0) else 0.0
            val targetCode = below + fraction
            srgbToLinear(targetCode / 255.0) / srgbToLinear(max(.5, y.toDouble()) / 255.0)
        }
    }

    private fun color(c: Int, gain: Double): Int {
        if (gain == 1.0) return c
        val r = scaleSrgbCode(c ushr 16 and 255, gain)
        val g = scaleSrgbCode(c ushr 8 and 255, gain)
        val b = scaleSrgbCode(c and 255, gain)
        return (c and -0x1000000) or (r shl 16) or (g shl 8) or b
    }

    private class Projection(val r: DoubleArray, source: Field, val width: Int, val height: Int) {
        val fx = width / (2 * source.x)
        val fy = height / (2 * source.y)

        fun depth(x: Double, y: Double) = r[8] - r[6] * x - r[7] * y

        fun u(x: Double, y: Double, depth: Double) =
            width * .5 + (r[0] * x + r[1] * y - r[2]) * fx / depth

        fun v(x: Double, y: Double, depth: Double) =
            height * .5 - (r[3] * x + r[4] * y - r[5]) * fy / depth
    }

    // Refine residual image translation in the overlap. Reject ambiguous texture and
    // small improvements; an inertial-only placement is preferable on a blank wall.
    private fun register(f: Frame, p: Projection, gain: DoubleArray): Pair<Int, Int> {
        if (count == 0) return 0 to 0
        val radius = (min(f.w, f.h) / 25).coerceIn(4, 20)
        val locations = ArrayList<Int>()
        val expected = ArrayList<Int>()
        val step = max(4, min(w, h) / 28)
        for (y in step / 2 until h - 2 step step) for (x in step / 2 until w - 2 step step) {
            val i = y * w + x
            val c = atlas[i]
            if (c == 0 || atlas[i + 2] == 0 || atlas[i + 2 * w] == 0) continue
            val value = luma(c)
            if (max(abs(value - luma(atlas[i + 2])), abs(value - luma(atlas[i + 2 * w]))) < 5)
                continue
            val z = p.depth(xs[x], ys[y])
            if (z < .001) continue
            val u = floor(p.u(xs[x], ys[y], z)).toInt()
            val v = floor(p.v(xs[x], ys[y], z)).toInt()
            if (u !in radius until f.w - radius || v !in radius until f.h - radius) continue
            locations.add(v * f.w + u)
            expected.add(value)
        }
        if (locations.size < 12) return 0 to 0
        val normalized = IntArray(256) { scaleSrgbCode(it, gain[it]) }
        fun error(dx: Int, dy: Int): Double {
            val offset = dy * f.w + dx
            var loss = 0
            for (i in locations.indices) {
                val observed = normalized[luma(f.pixels[locations[i] + offset])]
                loss += min(36, abs(observed - expected[i]))
            }
            return loss.toDouble() / locations.size + .05 * (abs(dx) + abs(dy))
        }
        val baseline = error(0, 0)
        var best = baseline
        var dx = 0
        var dy = 0
        fun evaluate(x: Int, y: Int) {
            val score = error(x, y)
            if (score < best) {
                best = score
                dx = x
                dy = y
            }
        }
        for (y in -radius..radius step 3) for (x in -radius..radius step 3) evaluate(x, y)
        val bx = dx
        val by = dy
        for (y in max(-radius, by - 2)..min(radius, by + 2)) for (x in
            max(-radius, bx - 2)..min(radius, bx + 2)) evaluate(x, y)
        return if (best < baseline * .83 && best + 1 < baseline) dx to dy else 0 to 0
    }

    // Warp the existing atlas into the incoming camera coordinates first. Native
    // optical flow now only needs to solve the residual left by the rotation sensor.
    private fun alignOverlap(frame: Frame, source: Field, rotation: DoubleArray): Sequence<ScanWarp> {
        val native = aligner ?: return emptySequence()
        val scale = min(1.0,256.0/max(frame.w,frame.h))
        val width = max(1,(frame.w*scale).roundToInt())
        val height = max(1,(frame.h*scale).roundToInt())
        val inverse = DoubleArray(9) { rotation[(it%3)*3+it/3] }
        val p = Projection(inverse,bounds,w,h)
        val reference = ByteArray(width*height)
        val current = ByteArray(reference.size)
        val mask = ByteArray(reference.size)
        for (y in 0 until height) {
            val dy = (1-2*(y+.5)/height)*source.y
            for (x in 0 until width) {
                val dx = (2*(x+.5)/width-1)*source.x
                val i = y*width+x
                current[i] = luma(frame.sample((x+.5)/width,(y+.5)/height)).toByte()
                val z = p.depth(dx,dy)
                if (z <= .001) continue
                val u = p.u(dx,dy,z)
                val v = p.v(dx,dy,z)
                if (u < 0 || u >= w || v < 0 || v >= h) continue
                val rgb = atlas[v.toInt()*w+u.toInt()]
                if (rgb == 0) continue
                reference[i] = luma(rgb).toByte()
                mask[i] = -1
            }
        }
        return native.candidates(width,height,reference,current,mask).map { fit ->
            fit.copy(tx=fit.tx*frame.w/width,ty=fit.ty*frame.h/height,width=frame.w,height=frame.h)
        }
    }

    // Test the final warp before mutating the atlas. Compare local structure rather
    // than intensity so device exposure and white-balance changes do not veto a match.
    private fun overlapAgrees(frame: Frame, p: Projection, warp: ScanWarp): Boolean {
        val stride = max(7,min(w,h)/24)
        var good = 0
        var bad = 0
        for (y in 4 until h-4 step stride) for (x in 4 until w-4 step stride) {
            var n = 0
            var a = 0.0; var b = 0.0
            var aa = 0.0; var bb = 0.0; var ab = 0.0
            for (dy in -4..4 step 2) for (dx in -4..4 step 2) {
                val row = y+dy; val col = x+dx
                val old = atlas[row*w+col]
                if (old == 0) continue
                val z = p.depth(xs[col],ys[row])
                if (z <= .001) continue
                val px = p.u(xs[col],ys[row],z); val py = p.v(xs[col],ys[row],z)
                val u = warp.x(px,py); val v = warp.y(px,py)
                if (u < 0 || v < 0 || u >= frame.w || v >= frame.h) continue
                val raw = frame.pixels[v.toInt()*frame.w+u.toInt()]
                if (raw ushr 24 == 0) continue
                val l = luma(old).toDouble(); val r = luma(raw).toDouble()
                a+=l; b+=r; aa+=l*l; bb+=r*r; ab+=l*r; n++
            }
            if (n < 16) continue
            val va = aa-a*a/n; val vb = bb-b*b/n
            if (max(va,vb) < 36*n) continue
            if (ab-a*b/n > .55*sqrt(max(0.0,va*vb))) good++ else bad++
        }
        lastOverlapChecks.add("good=$good bad=$bad a=${warp.a} b=${warp.b} tx=${warp.tx} ty=${warp.ty}")
        return bad < 4 || good >= 2*bad
    }

    // Estimate residual camera processing after normalizing the measured exposure.
    // Only matched, unclipped overlap contributes; the median rejects local scene motion.
    private fun overlapGains(frame: Frame, p: Projection, warp: ScanWarp, gains: DoubleArray): Array<DoubleArray> {
        val result = DoubleArray(3) { 1.0 }
        if (count == 0) return Array(4) { result }
        val ratios = Array(3) { ArrayList<Double>() }
        val blocks = Array(4) { Array(3) { ArrayList<Double>() } }
        val step = max(1,min(w,h)/48)
        for (y in step/2 until h step step) for (x in step/2 until w step step) {
            val old = atlas[y*w+x]
            if (old == 0) continue
            val z = p.depth(xs[x],ys[y])
            if (z <= .001) continue
            val px = p.u(xs[x],ys[y],z)
            val py = p.v(xs[x],ys[y],z)
            val u = warp.x(px,py)
            val v = warp.y(px,py)
            if (u < 0 || v < 0 || u >= frame.w || v >= frame.h) continue
            val raw = frame.pixels[v.toInt()*frame.w+u.toInt()]
            if (raw ushr 24 == 0) continue
            val normalized = color(raw,gains[luma(raw)])
            for (c in 0..2) {
                val shift = 16-8*c
                val a = (old ushr shift) and 255
                val b = (normalized ushr shift) and 255
                if (a in 18..235 && b in 18..235) {
                    val ratio = srgbToLinear(a/255.0)/srgbToLinear(b/255.0)
                    ratios[c].add(ratio)
                    blocks[(if (u >= frame.w*.5) 1 else 0)+(if (v >= frame.h*.5) 2 else 0)][c].add(ratio)
                }
            }
        }
        for (c in 0..2) {
            val list = ratios[c]
            if (list.size < 12) continue
            list.sort()
            if (list[3*list.size/4] < 1.5*list[list.size/4])
                result[c] = list[list.size/2].coerceIn(.5,2.0)
        }
        return Array(4) { tile -> DoubleArray(3) { c ->
            val local = blocks[tile][c]
            if (local.size < 16) result[c] else {
                local.sort()
                if (local[3*local.size/4] > local[local.size/4]*1.25) result[c]
                else local[local.size/2].coerceIn(result[c]*.78,result[c]*1.28)
            }
        } }
    }

    var lastDecision: String = "not_attempted"
        private set
    val lastOverlapChecks = mutableListOf<String>()

    fun add(frame: Frame, source: Field, pose: FloatArray) = add(source,pose) { frame }

    fun add(source: Field, pose: FloatArray, requireVisual: Boolean = false, decode: () -> Frame): Boolean {
        lastDecision = "accepted"
        lastOverlapChecks.clear()
        if (ready) { lastDecision = "ready"; return false }
        val rotation = relative(pose,center)
        // Reject disconnected islands. A sample grid over the atlas checks geometric
        // overlap before feature matching, including in low-texture scenes.
        if (count > 0) {
            val p = Projection(rotation,source,1,1)
            var overlap = false
            var addsArea = false
            for (i in tileSize.indices) {
                if (tileCount[i] == tileSize[i]) continue
                // The camera's view is convex on the atlas. Reject a tile only if
                // all four corners are outside the same frustum plane.
                val col = i % GRID_WIDTH
                val row = i / GRID_WIDTH
                var outside = 31
                for (cx in col..col+1) for (cy in row..row+1) {
                    val dx = (2.0*cx/GRID_WIDTH-1)*bounds.x
                    val dy = (1-2.0*cy/GRID_HEIGHT)*bounds.y
                    val z = p.depth(dx,dy)
                    val px = p.r[0]*dx+p.r[1]*dy-p.r[2]
                    val py = p.r[3]*dx+p.r[4]*dy-p.r[5]
                    var mask = if (z <= 0) 1 else 0
                    if (px < -source.x*z) mask = mask or 2
                    if (px > source.x*z) mask = mask or 4
                    if (py < -source.y*z) mask = mask or 8
                    if (py > source.y*z) mask = mask or 16
                    outside = outside and mask
                }
                if (outside == 0) { addsArea = true; break }
            }
            if (!addsArea) { lastDecision = "no_new_area"; return true }
            val stride = max(1,min(w,h)/64)
            outer@ for (y in 0 until h step stride) for (x in 0 until w step stride) {
                if (atlas[y*w+x] == 0) continue
                val z = p.depth(xs[x],ys[y])
                if (z <= .001) continue
                val u = p.u(xs[x],ys[y],z)
                val v = p.v(xs[x],ys[y],z)
                if (u >= .03 && u < .97 && v >= .03 && v < .97) {
                    overlap = true
                    break@outer
                }
            }
            if (!overlap) { lastDecision = "no_overlap"; return false }
        }
        val frame = decode()
        if (frame.id != initial.id) { lastDecision = "camera_changed"; return false }
        val rawPlacement = references?.estimate(frame,source,rotation)
        references?.validation?.let(lastOverlapChecks::add)
        val placement = rawPlacement ?: rotation
        val p = Projection(placement,source,frame.w,frame.h)
        val gain = gains(frame.cameraEv - initial.cameraEv)
        var hadVisualFit = false
        val nativeFit = if (rawPlacement!=null) ScanWarp() else if (count > 0) alignOverlap(frame,source,rotation).firstOrNull {
            hadVisualFit = true
            overlapAgrees(frame,p,it)
        } else null
        if (count > 0 && requireVisual && nativeFit == null) {
            lastDecision = if (hadVisualFit) "overlap_disagrees" else "visual_fit_missing"
            return false
        }
        val warp = nativeFit ?: register(frame,p,gain).let { (x,y) -> ScanWarp(tx=x.toDouble(),ty=y.toDouble()) }
        if (count > 0 && nativeFit == null && !overlapAgrees(frame,p,warp)) { lastDecision = "overlap_disagrees"; return false }
        if (count==0 || rawPlacement!=null) references?.retain(placement)
        val residual = overlapGains(frame,p,warp,gain)
        val feather = max(2.0,min(frame.w,frame.h)*.14)
        for (y in ys.indices) for (x in xs.indices) {
            val j = y * w + x
            if (confidence[j] >= 1f) continue
            val z = p.depth(xs[x], ys[y])
            if (z <= .001) continue
            val px = p.u(xs[x],ys[y],z)
            val py = p.v(xs[x],ys[y],z)
            val u = warp.x(px,py)
            val v = warp.y(px,py)
            if (u < 0 || v < 0 || u >= frame.w || v >= frame.h) continue
            val c = frame.pixels[v.toInt() * frame.w + u.toInt()]
            if (c ushr 24 == 0) continue
            val weight = (min(min(u+.5,frame.w-u),min(v+.5,frame.h-v))/feather).coerceIn(.001,1.0).toFloat()
            if (atlas[j] != 0 && confidence[j] >= weight) continue
            val normalized = color(c,gain[luma(c)])
            val fx = (2*u/frame.w-.5).coerceIn(0.0,1.0)
            val fy = (2*v/frame.h-.5).coerceIn(0.0,1.0)
            var mix = (weight-confidence[j]).toDouble()
            if (atlas[j] != 0 && x > 0 && y > 0) {
                val left = atlas[j-1]
                val above = atlas[j-w]
                val old = luma(atlas[j])
                val detail = (left != 0 && abs(old-luma(left)) > 12) ||
                    (above != 0 && abs(old-luma(above)) > 12)
                val src = v.toInt()*frame.w+u.toInt()
                val edgeInSource = (u >= 1 && abs(luma(c)-luma(frame.pixels[src-1])) > 12) ||
                    (v >= 1 && abs(luma(c)-luma(frame.pixels[src-frame.w])) > 12)
                if (detail && edgeInSource && abs(old-luma(normalized)) > 20)
                    mix = if (mix > .5) 1.0 else 0.0
            }
            var pixel = -0x1000000
            for (channel in 0..2) {
                val shift = 16-channel*8
                val top = residual[0][channel]*(1-fx)+residual[1][channel]*fx
                val bottom = residual[2][channel]*(1-fx)+residual[3][channel]*fx
                val incoming = scaleSrgbCode((normalized ushr shift) and 255,top*(1-fy)+bottom*fy)
                val mixed = if (atlas[j] == 0) incoming else
                    blendSrgbCode((atlas[j] ushr shift) and 255,incoming,mix)
                pixel = pixel or (mixed shl shift)
            }
            if (atlas[j] == 0) {
                count++
                tileCount[tile(x,y)]++
            }
            atlas[j] = pixel
            confidence[j] = weight
        }
        return true
    }

    // Project the initial camera ray into the live crop. Its tangent along the
    // initial camera X axis preserves the framing's roll, even in a top-down scan.
    fun startMark(pose: FloatArray, turn: Int): StartMark? {
        val r = relative(pose, center)
        val z = r[8]
        if (z <= .001) return null
        val p = FinderPoint(.5 - .5*r[2]/(z*target.x), .5 + .5*r[5]/(z*target.y))
        if (p.x !in 0.0..1.0 || p.y !in 0.0..1.0) return null
        val dx = (r[0]*z - r[2]*r[6])/target.x
        val dy = -(r[3]*z - r[5]*r[6])/target.y
        val right = FinderPoint(p.x + dx,p.y + dy).turned(turn)
        val projected = p.turned(turn)
        return StartMark(projected, FinderPoint(right.x - projected.x,right.y - projected.y))
    }

    fun homeDirection(pose: FloatArray): FinderPoint? {
        val r = relative(pose, center)
        val aim = aim(pose)
        val x = floor(aim.x*w).toInt()
        val y = floor(aim.y*h).toInt()
        if (r[8] > 0 && x in 0 until w && y in 0 until h && atlas[y*w+x] != 0) return null
        // The center ray in the current camera's right/down coordinates. At the
        // antipode either turn is valid. At the starting center no arrow is needed.
        val dx = -r[2]
        val dy = r[5]
        val length = hypot(dx,dy)
        if (length < 1e-6) return if (r[8] < 0) FinderPoint(1.0,0.0) else null
        return FinderPoint(dx/length,dy/length)
    }

    fun view(pose: FloatArray): Frame {
        val width = max(1, (w / MARGIN).roundToInt())
        val height = max(1, (h / MARGIN).roundToInt())
        val r = relative(center,pose)
        val buffer = IntArray(width * height)
        // The homography numerators and depth advance linearly across a scanline.
        val step = 2*target.x/width
        val left = (.5*step-target.x)
        val fx = w/(2*bounds.x)
        val fy = h/(2*bounds.y)
        val stepU = r[0]*step*fx
        val stepV = -r[3]*step*fy
        val stepZ = -r[6]*step
        for (y in 0 until height) {
            val dy = (1 - 2*(y+.5)/height)*target.y
            var z = r[8]-r[6]*left-r[7]*dy
            var u = (r[0]*left+r[1]*dy-r[2])*fx
            var v = -(r[3]*left+r[4]*dy-r[5])*fy
            for (x in 0 until width) {
                if (z > 0) {
                    val inverse = 1/z
                    val ax = w*.5+u*inverse
                    val ay = h*.5+v*inverse
                    if (ax >= 0 && ax < w && ay >= 0 && ay < h)
                        buffer[y*width+x] = atlas[ay.toInt()*w+ax.toInt()]
                }
                z += stepZ
                u += stepU
                v += stepV
            }
        }
        return initial.copy(w = width, h = height, pixels = buffer)
    }
}

// A scan keeps the display axes that were in use when the center was confirmed.
// Its atlas and angular reference remain valid if Android rotates the window.
internal class ScanSession(center: Frame, target: Field, origin: FloatArray?, response: DoubleArray,
                           private val aligner: ScanAligner? = null,
                           private val startLevel: LevelReading? = origin?.let(::levelReading)) {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val mosaic = Mosaic(target, identity, center, response, aligner)
    fun close() = mosaic.close()
    private var orientation = identity
    private var sensorOrigin = origin
    private var originPose = identity
    private var reference: TrackingImage? = null
    private var nextTarget: FinderPoint? = FinderPoint(.5,.5)
    private var accepted = 0
    private var lost = false
    private var anchor: FloatArray? = null
    private var since = 0L
    private var lastTime = -1L
    private var attemptedAt = Long.MIN_VALUE
    private var retryStationary = false
    private var disconnected = false
    private var lastAttempt: FloatArray? = null
    private var settling = true
    private var renderedPose: FloatArray? = null
    private var rendered: Frame? = null
    val lastCandidateDecision get() = mosaic.lastDecision
    val lastCandidateChecks get() = mosaic.lastOverlapChecks.toList()

    private fun moved(a: FloatArray, b: FloatArray, degrees: Double): Boolean {
        var trace = 0.0
        for (i in a.indices) trace += a[i].toDouble()*b[i]
        return trace < 1 + 2*cos(Math.toRadians(degrees))
    }

    fun resumeTracking() {
        sensorOrigin = null
        reference = null
        anchor = null
        lastTime = -1L
    }

    fun capture(field: Field, sensor: FloatArray?, nowMs: Long, tracking: (() -> TrackingImage)? = null, exposureMs: Double = 0.0, frame: () -> Frame): Frame {
        var decoded: Frame? = null
        fun image() = decoded ?: frame().also { decoded = it }
        val previousOrientation = orientation
        lost = false
        if (sensor != null) {
            if (sensorOrigin == null) {
                originPose = orientation
                sensorOrigin = sensor
            }
            val delta = relative(sensorOrigin!!, sensor)
            orientation = FloatArray(9) { i ->
                (0..2).sumOf { k -> originPose[i/3*3+k]*delta[k*3+i%3] }.toFloat()
            }
            reference = null
        } else {
            sensorOrigin = null
            val next = tracking?.invoke() ?: TrackingImage(image())
            val delta = reference?.motionTo(next,field)
            lost = reference != null && delta == null
            if (delta != null) {
                val prior = orientation
                orientation = FloatArray(9) { i ->
                    (0..2).sumOf { k -> prior[i/3*3+k]*delta[k*3+i%3] }.toFloat()
                }
            }
            if (!lost) reference = next
        }
        if (lost || anchor == null || nowMs-lastTime !in 0..500 || moved(anchor!!,orientation,.45)) {
            anchor = orientation
            since = nowMs
        }
        val elapsed = nowMs-lastTime
        lastTime = nowMs
        val stationary = nowMs-since >= 350
        val maxMotion = Math.toDegrees(2*min(field.x,field.y)/LONG_EDGE) * 2.5 *
            elapsed.coerceIn(1,500)/max(1.0,exposureMs)
        val sharp = elapsed in 1..500 && !moved(previousOrientation,orientation,min(8.0,maxMotion))
        // A failed registration must not require another large turn or a perfect
        // stationary interval. Small frame-to-frame movement permits a fresh candidate.
        val retry = retryStationary && elapsed in 1..500 &&
            !moved(previousOrientation,orientation,1.5) && nowMs-attemptedAt >= 160
        // A long exposure can suppress every sharp-frame attempt during a sweep.
        // Periodically validate an intermediate view against original keyframes.
        val bridge = aligner?.rawReferences == true && lastAttempt != null &&
            nowMs-attemptedAt >= 150 && (retryStationary || moved(lastAttempt!!,orientation,2.0))
        val continuous = aligner != null && (sharp || retry || bridge)
        settling = !stationary && !continuous && lastAttempt != null
        val step = if (mosaic.coverage >= .90) .45 else if (stationary) 1.0 else Math.toDegrees(atan(min(field.x,field.y)))*.14
        if (!mosaic.ready && !lost && (stationary || continuous || (aligner != null && accepted == 0 && lastAttempt == null)) &&
            (lastAttempt == null || bridge || moved(lastAttempt!!,orientation,step) || (retryStationary && ((stationary && nowMs-attemptedAt >= 220) || (continuous && nowMs-attemptedAt >= 110))))) {
            val before = mosaic.coverage
            // If moving registration is unreliable, allow a stationary retry at this pose.
            disconnected = !mosaic.add(field,orientation,!stationary,::image)
            if (mosaic.coverage > before) {
                accepted++
                nextTarget = mosaic.nextUncovered(mosaic.aim(orientation),field)
                rendered = null
            }
            retryStationary = disconnected
            attemptedAt = nowMs
            lastAttempt = orientation
        }
        if (rendered == null || moved(renderedPose!!,orientation,.06)) {
            rendered = mosaic.view(orientation)
            renderedPose = orientation
        }
        return rendered!!
    }

    fun guide(displayTurn: Int): ScanGuide {
        val aim = mosaic.aim(orientation)
        val home = mosaic.homeDirection(orientation)?.let {
            val p = FinderPoint(.5 + .5*it.x,.5 + .5*it.y).turned(displayTurn)
            FinderPoint(2*p.x-1,2*p.y-1)
        }
        val initialLevel = startLevel?.let {
            when (Math.floorMod(displayTurn,360)) {
                90 -> LevelReading(it.upY,-it.upX,it.upZ)
                180 -> LevelReading(-it.upX,-it.upY,it.upZ)
                270 -> LevelReading(-it.upY,it.upX,it.upZ)
                else -> it
            }
        }
        return ScanGuide(accepted,mosaic.coverage,aim.turned(displayTurn),
            nextTarget?.turned(displayTurn),lost || disconnected,mosaic.tileCoverage(),displayTurn,home,settling,
            startMark = mosaic.startMark(orientation,displayTurn), startRollRadians = initialLevel?.rollRadians,
            startPitchRadians = initialLevel?.pitchRadians, startOverhead = initialLevel?.overhead == true)
    }
}

// Frame-to-frame alignment in angular coordinates. Correlation is insensitive to a change
// in exposure and a candidate must agree across the portion common to both real frames.
class TrackingImage(w: Int, h: Int, sample: (Double, Double) -> Int) {
    constructor(frame: Frame) : this(frame.w, frame.h, frame::sample)
    private val width = min(144,w)
    private val height = (width * h.toDouble()/w).roundToInt().coerceIn(1,192)
    private val data = IntArray(width*height) { i ->
        luma(sample((i%width+.5)/width,(i/width+.5)/height))
    }

    fun motionTo(next: TrackingImage, field: Field): DoubleArray? {
        if (width != next.width || height != next.height || min(width, height) < 16) return null
        val fx = width / (2 * field.x)
        val fy = height / (2 * field.y)
        val step = max(2, min(width, height) / 14)
        fun rotation(dx: Double, dy: Double): DoubleArray {
            val yaw = atan(dx / fx)
            val pitch = atan(dy / fy)
            val a = cos(yaw)
            val b = sin(yaw)
            val c = cos(pitch)
            val d = sin(pitch)
            return doubleArrayOf(a, b * d, b * c, 0.0, c, -d, -b, a * d, a * c)
        }
        fun score(dx: Double, dy: Double): Double {
            val r = rotation(dx, dy)
            // Vote by spatial region. A flashing display or a passing person can dominate
            // the total image variance despite occupying a small portion of a static scene.
            val regions = Array(9) { DoubleArray(6) }
            for (y in step / 2 until height step step) for (x in step / 2 until width step step) {
                val u = (x + .5 - width * .5) / fx
                val v = (height * .5 - y - .5) / fy
                val z = r[8] - r[2] * u - r[5] * v
                if (z <= 0) continue
                val px = width * .5 - .5 + (r[0] * u + r[3] * v - r[6]) * fx / z
                val py = height * .5 - .5 - (r[1] * u + r[4] * v - r[7]) * fy / z
                if (px < 0 || py < 0 || px >= width - 1 || py >= height - 1) continue
                val pos = py.toInt() * width + px.toInt()
                val tx = px - px.toInt()
                val ty = py - py.toInt()
                val top = next.data[pos] * (1 - tx) + next.data[pos + 1] * tx
                val bottom = next.data[pos + width] * (1 - tx) + next.data[pos + width + 1] * tx
                val a = data[y * width + x].toDouble()
                val b = top * (1 - ty) + bottom * ty
                val stats = regions[(y * 3 / height) * 3 + x * 3 / width]
                stats[0]++; stats[1] += a; stats[2] += b
                stats[3] += a*a; stats[4] += b*b; stats[5] += a*b
            }
            val scores = regions.mapNotNull { s ->
                val count = s[0]
                if (count < 8) return@mapNotNull null
                val varA = s[3] - s[1]*s[1]/count
                val varB = s[4] - s[2]*s[2]/count
                if (min(varA, varB) < 9*count) null
                else (s[5]-s[1]*s[2]/count)/sqrt(varA*varB)
            }.sorted()
            return if (scores.size >= 4) scores[scores.size/2] else -1.0
        }
        var bx = 0.0
        var by = 0.0
        var best = score(0.0, 0.0)
        if (best > .985) return rotation(0.0, 0.0)
        fun evaluate(x: Double, y: Double) {
            val value = score(x, y) - .0003 * (abs(x) + abs(y))
            if (value > best) {
                best = value
                bx = x
                by = y
            }
        }
        val radius = (min(width, height) / 5).coerceIn(4, 24)
        for (dy in -radius..radius step 3) for (dx in -radius..radius step 3) evaluate(
            dx.toDouble(),
            dy.toDouble(),
        )
        val cx = bx
        val cy = by
        for (dy in -3..3) for (dx in -3..3) evaluate(cx + dx, cy + dy)
        val fineX = bx
        val fineY = by
        for (dy in -2..2) for (dx in -2..2) evaluate(fineX + dx * .35, fineY + dy * .35)
        return if (best > .85) rotation(bx, by) else null
    }
}
