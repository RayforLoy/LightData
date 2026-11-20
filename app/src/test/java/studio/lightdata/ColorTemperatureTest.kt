package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class ColorTemperatureTest {
    private val identity = doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
    private val daylight = doubleArrayOf(.95047,1.0,1.08883)
    private val tungsten = doubleArrayOf(1.0985,1.0,.35585)
    private fun frame(color: Int, white: SceneWhite?=sceneWhite(daylight)) =
        Frame(7,7,IntArray(49) { color },0.0,"test",whitePoint=white)

    @Test fun recoversDaylightAndTungstenFromSensorSpecificCoordinates() {
        val factory = SensorMatrix(doubleArrayOf(.68,.21,.09,.17,.81,.03,.02,.1,.62),6504.0)
        for ((xyz,expected) in listOf(daylight to 6504,tungsten to 2856)) {
            val raw=matrixVector(factory.xyzToCamera,xyz)
            assertEquals(expected.toDouble(),factory.estimate(raw,null)!!.kelvin.toDouble(),80.0)
        }
    }

    @Test fun interpolatesBetweenDistinctCalibrationIlluminants() {
        val first = SensorMatrix(identity,2856.0)
        val second = SensorMatrix(doubleArrayOf(.8,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.2),6504.0)
        val xyz = doubleArrayOf(.96422,1.0,.82521) // D50
        val weight = (1/5003.0-1/2856.0)/(1/6504.0-1/2856.0)
        val simulated = DoubleArray(9) { identity[it]*(1-weight)+second.xyzToCamera[it]*weight }
        val estimate = first.estimate(matrixVector(simulated,xyz),second)
        assertNotNull(estimate)
        assertEquals(5003.0,estimate!!.kelvin.toDouble(),100.0)
    }

    @Test fun rejectsInvalidMeasurements() {
        assertNull(SensorMatrix(DoubleArray(9),6504.0).estimate(daylight,null))
        assertNull(sceneWhite(doubleArrayOf(.05,1.0,.05)))
        assertNull(frame(0).localKelvin(.5,.5))
        assertNull(frame(-1).localKelvin(.5,.5))
        assertNull(frame(0xffaaaaaa.toInt(),null).localKelvin(.5,.5))
        assertNull(frame(0xffaaaaaa.toInt()).localKelvin(-.01,.5))
    }

    @Test fun neutralPatchTracksIlluminantAndWarmPatchIsWarmer() {
        for (xyz in listOf(daylight,tungsten)) {
            val anchor = sceneWhite(xyz)!!
            assertEquals(anchor.kelvin,frame(0xffa0a0a0.toInt(),anchor).localKelvin(.5,.5))
        }
        val warmer = frame(0xffd2b096.toInt()).localKelvin(.5,.5)
        assertNotNull(warmer)
        assertTrue(warmer!!<5400)
    }
}
