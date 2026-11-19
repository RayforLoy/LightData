package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class ParallaxTest {
    @Test fun translatedGateUsesBothLensDepthsAndDisplayAxes() {
        val baseline = ParallaxConfig(true, xMm = 80.0, yMm = 40.0, zMm = 100.0, distanceMm = 950.0)
        val field = Field(.4,.3)
        val gate = baseline.project(field, 50.0, 0)
        assertEquals(.08,gate.right,1e-12)
        assertEquals(.04,gate.up,1e-12)
        assertEquals(.36,gate.field.x,1e-12)
        assertFalse(gate.coveredBy(Field(.4,.5)))
        assertTrue(gate.coveredBy(Field(.45,.32)))
        val rotated = baseline.project(field, 50.0, 1)
        assertEquals(-.04,rotated.right,1e-12)
        assertEquals(.08,rotated.up,1e-12)
        assertEquals(FinderGate(field),baseline.copy(distanceMm = Double.POSITIVE_INFINITY).project(field,50.0,2))
        assertFalse(baseline.copy(zMm = -900.0).valid(50.0))
    }

    @Test fun displacedLiveAndFrozenCropExcludeMissingPixelsFromMeter() {
        val w = 20
        val h = 12
        val packet = Rig.Packet(w,h,listOf(
            Rig.Plane(ByteArray(w*h) { (20 + it % w * 10).toByte() },w,1),
            Rig.Plane(ByteArray(w*h/4) { 128.toByte() },w/2,1),
            Rig.Plane(ByteArray(w*h/4) { 128.toByte() },w/2,1)), YuvEncoding())
        val capture = LiveCapture(packet,8.0,"test",111,0,Field(1.0,1.0),Field(.5,.5),.8,.5)
        val frame = capture.fullFrame()
        assertEquals(packet.pixel(13,0),frame.pixels[0])
        assertEquals(0,frame.pixels[9])
        assertEquals(7 * 6,frame.distribution().sum())
        assertEquals(.65,capture.gate[0].toDouble(),1e-6)
        assertEquals(0.0,capture.gate[1].toDouble(),1e-6)
    }

    @Test fun panoramaShowsSameOffsetInItsOutputAndRegistrationReference() {
        val id = floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
        val f = Frame(100,100,IntArray(10000) { index ->
            if (index % 100 >= 65 && index / 100 < 45) -1 else 0xff202020.toInt()
        },8.0,"test")
        val scan = Mosaic(Field(.18,.18),id,f,right = .5,up = .4)
        assertTrue(scan.add(f,Field(1.0,1.0), id))
        val output = scan.view(id)
        assertEquals(-1,output.sample(.5,.5))
        assertEquals(.5,scan.startMark(id,0)!!.center.x,1e-5)
        assertEquals(.5,scan.startMark(id,0)!!.center.y,1e-5)
        scan.close()
    }
}
