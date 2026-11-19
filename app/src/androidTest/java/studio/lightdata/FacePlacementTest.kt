package studio.lightdata

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FacePlacementTest {
    @Test fun facePlacementTracksMeterSourceAndPreservesManualExposure() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val a = inst.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        inst.runOnMainSync {
            val wasFace = a.faceMeterEnabled
            val oldEnabled = a.skinLiftEnabled
            val oldEv = a.skinLiftEv
            val oldComp = a.compensationEv
            try {
                if (!wasFace) a.toggleFaceMeter()
                a.configureSkinLift(true, 1.0)
                val f = Frame(32,32,IntArray(1024) { 0xff808080.toInt() },10.0,"test",
                    faces=listOf(FaceBox(.1,.1,.9,.9)))
                val scene = Sample(f,f.distribution(),false,null)
                a.sample = scene
                a.mode = "A"
                a.manual = Exposure(1.0/125,4.0,100.0)
                a.adjustCompensation(0.0)
                val raw = a.reading!!
                val placed = a.exposure
                assertEquals(raw - 1, a.meterMiddleTarget!!, 1e-8)
                a.configureSkinLift(false,1.0)
                assertTrue(abs(placed.ev - a.exposure.ev + 1) < 0.2)
                a.configureSkinLift(true,1.5)
                a.mode = "M"
                assertEquals(a.manual,a.exposure)
                a.meterOnce()
                a.sample = scene.copy(frame=f.copy(faces=emptyList()))
                assertEquals(raw - 1.5,a.meterMiddleTarget!!,1e-8)
                a.single = false
                a.held = null
                assertEquals(a.reading!!,a.meterMiddleTarget!!,1e-8)
            } finally {
                a.mode = "A"
                a.adjustCompensation(oldComp)
                a.configureSkinLift(oldEnabled,oldEv)
                if (!wasFace) a.toggleFaceMeter()
                a.finish()
            }
        }
    }
}
