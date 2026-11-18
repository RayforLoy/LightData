package studio.lightdata

import org.junit.Assert.*
import org.junit.Test

class ScanHistoryTest {
    @Test fun rejectedFramesDoNotEraseTheBeginningOfTheScan() {
        val history = ScanHistory<Int>(1000)
        repeat(93) { assertTrue(history.add(it, 5, it == 0)) }
        val snapshot = history.snapshot()
        assertEquals((0..92).toList(), snapshot.shots)
        assertTrue(snapshot.complete)
        assertEquals(93, snapshot.attempts)
    }
    @Test fun budgetKeepsContiguousPrefixAndReportsLostAcceptedFrames() {
        val history = ScanHistory<Int>(10)
        history.add(0, 5, true)
        history.add(1, 5, false)
        assertFalse(history.add(2, 1, false))
        assertFalse(history.add(3, 0, true))
        val snapshot = history.snapshot()
        assertEquals(listOf(0,1), snapshot.shots)
        assertEquals(1, snapshot.droppedAccepted)
        assertEquals(1, snapshot.droppedRejected)
        assertFalse(snapshot.complete)
        assertEquals(10L, snapshot.bytes)
        history.clear()
        assertTrue(history.snapshot().complete)
        assertEquals(listOf(0,1), snapshot.shots)
        assertTrue(history.add(4,10,true))
    }
}
