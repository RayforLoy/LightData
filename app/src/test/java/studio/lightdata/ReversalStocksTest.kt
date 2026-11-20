package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class ReversalStocksTest {
    @Test fun experimentalIndicesAndCurvesRepresentTheSelectedReversalProcess() {
        assertEquals(listOf(5, 64), experimentalReversalStocks.map { it.iso })
        for (entry in experimentalReversalStocks) {
            val film = entry.film
            assertTrue(entry.experimentalEi)
            assertTrue(film.url.startsWith("https://"))
            assertEquals(film.url, filmSourceUrl(entry.document))
            assertTrue(film.low < 0 && film.high > 0)
            assertTrue(film.dots.zipWithNext().all { (a,b) -> a.first < b.first && a.second > b.second })
            assertTrue(film.positive(1.0) > film.positive(0.0))
            assertTrue(film.positive(0.0) > film.positive(-1.0))
        }
    }
}
