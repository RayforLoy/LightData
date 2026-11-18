package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class DimMeterWarningTest {
    @Test fun advisoryUsesSceneReadingAndValidUnfilteredPixels() {
        val gray = IntArray(256).apply { this[105] = 100 }
        val dark = IntArray(256).apply { this[16] = 90; this[64] = 10 }
        assertTrue(dimMeterWarning(0.0, gray))
        assertTrue(dimMeterWarning(3.0, dark))
        assertFalse(dimMeterWarning(2.0, gray))
        assertFalse(dimMeterWarning(4.0, dark))
        assertFalse(dimMeterWarning(-2.0, IntArray(256)))
        assertFalse(dimMeterWarning(null, dark))
    }
}
