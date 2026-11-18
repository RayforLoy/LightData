package studio.lightdata

import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import org.opencv.features2d.ORB
import org.opencv.features2d.BFMatcher
import kotlin.math.*

/** Sensor-seeded, pyramidal Lucas–Kanade registration with a robust similarity fit. */
internal object OpenCvScanAligner : ScanAligner {
    val available: ScanAligner? by lazy { if (OpenCVLoader.initLocal()) this else null }

    fun forMode(mode: String): ScanAligner? {
        if (available == null) return null
        return when(mode) {
            "ORB" -> ScanAligner { w,h,a,b,m -> alternative(w,h,a,b,m,false) }
            "ECC" -> ScanAligner { w,h,a,b,m -> alternative(w,h,a,b,m,true) }
            "自动" -> object : ScanAligner {
                override val rawReferences = true
                override fun align(w: Int,h: Int,reference: ByteArray,incoming: ByteArray,mask: ByteArray) =
                    candidates(w,h,reference,incoming,mask).firstOrNull()
                override fun candidates(w: Int,h: Int,reference: ByteArray,incoming: ByteArray,mask: ByteArray) = sequence {
                    OpenCvScanAligner.align(w,h,reference,incoming,mask)?.let { yield(it) }
                    alternative(w,h,reference,incoming,mask,false)?.let { yield(it) }
                    alternative(w,h,reference,incoming,mask,true)?.let { yield(it) }
                }
            }
            else -> this
        }
    }
    private fun alternative(w: Int,h: Int,a: ByteArray,b: ByteArray,mask: ByteArray,ecc: Boolean): ScanWarp? {
        val mats = mutableListOf<Mat>()
        fun <T: Mat> T.keep(): T { mats.add(this); return this }
        fun gray(data: ByteArray) = Mat(h,w,CvType.CV_8UC1).keep().apply { put(0,0,data) }
        try {
            val ref = gray(a); val src = gray(b); val valid = gray(mask)
            val fit: Mat
            if (ecc) {
                fit = Mat.eye(2,3,CvType.CV_32F).keep()
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(15.0,15.0)).keep()
                Imgproc.erode(valid,valid,kernel)
                if (Core.countNonZero(valid) < 300) return null
                val score = Video.findTransformECC(ref,src,fit,Video.MOTION_EUCLIDEAN,
                    TermCriteria(TermCriteria.COUNT+TermCriteria.EPS,35,.0005),valid,5)
                if (score < .85) return null
            } else {
                val orb = ORB.create(700,1.2f,8,12,0,2,ORB.HARRIS_SCORE,21,10)
                val matcher = BFMatcher.create(Core.NORM_HAMMING,false)
                try {
                    val k1 = MatOfKeyPoint().keep(); val k2 = MatOfKeyPoint().keep()
                    val d1 = Mat().keep(); val d2 = Mat().keep()
                    orb.detectAndCompute(ref,valid,k1,d1)
                    orb.detectAndCompute(src,Mat().keep(),k2,d2)
                    if (d1.empty() || d2.rows()<2) return null
                    val matches = mutableListOf<MatOfDMatch>()
                    try {
                        matcher.knnMatch(d1,d2,matches,2)
                        val good = matches.map { it.toArray() }.filter { it.size==2 && it[0].distance < .72*it[1].distance }.map { it[0] }
                        if (good.size<8) return null
                        val p1=k1.toArray(); val p2=k2.toArray()
                        val from=MatOfPoint2f(*good.map { p1[it.queryIdx].pt }.toTypedArray()).keep()
                        val to=MatOfPoint2f(*good.map { p2[it.trainIdx].pt }.toTypedArray()).keep()
                        val inliers=Mat().keep()
                        fit=Calib3d.estimateAffinePartial2D(from,to,inliers,Calib3d.RANSAC,2.0).keep()
                        if (fit.empty() || Core.countNonZero(inliers)<max(7,(good.size*.6).toInt())) return null
                    } finally { matches.forEach { it.release() } }
                } finally { orb.clear(); matcher.clear() }
            }
            val values=Mat().keep()
            fit.convertTo(values,CvType.CV_64F)
            val m=DoubleArray(6); values.get(0,0,m)
            if (hypot(m[0],m[3]) !in .93..1.07 || abs(m[3])>.10 || hypot(m[2],m[5])>min(w,h)*.20) return null
            return ScanWarp(m[0],m[3],m[2],m[5])
        } catch (_: CvException) { return null }
        finally { mats.asReversed().forEach { it.release() } }
    }

    override fun align(w: Int, h: Int, reference: ByteArray, incoming: ByteArray, mask: ByteArray): ScanWarp? {
        if (min(w,h) < 20) return null
        val allocations = mutableListOf<Mat>()
        fun <T: Mat> T.owned(): T { allocations.add(this); return this }
        fun gray(bytes: ByteArray) = Mat(h,w,CvType.CV_8UC1).owned().apply { put(0,0,bytes) }
        try {
            val ref = gray(reference)
            val src = gray(incoming)
            val valid = gray(mask)
            // A corner must belong to the image, not the boundary of the warped atlas.
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(13.0,13.0)).owned()
            Imgproc.erode(valid,valid,kernel)
            val corners = MatOfPoint().owned()
            Imgproc.goodFeaturesToTrack(ref,corners,100,.015,5.0,valid)
            if (corners.total() < 8) return null
            val pts = corners.toArray()
            val old = MatOfPoint2f(*pts).owned()
            val next = MatOfPoint2f().owned()
            val back = MatOfPoint2f().owned()
            val forwardStatus = MatOfByte().owned()
            val backStatus = MatOfByte().owned()
            val errors = MatOfFloat().owned()
            val window = Size(15.0,15.0)
            Video.calcOpticalFlowPyrLK(ref,src,old,next,forwardStatus,errors,window,2)
            Video.calcOpticalFlowPyrLK(src,ref,next,back,backStatus,errors,window,2)
            val tracked = next.toArray()
            val returned = back.toArray()
            val forwardOk = forwardStatus.toArray()
            val backOk = backStatus.toArray()
            val good = pts.indices.filter { i ->
                forwardOk[i].toInt()!=0 && backOk[i].toInt()!=0 &&
                    tracked[i].x in 0.0..(w-1.0) && tracked[i].y in 0.0..(h-1.0) &&
                    hypot(pts[i].x-returned[i].x,pts[i].y-returned[i].y) < 1.2
            }
            if (good.size < 8) return null
            val from = MatOfPoint2f(*good.map { pts[it] }.toTypedArray()).owned()
            val to = MatOfPoint2f(*good.map { tracked[it] }.toTypedArray()).owned()
            val inliers = Mat().owned()
            val fit = Calib3d.estimateAffinePartial2D(from,to,inliers,Calib3d.RANSAC,1.6).owned()
            if (fit.empty() || Core.countNonZero(inliers) < max(7,(good.size*.65).toInt())) return null
            val m = DoubleArray(6)
            fit.get(0,0,m)
            if (hypot(m[0],m[3]) !in .95..1.05 || abs(m[3]) > .08 ||
                hypot(m[2],m[5]) > min(w,h)*.15) return null
            val global = ScanWarp(m[0],m[3],m[2],m[5])
            // Allow a coherent nearby surface to depart from the global motion.
            // Unsupported grid nodes retain the inertial/global projection.
            val dx = DoubleArray(16)
            val dy = DoubleArray(16)
            for (node in dx.indices) {
                val x = (node%4)*w/3.0
                val y = (node/4)*h/3.0
                val nearby = good.filter { abs(pts[it].x-x)<w*.25 && abs(pts[it].y-y)<h*.25 }
                if (nearby.size < 4) continue
                val rx = nearby.map { tracked[it].x-global.x(pts[it].x,pts[it].y) }.sorted()
                val ry = nearby.map { tracked[it].y-global.y(pts[it].x,pts[it].y) }.sorted()
                val n = nearby.size
                if (rx[3*n/4]-rx[n/4]>1.5 || ry[3*n/4]-ry[n/4]>1.5) continue
                dx[node] = rx[n/2].coerceIn(-3.0,3.0)/w
                dy[node] = ry[n/2].coerceIn(-3.0,3.0)/h
            }
            return global.copy(width=w,height=h,offsetsX=dx,offsetsY=dy)
        } catch (_: CvException) {
            return null
        } finally {
            allocations.asReversed().forEach { it.release() }
        }
    }
}
