package studio.lightdata

import kotlin.math.*

/** Scene white in CIE XYZ (Y = 1), from the active physical sensor. */
data class SceneWhite(val x: Double, val z: Double, val kelvin: Int)

internal fun matrixVector(m: DoubleArray, v: DoubleArray) =
    DoubleArray(3) { r -> (0..2).sumOf { c -> m[r*3+c]*v[c] } }

private fun invert(m: DoubleArray): DoubleArray? {
    val a=m[0]; val b=m[1]; val c=m[2]
    val d=m[3]; val e=m[4]; val f=m[5]
    val g=m[6]; val h=m[7]; val i=m[8]
    val adj = doubleArrayOf(e*i-f*h,c*h-b*i,b*f-c*e,f*g-d*i,a*i-c*g,c*d-a*f,d*h-e*g,b*g-a*h,a*e-b*d)
    val det = a*adj[0]+b*adj[3]+c*adj[6]
    if (!det.isFinite() || abs(det)<1e-9) return null
    return adj.apply { for (n in indices) this[n] /= det }
}

// Approximate the Planckian locus in CIE 1960 UCS. Distances in xy are not perceptually uniform.
private data class Locus(val mired: Int, val u: Double, val v: Double)
private val planckian = (40..598 step 2).map { m ->
    val t = 1e6/m
    val k = 1000/t
    val x = if (t<=4000) -.2661239*k*k*k - .2343580*k*k + .8776956*k + .179910
        else -3.0258469*k*k*k + 2.1070379*k*k + .2226347*k + .240390
    val y = when {
        t<=2222 -> -1.1063814*x*x*x - 1.34811020*x*x + 2.18555832*x - .20219683
        t<=4000 -> -.9549476*x*x*x - 1.37418593*x*x + 2.09137015*x - .16748867
        else -> 3.0817580*x*x*x - 5.8733867*x*x + 3.75112997*x - .37001483
    }
    val denominator = -2*x + 12*y + 3
    Locus(m,4*x/denominator,6*y/denominator)
}

internal fun sceneWhite(xyz: DoubleArray): SceneWhite? {
    if (xyz.size!=3 || xyz.any { !it.isFinite() || it<=0 }) return null
    val x=xyz[0]/xyz[1]
    val z=xyz[2]/xyz[1]
    val denominator=x+15+3*z
    val u=4*x/denominator
    val v=6/denominator
    val near = planckian.minBy { (it.u-u)*(it.u-u)+(it.v-v)*(it.v-v) }
    if (hypot(u-near.u,v-near.v)>.025 || near==planckian.first() || near==planckian.last()) return null
    return SceneWhite(x,z,(1e6/near.mired).roundToInt())
}

internal data class SensorMatrix(val xyzToCamera: DoubleArray, val illuminant: Double?) {
    fun estimate(neutral: DoubleArray, other: SensorMatrix?): SceneWhite? {
        if (neutral.size!=3 || neutral.any { !it.isFinite() || it<=0 }) return null
        if (other?.illuminant==null || illuminant==null || other.illuminant==illuminant)
            return invert(xyzToCamera)?.let { sceneWhite(matrixVector(it,neutral)) }
        var weight = .5
        var white: SceneWhite? = null
        repeat(6) {
            val mixed = DoubleArray(9) { xyzToCamera[it]*(1-weight)+other.xyzToCamera[it]*weight }
            white = sceneWhite(matrixVector(invert(mixed) ?: return null,neutral)) ?: return null
            val next = ((1.0/white!!.kelvin-1/illuminant)/(1/other.illuminant-1/illuminant)).coerceIn(0.0,1.0)
            if (abs(next-weight)<.002) return white
            weight = next
        }
        return white
    }
}

private val bradford = doubleArrayOf(.8951,.2664,-.1614,-.7502,1.7135,.0367,.0389,-.0685,1.0296)
private val inverseBradford = checkNotNull(invert(bradford))
private val d65Cone = matrixVector(bradford,doubleArrayOf(.95047,1.0,1.08883))
private val srgbToXyz = doubleArrayOf(.4124564,.3575761,.1804375,.2126729,.7151522,.0721750,.0193339,.1191920,.9503041)

/** A neutral surface under local light, sampled before film rendering. */
fun Frame.localKelvin(x: Double,y: Double): Int? {
    val white = whitePoint ?: return null
    if (x !in 0.0..1.0 || y !in 0.0..1.0) return null
    val rgb=DoubleArray(3)
    val cx=(x*w).toInt().coerceAtMost(w-1)
    val cy=(y*h).toInt().coerceAtMost(h-1)
    var count=0
    for (j in max(0,cy-2)..min(h-1,cy+2)) for (i in max(0,cx-2)..min(w-1,cx+2)) {
        val p=pixels[j*w+i]
        val r=p ushr 16 and 255
        val g=p ushr 8 and 255
        val b=p and 255
        if (p ushr 24==0 || maxOf(r,g,b)>=249 || minOf(r,g,b)<8) continue
        rgb[0]+=srgbToLinear(r/255.0)
        rgb[1]+=srgbToLinear(g/255.0)
        rgb[2]+=srgbToLinear(b/255.0)
        count++
    }
    if (count<3) return null
    val cones=matrixVector(bradford,matrixVector(srgbToXyz,rgb))
    val reference=matrixVector(bradford,doubleArrayOf(white.x,1.0,white.z))
    for (i in cones.indices) cones[i]*=reference[i]/d65Cone[i]
    return sceneWhite(matrixVector(inverseBradford,cones))?.kelvin
}
