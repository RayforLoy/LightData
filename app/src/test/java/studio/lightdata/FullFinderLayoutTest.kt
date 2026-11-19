package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class FullFinderLayoutTest {
    @Test fun portraitAndLandscapePlaceFrameAndInstrumentsInOppositeCorners() {
        val (_, portrait) = glassBounds(800f,1000f,1.75f,.5f,false,true,null)
        val (frame, histogram, meter) = portrait
        assertEquals(300f, frame.left, .001f)
        assertEquals(0f, frame.top, .001f)
        assertEquals(800f, frame.right, .001f)
        assertEquals(0f, histogram.left, .001f)
        assertEquals(1000f, histogram.bottom, .001f)
        assertEquals(histogram.right, meter.left, .001f)

        val (_, landscape) = glassBounds(1000f,800f,1.75f,2f,true,false,null)
        assertEquals(0f, landscape[0].top, .001f)
        assertEquals(0f, landscape[0].left, .001f)
        assertEquals(500f, landscape[0].height, .001f)
        assertEquals(1000f, landscape[2].right, .001f)
        assertEquals(800f, landscape[2].bottom, .001f)
        assertEquals(landscape[1].bottom, landscape[2].top, .001f)
    }

    @Test fun customPositionsScaleToNewWindowAndKeepAllThreeComponentsReachable() {
        val (initial, _) = glassBounds(1080f,2400f,2.75f,.8f,false,false,null)
        val layout = initial.copy(finderScale=.7f)
            .move(GlassPart.FINDER,-3f,4f).move(GlassPart.HISTOGRAM,.3f,.1f).move(GlassPart.METER,2f,-1f)
        for ((w,h) in listOf(660f to 880f, 2400f to 2500f, 450f to 1050f)) {
            val (_, rects) = glassBounds(w,h,2f,.8f,false,true,layout)
            assertEquals(0f, rects[0].left,.001f)
            assertEquals(h, rects[0].bottom,.001f)
            assertEquals(w, rects[2].right,.001f)
            assertEquals(0f, rects[2].top,.001f)
            assertEquals(.8f,rects[0].width/rects[0].height,.001f)
            rects.forEach { assertTrue(it.left >= -.001f && it.top >= -.001f && it.right <= w+.001f && it.bottom <= h+.001f) }
        }
    }
}
