package studio.lightdata

import org.junit.Assert.assertEquals
import org.junit.Test

class HistogramBarsTest {
    @Test fun interruptionStartsAtDisplayedCountsAndFreezeSnaps() {
        val bars = HistogramBars(intArrayOf(0, 80))
        bars.retarget(intArrayOf(80, 0), null, true)
        assertEquals(80f, bars.value(1), 0f)
        bars.advance(0.25f)
        assertEquals(60f, bars.value(1), 0f)
        bars.retarget(intArrayOf(40, 40), null, true)
        assertEquals(20f, bars.value(0), 0f)
        bars.advance(1f)
        assertEquals(40f, bars.value(0), 0f)
        bars.retarget(intArrayOf(2, 78), null, false)
        assertEquals(78f, bars.value(1), 0f)
    }

    @Test fun axisMovementUsesReprojectedCounts() {
        val bars = HistogramBars(intArrayOf(80, 0))
        bars.retarget(intArrayOf(0, 60), intArrayOf(0, 80), true)
        bars.advance(0.5f)
        assertEquals(0f, bars.value(0), 0f)
        assertEquals(70f, bars.value(1), 0f)
        assertEquals(70f, bars.peak(), 0f)
    }
}
