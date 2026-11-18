package studio.lightdata

import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import kotlin.math.*

/** Verified original views remain usable when the resampled atlas has lost corner detail. */
internal class ScanReferences {
    private data class View(val points: Array<Point>, val descriptors: Mat, val sensor: DoubleArray,
                            val placement: DoubleArray, val fx: Double, val fy: Double, val cx: Double, val cy: Double)
    private val views = mutableListOf<View>()
    private val detector = ORB.create(750,1.2f,8,16,0,2,ORB.HARRIS_SCORE,31,9)
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
    private var pending: View? = null

    private fun distance(a: DoubleArray,b: DoubleArray) =
        acos(((a.indices.sumOf { a[it]*b[it] }-1)/2).coerceIn(-1.0,1.0))

    var validation: String? = null
        private set

    fun estimate(frame: Frame, field: Field, rotation: DoubleArray): DoubleArray? {
        validation = null
        pending?.descriptors?.release()
        pending = null
        val scale = min(1.0,540.0/max(frame.w,frame.h))
        val w = (frame.w*scale).roundToInt().coerceAtLeast(1)
        val h = (frame.h*scale).roundToInt().coerceAtLeast(1)
        val gray = Mat(h,w,CvType.CV_8UC1)
        val empty = Mat()
        val points = MatOfKeyPoint()
        val desc = Mat()
        try {
            gray.put(0,0,ByteArray(w*h) { i ->
                luma(frame.sample((i%w+.5)/w,(i/w+.5)/h)).toByte()
            })
            detector.detectAndCompute(gray,empty,points,desc)
            val view = View(points.toArray().map { it.pt }.toTypedArray(),desc,rotation,rotation,
                w/(2*field.x),h/(2*field.y),w*.5,h*.5)
            pending = view
            if (desc.rows()<16) return null
            var error = Double.POSITIVE_INFINITY
            var best: DoubleArray? = null
            for (ref in views.sortedBy { distance(it.sensor,rotation) }.take(4)) {
                val result = match(view,ref) ?: continue
                if (result.second < error && distance(rotation,result.first)<Math.toRadians(12.0)) {
                    best=result.first
                    error=result.second
                }
            }
            if (best!=null) validation="raw_reference medianPx=$error keys=${views.size}"
            return best
        } finally {
            gray.release()
            empty.release()
            points.release()
            if (pending==null) desc.release()
        }
    }

    fun retain(placement: DoubleArray) {
        val v=pending ?: return
        pending=null
        if (v.descriptors.rows()<16 || views.any { distance(it.sensor,v.sensor)<Math.toRadians(3.0) }) {
            v.descriptors.release()
            return
        }
        if (views.size>=36) views.removeAt(1).descriptors.release()
        views.add(v.copy(placement=placement))
    }

    private fun ray(p: Point,v: View): DoubleArray {
        val x=(p.x-v.cx)/v.fx
        val y=(v.cy-p.y)/v.fy
        val len=sqrt(x*x+y*y+1)
        return doubleArrayOf(x/len,y/len,-1/len)
    }

    private fun match(v: View,ref: View): Pair<DoubleArray,Double>? {
        val matches=mutableListOf<MatOfDMatch>()
        val from=MatOfPoint2f()
        val to=MatOfPoint2f()
        val mask=Mat()
        val covariance=Mat(3,3,CvType.CV_64F)
        val singular=Mat()
        val u=Mat()
        val vt=Mat()
        try {
            matcher.knnMatch(v.descriptors,ref.descriptors,matches,2)
            val good=matches.mapNotNull {
                val pair=it.toArray()
                pair.firstOrNull()?.takeIf { pair.size==2 && it.distance < .76*pair[1].distance }
            }.distinctBy { it.trainIdx }
            if (good.size<18) return null
            val train=good.filterIndexed { i,_ -> i%4!=0 }
            from.fromList(train.map { v.points[it.queryIdx] })
            to.fromList(train.map { ref.points[it.trainIdx] })
            Calib3d.findHomography(from,to,Calib3d.RANSAC,3.0,mask).release()
            if (mask.empty() || Core.countNonZero(mask)<10) return null
            val flags=ByteArray(train.size)
            mask.get(0,0,flags)
            // A rotation needs separated rays. Door edges can supply such rays along one line.
            val inliers=train.filterIndexed { index,_ -> flags[index].toInt()!=0 }
            val support=inliers.map { v.points[it.queryIdx] }
            val mx=support.map { it.x }.average()
            val my=support.map { it.y }.average()
            val xx=support.sumOf { (it.x-mx)*(it.x-mx) }/support.size
            val yy=support.sumOf { (it.y-my)*(it.y-my) }/support.size
            if (xx+yy < (min(v.cx,v.cy)*.09).pow(2)) return null
            val cross=DoubleArray(9)
            for ((i,m) in train.withIndex()) {
                if (flags[i].toInt()==0) continue
                val a=ray(v.points[m.queryIdx],v)
                val b=ray(ref.points[m.trainIdx],ref)
                for (j in cross.indices) cross[j]+=a[j/3]*b[j%3]
            }
            covariance.put(0,0,*cross)
            Core.SVDecomp(covariance,singular,u,vt)
            val left=DoubleArray(9)
            val right=DoubleArray(9)
            u.get(0,0,left)
            vt.get(0,0,right)
            val sign=if (Core.determinant(u)*Core.determinant(vt)<0) -1 else 1
            val r=DoubleArray(9) { i ->
                (0..2).sumOf { k -> left[i/3*3+k]*right[k*3+i%3]*(if(k==2) sign else 1) }
            }
            val residual=good.filterIndexed { i,_ -> i%4==0 }.map { m ->
                val b=ray(ref.points[m.trainIdx],ref)
                val predicted=DoubleArray(3) { j -> (0..2).sumOf { k -> r[j*3+k]*b[k] } }
                val p=v.points[m.queryIdx]
                if (predicted[2]>=0) Double.POSITIVE_INFINITY else
                    hypot(v.cx-v.fx*predicted[0]/predicted[2]-p.x,v.cy+v.fy*predicted[1]/predicted[2]-p.y)
            }.sorted()
            val error=residual[residual.size/2]
            if (error>4.0 || residual.count { it<5.0 }<4) return null
            return DoubleArray(9) { i -> (0..2).sumOf { k -> r[i/3*3+k]*ref.placement[k*3+i%3] } } to error
        } finally {
            matches.forEach { it.release() }
            listOf(from,to,mask,covariance,singular,u,vt).forEach { it.release() }
        }
    }

    fun close() {
        views.forEach { it.descriptors.release() }
        views.clear()
        pending?.descriptors?.release()
        pending=null
        matcher.clear()
        detector.clear()
    }
}
