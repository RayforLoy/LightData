package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class GroundCalibrationTest {
    @Test fun appliesInfinityOffsetAndBellowsLossOnce() {
        val c = GroundCalibration(12.0-7.0,8.0)
        assertEquals(1.0/64,c.seconds(7.0,100.0),.00001)
        assertEquals(1.0/16,c.seconds(5.0,100.0),.00001)
        assertEquals(12.0,c.equivalentSceneEv(5.0,16.0),.00001) // two stops due to stopping down
        assertEquals(10.0,c.equivalentSceneEv(5.0,8.0),.00001)  // two stops lost to extension
        assertEquals(1.0/32,c.seconds(5.0,200.0),.00001)
    }
    @Test fun stableBurstRejectsStaleDuplicateDarkAndUnstableFrames() {
        val b = GroundBurst(100)
        repeat(6) { assertNull(b.observe(100,5.0)) }
        for (t in 101L..104L) assertNull(b.observe(t,3.0))
        assertNull(b.observe(105,4.0))
        assertNull(b.observe(106,null))
        for (t in 107L..110L) assertNull(b.observe(t,4.0))
        assertEquals(4.0,b.observe(111,4.1)!!,.001)
    }
}
