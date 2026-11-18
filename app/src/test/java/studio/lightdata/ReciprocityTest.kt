package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class ReciprocityTest {
    @Test fun phoenixUsesItsOwnPublishedFormulaAndMarksExtrapolatedTimes() {
        val example = reciprocity("Phoenix II", 10.0)
        assertTrue(example.sourceUrl!!.contains("harmanphoto.co.uk"))
        assertEquals(20.4, example.exposureSeconds!!, .05)
        for (stock in listOf("Phoenix II", "PAN 200 · Kentmere", "HP5 PLUS")) {
            assertFalse(reciprocity(stock, 50.0).label.contains("公式外推"))
            val beyond = reciprocity(stock, 51.0)
            assertTrue(beyond.label.contains("公式外推"))
            assertTrue(beyond.exposureSeconds!! > 51.0)
        }
    }

    @Test
    fun hp5UsesManufacturersExampleAndKeepsTheMeteredTime() {
        val result = reciprocity("HP5 PLUS", 10.0)
        assertEquals(ReciprocityStatus.CORRECTED, result.status)
        assertEquals(10.0, result.meteredSeconds, 0.0)
        assertEquals(20.4, result.exposureSeconds!!, .05)
        assertTrue(result.label.contains("20.4"))
        assertTrue(result.sourceUrl!!.contains("ilfordphoto.com"))
        assertEquals(ReciprocityStatus.UNCHANGED, reciprocity("HP5 PLUS", 1.0).status)
        assertEquals(ReciprocityStatus.CORRECTED, reciprocity("HP5 PLUS", 1.01).status)
    }

    @Test
    fun portraUsesNetworkEstimateOnlyBeyondPublishedRange() {
        for (t in listOf(.0001, .2, 1.0)) {
            val result = reciprocity("PORTRA 400", t)
            assertEquals(ReciprocityStatus.UNCHANGED, result.status)
            assertEquals(t, result.exposureSeconds!!, 0.0)
        }
        assertNull(reciprocity("PORTRA 400", .00009).exposureSeconds)
        for (t in listOf(1.01, 60.0)) {
            val result = reciprocity("PORTRA 400", t)
            assertTrue(result.networkEstimate && result.label.contains("数据来源网络"))
            assertEquals(Math.pow(t,1.3), result.exposureSeconds!!, .00001)
            assertTrue(result.sourceUrl!!.contains("metergeist.com"))
        }
    }

    @Test
    fun e100KeepsItsDiscreteDaylightFilterAdviceOutOfTheShutterCalculation() {
        assertEquals(ReciprocityStatus.UNCHANGED, reciprocity("EKTACHROME E100", 10.0).status)
        for (t in listOf(10.01,119.0,121.0)) assertTrue(reciprocity("EKTACHROME E100",t).networkEstimate)
        for (t in listOf(120.0)) {
            val result = reciprocity("EKTACHROME E100", t)
            assertEquals(ReciprocityStatus.UNPUBLISHED, result.status)
            assertNull(result.exposureSeconds)
            assertEquals(t == 120.0, result.label.contains("CC10R"))
        }
    }

    @Test fun preservesOnlineChartPointsAndManufacturerPrecedence() {
        assertEquals(30.0,reciprocity("PORTRA 800",10.0).exposureSeconds!!,.0001)
        assertTrue(reciprocity("PORTRA 800",100.0).label.contains("公式外推"))
        assertFalse(reciprocity("PROVIA 100F",128.0).networkEstimate)
        assertTrue(reciprocity("VELVIA 50",4.0).label.contains("滤镜"))
        assertNull(reciprocity("PORTRA 160VC",10.0).exposureSeconds)
    }

    @Test
    fun customFilmAndInvalidInputsNeverProduceACompensatedShutter() {
        val custom = reciprocity("自定义胶片", 60.0)
        assertNull(custom.exposureSeconds)
        assertNull(custom.sourceUrl)
        for (t in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            assertNull(reciprocity("HP5 PLUS", t).exposureSeconds)
        }
    }
}
