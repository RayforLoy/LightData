package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class FaceBoxTest {
    @Test fun sensorCropAspectRotationAndParallaxFollowFinderGate() {
        val crop = FaceBox(100.0,200.0,4100.0,3200.0)
        val face = FaceBox(1100.0,1200.0,2100.0,1700.0)
        val normal = face.inStream(crop,2.0)
        assertEquals(FaceBox(.25,.25,.5,.5),normal)
        assertEquals(FaceBox(.5,.0,1.0,.5),normal.inGate(90,floatArrayOf(.25f,.25f,.5f,.5f)))
        assertEquals(FaceBox(.5,.5,.75,.75),normal.inGate(180,floatArrayOf(0f,0f,1f,1f)))
        assertEquals(FaceBox(.25,.5,.5,.75),normal.inGate(270,floatArrayOf(0f,0f,1f,1f)))
        assertNull(normal.inGate(0,floatArrayOf(.6f,.6f,.4f,.4f)))
    }

    @Test fun innerFaceHistogramIgnoresBrightBackgroundAndUncoveredPixels() {
        val box = FaceBox(.2,.2,.8,.8)
        val pixels = IntArray(200*200) { i ->
            when {
                i%200 in 60..140 && i/200 in 58..142 -> if(i%3==0) 0 else 0xff333333.toInt()
                else -> -1
            }
        }
        val face = Frame(200,200,pixels,8.0,"lens",faces=listOf(box))
        val histogram = face.faceHistogram!!
        assertTrue(histogram.sum() in 1..2304)
        assertEquals(histogram.sum(),histogram[0x33])
        assertTrue(face.distribution()[255] > 0)
        assertNull(face.copy(faces=emptyList()).faceHistogram)
        assertNull(face.copy(pixels=IntArray(pixels.size)).faceHistogram)
    }
}
