package studio.lightdata

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class ExposurePlacementTest {
    @Test fun meterPlacesZoneBeforeOpticsAndReciprocity() {
        val loss = opticalLossStops(1.0, 200.0, 100.0)
        assertEquals(3.0, loss, 1e-8)
        val middle = zoneMiddleEv(4.0, 3)
        assertEquals(6.0, middle, 1e-8)
        val target = shootingExposure("A", Exposure(1.0, 8.0, 100.0), middle, loss, 0.0, true)
        assertEquals(8.0, target.t, 1e-8)
        assertEquals(3.0, 4.0 - (target.ev + loss) + 5, 1e-8)
        val corrected = reciprocity("HP5 PLUS", target.t)
        assertTrue(corrected.exposureSeconds!! > 8.0)
    }

    @Test fun shutterPriorityKeepsTimeAndManualKeepsChosenDials() {
        val manual = Exposure(.25, 8.0, 100.0)
        assertEquals(manual, shootingExposure("M", manual, 9.0, 2.0, 0.0))
        val shutter = shootingExposure("S",manual,9.0,2.0,0.0)
        assertEquals(.25,shutter.t,0.0)
        assertTrue(abs(shutter.ev + 2 - 9) < .1)
        assertEquals(0.0, opticalLossStops(0.0, 0.0, 50.0), 0.0)
    }

    @Test fun groundGlassDifferenceIsAbsoluteAndRejectsInvertedSamples() {
        assertEquals(2.5, measuredTransmissionLoss(9.0,6.5)!!, 0.0)
        assertEquals(0.0, measuredTransmissionLoss(4.0,4.0)!!, 0.0)
        assertNull(measuredTransmissionLoss(4.0,5.0))
        assertNull(measuredTransmissionLoss(Double.NaN,5.0))
    }

    @Test fun bulbCanExceedLastMarkedShutter() {
        val e = shootingExposure("A", Exposure(1.0,16.0,100.0), 2.0, 4.0, 0.0)
        assertEquals(1024.0,e.t,0.0)
        val betweenMarkings = shootingExposure("A", Exposure(1.0,8.0,100.0), 1.0, kotlin.math.log2(3.0), 0.0)
        assertEquals(96.0,betweenMarkings.t,1e-8)
    }
}
