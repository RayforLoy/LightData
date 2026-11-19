package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class EquivalentFocalTest {
    @Test fun squareFrameHasThreeDistinctEquivalentLengths() {
        val format = Format("6×6", 56.0, 56.0)
        assertEquals(43.70588, format.equivalentFocal(80.0, FocalAxis.DIAGONAL), .01)
        assertEquals(51.42857, format.equivalentFocal(80.0, FocalAxis.WIDTH), .001)
        assertEquals(34.28571, format.equivalentFocal(80.0, FocalAxis.HEIGHT), .001)
    }

    @Test fun fullFrameDetectionUsesDimensionsIncludingCustomPortraitFormats() {
        assertTrue(formats.first { it.name == "135" }.isFullFrame)
        assertTrue(Format("自定义", 24.0, 36.0).isFullFrame)
        val portrait = Format("自定义", 12.0, 18.0)
        assertFalse(portrait.isFullFrame)
        for (axis in FocalAxis.entries) assertEquals(70.0, portrait.equivalentFocal(35.0, axis), .00001)
    }
}
