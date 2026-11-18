package studio.lightdata

import android.media.MediaPlayer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FinderToolsTest {
    @Test fun referenceFrameAndPoseSurviveArchiveReopen() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(ctx.cacheDir,"shot-test-"+System.nanoTime())
        try {
            val format=Format("4×5",96.0,120.0)
            val frame=Frame(3,4,IntArray(12) { 0xff455566.toInt() },3.0,"test")
            val axes=floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
            FramingArchive(dir).save(frame,format,150.0,axes,levelReading(axes))
            val shot=FramingArchive(dir).list().single()
            assertEquals(format,shot.format)
            assertEquals(96.0,shot.horizontalMm,0.0)
            assertEquals(150.0,shot.focal,0.0)
            assertArrayEquals(axes,shot.axes,0f)
            assertEquals(-Math.PI/2,shot.pitch!!,.001)
            val image=shot.bitmap()
            assertEquals(0xff455566.toInt(),image.getPixel(1,2))
            image.recycle()
        } finally { dir.listFiles()?.forEach {it.delete()}; dir.delete() }
    }

    @Test fun packagedFocusRecordingDecodes() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val player=MediaPlayer.create(ctx,R.raw.sony_focus)
        assertNotNull(player)
        try { assertTrue(player.duration in 200..500) } finally { player.release() }
    }
}
