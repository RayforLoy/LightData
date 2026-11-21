package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class ConverterReleaseTest {
    @Test fun converterGainAndLossCombineWithManualBiasOnce() {
        val lens = Exposure(1.0/125, 8.0, 100.0)
        assertEquals(-1.0, converterStops(.71), .02)
        assertEquals(1.0, converterStops(1.4), .04)
        val loss = opticalLossStops(1.0, 0.0, 50.0)+converterStops(2.0)
        assertEquals(3.0, loss, 1e-8)
        assertEquals(1.0, shootingExposure("A",lens,10.0,loss,1.0).t, .01)
        assertEquals(lens, shootingExposure("M",lens,10.0,loss,1.0))
        assertEquals(-1.0, measuredTransmissionLoss(7.0,8.0,true)!!,0.0)
    }

    @Test fun updateCheckOrdersReleasesNumericallyIncludingBeta() {
        val versions = listOf("0.2.6-beta","0.2.7-beta","v0.2.7-rc.1","0.2.7","0.2.10-beta")
            .map { AppVersion.parse(it)!! }
        assertEquals(versions,versions.reversed().sorted())
        assertNull(AppVersion.parse("web-only"))
    }
}
