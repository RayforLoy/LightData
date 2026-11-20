package studio.lightdata

import kotlin.math.*

/** A finder ray expressed in the rotation sensor's world coordinates. */
internal data class Direction(val x: Double, val y: Double, val z: Double)
internal data class GatePose(val axes: FloatArray, val gate: FinderGate) {
    fun ray(x: Double, y: Double): Direction {
        val u = (2*x-1)*gate.field.x + gate.right
        val v = (1-2*y)*gate.field.y + gate.up
        fun row(i: Int) = axes[i]*u + axes[i+1]*v - axes[i+2]
        return Direction(row(0),row(3),row(6))
    }

    fun project(ray: Direction): FinderPoint? {
        fun column(i: Int) = axes[i]*ray.x + axes[i+3]*ray.y + axes[i+6]*ray.z
        val depth = -column(2)
        if (depth <= .02) return null
        return FinderPoint(.5 + (column(0)/depth-gate.right)/(2*gate.field.x),
            .5 - (column(1)/depth-gate.up)/(2*gate.field.y))
    }
}

internal data class TrackedSpot(val spot: Spot, val stale: Boolean = false, val alpha: Float = 1f,
    val inView: Boolean = true, val moveBack: Boolean = false, val kelvin: Int? = null, val worldRay: Direction? = null)

/** Immutable template so an occluder cannot gradually replace the original subject. */
internal class SpatialAnchor private constructor(
    val id: Int, private val rays: List<Direction>, private val template: DoubleArray,
    private val origin: Direction, private val energy: Double,
) {
    private var lastRay = origin
    private var fadingSince: Long? = null
    private var hadParallax = false

    companion object {
        fun create(id: Int, f: Frame, pose: GatePose, x: Double, y: Double): SpatialAnchor? {
            if (x !in 0.0..1.0 || y !in 0.0..1.0 || f.sample(x,y) ushr 24 == 0) return null
            val stride = max(1.0,min(f.w,f.h)*.009)
            val rays = mutableListOf<Direction>()
            val levels = mutableListOf<Double>()
            for (j in -4..4) for (i in -4..4) {
                val u = x+i*stride/f.w
                val v = y+j*stride/f.h
                if (u !in 0.0..1.0 || v !in 0.0..1.0) continue
                val pixel = f.sample(u,v)
                if (pixel ushr 24 == 0) continue
                rays += pose.ray(u,v)
                levels += luma(pixel).toDouble()
            }
            if (levels.size < 20) return null
            val mean = levels.average()
            val pattern = DoubleArray(levels.size) { levels[it]-mean }
            return SpatialAnchor(id,rays,pattern,pose.ray(x,y),pattern.sumOf { it*it })
        }
    }

    fun update(f: Frame, pose: GatePose, prior: TrackedSpot, response: DoubleArray, time: Long,
               moved: Boolean, localTemperature: (Double,Double)->Int?): TrackedSpot {
        val expected = pose.project(origin)
        val recent = pose.project(lastRay)
        val predicted = if (recent != null && recent.x in 0.0..1.0 && recent.y in 0.0..1.0) recent else expected
        fun lost(at: FinderPoint?, visible: Boolean, displaced: Boolean): TrackedSpot {
            if (displaced && fadingSince == null) fadingSince = time
            val alpha = fadingSince?.let { (1.0-(time-it)/6000.0).coerceIn(0.0,1.0).toFloat() } ?: 1f
            return prior.copy(spot=prior.spot.copy(x=at?.x ?: -1.0,y=at?.y ?: -1.0),
                worldRay=lastRay,stale=true,inView=visible,moveBack=fadingSince!=null,alpha=alpha)
        }
        if (predicted == null || expected == null || predicted.x !in 0.0..1.0 || predicted.y !in 0.0..1.0)
            return lost(predicted,false,hadParallax && moved)
        if (energy < template.size*9) return lost(predicted,true,moved)
        val px = DoubleArray(rays.size)
        val py = DoubleArray(rays.size)
        for (i in rays.indices) {
            val p = pose.project(rays[i]) ?: return lost(predicted,true,false)
            px[i] = p.x + predicted.x - expected.x
            py[i] = p.y + predicted.y - expected.y
        }
        fun score(dx: Double, dy: Double): Double {
            var sum = 0.0; var squares = 0.0; var cross = 0.0
            for (i in template.indices) {
                val x = px[i]+dx/f.w; val y = py[i]+dy/f.h
                if (x !in 0.0..1.0 || y !in 0.0..1.0) return -1.0
                val pixel = f.sample(x,y)
                if (pixel ushr 24 == 0) return -1.0
                val v = luma(pixel).toDouble()
                sum += v; squares += v*v; cross += template[i]*v
            }
            val variance = squares-sum*sum/template.size
            return if (variance > template.size*4) cross/sqrt(variance*energy) else -1.0
        }
        val short = min(f.w,f.h).toDouble()
        val step = max(1.0, short*.006)
        var bx = 0.0; var by = 0.0; var best = -1.0
        val originalDx = (expected.x-predicted.x)*f.w
        val originalDy = (expected.y-predicted.y)*f.h
        // After a large lateral move the last match and the original ray may be far apart.
        // Search both so returning to the original viewpoint can recover the original patch.
        val origins = if (hypot(originalDx,originalDy) > 3*step)
            listOf(0.0 to 0.0,originalDx to originalDy) else listOf(0.0 to 0.0)
        val scores = DoubleArray(origins.size*17*17)
        for ((region,offset) in origins.withIndex()) for (j in -8..8) for (i in -8..8) {
            val dx = offset.first+i*step; val dy = offset.second+j*step
            val s = score(dx,dy)
            scores[region*289+(j+8)*17+i+8] = s
            if (s > best) { best=s; bx=dx; by=dy }
        }
        val coarseX=bx; val coarseY=by
        for (j in -2..2) for (i in -2..2) {
            val x = coarseX+i*step/3; val y = coarseY+j*step/3
            val s = score(x,y)
            if (s > best) { best=s; bx=x; by=y }
        }
        val ambiguous = scores.indices.any { k ->
            hypot(origins[k/289].first+(k%17-8)*step-bx,origins[k/289].second+(k%289/17-8)*step-by)>3*step && scores[k] > best-.055
        }
        if (best < .77 || ambiguous) return lost(predicted,true,moved || hadParallax)
        val x=predicted.x+bx/f.w; val y=predicted.y+by/f.h
        val disparity = hypot((x-expected.x)*f.w,(y-expected.y)*f.h)/short
        hadParallax = disparity > .045
        lastRay = pose.ray(x,y)
        if (disparity > .095) return lost(FinderPoint(x,y),true,true)
        val ev = f.spot(x,y,response)
        if (!ev.isFinite()) return lost(predicted,true,false)
        fadingSince = null
        val kelvin = if (id==0) localTemperature(x,y) else null
        if (id==0 && kelvin==null && prior.kelvin!=null) return lost(FinderPoint(x,y),true,false)
        return TrackedSpot(Spot(id,x,y,ev),worldRay=lastRay,kelvin=kelvin)
    }
}
