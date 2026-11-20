package studio.lightdata

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class InstantStocksTest {
    @Test fun selectorExposesFiveFilmsWithPackSpeedsAndSources() {
        val speeds = mapOf("instax-mini" to 800,"instax-square" to 800,"instax-wide" to 800,
            "tip-color600" to 640,"tip-colorsx70" to 160)
        val selected = filmRecords.filter { it.document in speeds }
        assertEquals(speeds,selected.associate { it.document to it.iso })
        selected.forEach { assertEquals(filmSourceUrl(it.document),it.film.url) }
    }

    @Test fun printedInstaxTraceHasReflectanceGrayAndConservativeLimits() {
        val f = instantStocks.first { it.document=="instax-mini" }.film
        val w = instantStocks.first { it.document=="instax-wide" }.film
        assertEquals(f.dots,w.dots)
        assertTrue(f.dots.zipWithNext().all { (a,b) -> a.second >= b.second && a.first < b.first })
        assertEquals(2.34,f.dots.first().second,.03)
        assertEquals(.21,f.dots.last().second,.015)
        assertEquals(.18,10.0.pow(f.dots.last().second-f.density(0.0)),.003)
        assertEquals(.10,f.dots.first().second-f.density(f.low),.003)
        assertEquals(.10,f.density(f.high)-f.dots.last().second,.003)
    }

    @Test fun recordsIdentifyEstimatedResponses() {
        for (id in listOf("instax-square","tip-color600","tip-colorsx70")) {
            val f=instantStocks.first { it.document==id }.film
            assertTrue(f.info.contains("近似"))
            assertTrue(f.positive(-1.0)<f.positive(1.0))
        }
    }
}
