package studio.lightdata

import android.media.AudioManager
import android.media.SoundPool
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FeedbackSoundTest {
    @Test fun everyBundledCueDecodesForImmediateSoundPoolPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cues = FeedbackCue.entries
        val complete = CountDownLatch(cues.size)
        val statuses = ConcurrentHashMap<Int,Int>()
        val attributes = feedbackAudioAttributes()
        assertEquals(AudioManager.STREAM_MUSIC, attributes.volumeControlStream)
        val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
            attributes
        ).build()
        try {
            pool.setOnLoadCompleteListener { _, id, status ->
                statuses[id] = status
                complete.countDown()
            }
            val ids = cues.map { pool.load(context,it.raw,1) }
            assertEquals(cues.size,ids.toSet().size)
            assertTrue(complete.await(5,TimeUnit.SECONDS))
            for (id in ids) {
                assertEquals(0,statuses[id])
                assertTrue(pool.play(id,1f,1f,Int.MAX_VALUE,0,1f) != 0)
                android.os.SystemClock.sleep(400)
            }
        } finally {
            pool.release()
        }
    }
}
