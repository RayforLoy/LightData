package studio.lightdata

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class ScanReferencesTest {
    @Test fun recoversRotationFromOriginalAfterPoseError() {
        assertNotNull(OpenCvScanAligner.available)
        val refs=ScanReferences()
        val field=Field(.75,.5625)
        val w=400
        val h=300
        val random=Random(31)
        val colors=IntArray(w*h) {
            val v=random.nextInt(256)
            -0x1000000 or (v*0x010101)
        }
        val first=Frame(w,h,colors,5.0,"test")
        val identity=doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        val yaw=Math.toRadians(5.0)
        val c=cos(yaw)
        val s=sin(yaw)
        val next=IntArray(w*h) { i ->
            val x=((i%w+.5)*2/w-1)*field.x
            val y=(1-(i/w+.5)*2/h)*field.y
            val z=-s*x-c
            val u=(.5-(c*x-s)/(2*field.x*z))*w
            val v=(.5+y/(2*field.y*z))*h
            if(u in 0.0..<w.toDouble() && v in 0.0..<h.toDouble()) colors[v.toInt()*w+u.toInt()] else 0
        }
        try {
            assertNull(refs.estimate(first,field,identity))
            refs.retain(identity)
            val placement=refs.estimate(first.copy(pixels=next),field,identity)
            assertNotNull(placement)
            assertEquals(-yaw,atan2(placement!![2],placement[0]),Math.toRadians(1.0))
            assertNull(refs.estimate(first.copy(pixels=IntArray(w*h)),field,identity))
        } finally {
            refs.close()
        }
    }
}
