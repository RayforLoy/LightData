package studio.lightdata

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class LevelReadingTest {
    private fun axes(x: Float, y: Float, z: Float) = floatArrayOf(0f,0f,0f,0f,0f,0f,x,y,z)

    @Test fun flatBackCameraPointsAtGroundAndBubbleMovesToRaisedEdge() {
        val flat = levelReading(axes(0f,0f,1f))
        assertTrue(flat.overhead)
        assertEquals(-PI/2,flat.pitchRadians,1e-6)
        assertEquals(0.0,flat.bubbleX,1e-6)
        assertEquals(0.0,flat.bubbleY,1e-6)
        val tilted = levelReading(axes(.1f,.15f,.98f))
        assertTrue(tilted.bubbleX > 0 && tilted.bubbleY < 0)
    }

    @Test fun uprightHorizonAndRotatedDisplayAgree() {
        val upright = levelReading(axes(0f,1f,0f))
        assertFalse(upright.overhead)
        assertEquals(0.0,upright.rollRadians,1e-6)
        assertEquals(0.0,upright.pitchRadians,1e-6)
        val landscape = levelReading(displayAxes(axes(1f,0f,0f),1))
        assertEquals(upright.rollRadians,landscape.rollRadians,1e-6)
        val roll = levelReading(axes(.5f,sqrt(.75f),0f))
        assertEquals(PI/6,roll.rollRadians,1e-6)
    }
}
