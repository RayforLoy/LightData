package studio.lightdata

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FlashReadingTest {
    private val curve=DoubleArray(256) { log2(max(1,it)/64.0) }
    private fun shot(code:Int)=FlashFrame(DoubleArray(64){code/255.0},curve,200.0,4.0,250_000_000L)
    @Test fun ambientSubtractionAndIsoScaling() {
        assertEquals(4.0,flashReading(shot(32),shot(96),shot(32),200.0).aperture!!,1e-8)
        assertEquals(8.0,flashReading(shot(32),shot(96),shot(32),800.0).aperture!!,1e-8)
        assertEquals(4.0,flashReading(shot(16).copy(timeNs=125_000_000),shot(96),shot(32),200.0).aperture!!,1e-8)
    }
    @Test fun rejectsWeakSaturatedAndChangingAmbient() {
        assertEquals(FlashIssue.WEAK,flashReading(shot(32),shot(32),shot(32),100.0).issue)
        assertEquals(FlashIssue.CLIPPED,flashReading(shot(32),shot(255),shot(32),100.0).issue)
        assertEquals(FlashIssue.UNSTABLE,flashReading(shot(32),shot(96),shot(72),100.0).issue)
    }
    @Test fun commonWindowExcludesRollingShutterAndPulseTail() {
        assertFalse(flashFitsAllRows(0,10_000_000,100_000_000,8_000_000))
        assertTrue(flashFitsAllRows(0,10_000_000,100_000_000,50_000_000))
        assertFalse(flashFitsAllRows(0,10_000_000,100_000_000,95_000_000))
    }
}
