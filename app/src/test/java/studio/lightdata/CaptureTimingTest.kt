package studio.lightdata

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureTimingTest {
    @Test fun usesExposureMidpointAndCentralReadoutTime() {
        assertEquals(1_025_525_885L,exposurePoseTimestamp(1_000_000_000L,39_965_296L,11_086_475L))
    }
    @Test fun offCenterCropChangesRollingShutterTime() {
        assertEquals(125L,exposurePoseTimestamp(100L,20L,20L,.75))
    }
    @Test fun unknownClockCannotBeAlignedToImu() {
        assertEquals(0L,exposurePoseTimestamp(0L,40_000_000L,11_000_000L))
        assertEquals(120L,exposurePoseTimestamp(100L,40L,null))
    }
}
