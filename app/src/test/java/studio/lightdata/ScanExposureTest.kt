package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class ScanExposureTest {
    private val times = 100_000L..100_000_000L

    @Test fun doorwayExposureReachesTargetWithoutChangingBrightness() {
        val next = fasterScanExposure(ScanExposure(29_994_234,978),times,50..6400,100)!!
        assertEquals(2933,next.iso)
        assertEquals(10_000_000.0,next.timeNs.toDouble(),2000.0)
        assertFalse(slowScanShutter(next.timeNs))
    }

    @Test fun isoCeilingRetainsExposureAndWarns() {
        val next = fasterScanExposure(ScanExposure(30_000_000,1000),times,50..1600,100)!!
        assertEquals(1600,next.iso)
        assertEquals(18_750_000,next.timeNs)
        assertTrue(slowScanShutter(next.timeNs))
        assertNull(fasterScanExposure(next,times,50..1600,100))
    }

    @Test fun brightSceneStaysOnAeLockAndPostRawGainIsAccountedFor() {
        assertNull(fasterScanExposure(ScanExposure(8_000_000,100),times,50..3200,100))
        assertEquals(1200,fasterScanExposure(ScanExposure(30_000_000,200,200),times,50..3200,100)!!.iso)
    }
}
