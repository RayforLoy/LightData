package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class ManualMeterInputTest {
    @Test fun acceptsFractionalOrDecimalTimeAndRejectsInvalidInput() {
        assertEquals(.008, parseShutterSeconds("1 / 125")!!, 0.0)
        assertEquals(.37, parseShutterSeconds("0.37")!!, 0.0)
        for (text in listOf("1/0", "-1/-2", "NaN", "Infinity", "0", "1/2/3", "86401")) {
            assertNull(text, parseShutterSeconds(text))
        }
    }

    @Test fun dialMovesToAdjacentMarkFromTypedValue() {
        val stops = listOf(1.0, 2.0, 4.0, 8.0)
        assertEquals(4.0, stepFromValue(stops, 3.0, 1), 0.0)
        assertEquals(2.0, stepFromValue(stops, 3.0, -1), 0.0)
        assertEquals(4.0, stepFromValue(stops, 80.0, -2), 0.0)
        assertEquals(1.0, stepFromValue(stops, .01, 1), 0.0)
    }

    @Test fun automaticHistogramCentersExactZoneAndCanFollowSceneInstead() {
        val scene = 6.35
        val zone = 8.72
        assertEquals(.5, HistogramWindow.automatic(scene, zone, true).fraction(zone), 1e-12)
        assertEquals(HistogramWindow.following(scene), HistogramWindow.automatic(scene, zone, false))
    }
}
