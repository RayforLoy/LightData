package studio.lightdata

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SpatialAnchorTest {
    private val flat = GatePose(floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f), FinderGate(Field(.8,.6),.07,.03))
    private fun frame(shift: Int = 0, covered: Boolean = false): Frame {
        val w=200; val h=160
        val pixels=IntArray(w*h) { i ->
            val x=i%w-shift; val y=i/w
            val v=if (covered) 95 else ((sin(x*.34+y*.14)*42 + cos(x*.1-y*.46)*38 +
                sin(x*.19+y*.33)*31+128).roundToInt()).coerceIn(0,255)
            0xff000000.toInt() or (v*0x10101)
        }
        return Frame(w,h,pixels,10.0,"test")
    }
    @Test fun tapProjectionRoundTripsAndRejectsBehindCamera() {
        val ray=flat.ray(.23,.74)
        val p=flat.project(ray)!!
        assertEquals(.23,p.x,1e-8); assertEquals(.74,p.y,1e-8)
        val reversed=flat.copy(axes=floatArrayOf(-1f,0f,0f,0f,1f,0f,0f,0f,-1f))
        assertNull(reversed.project(ray))
    }
    @Test fun followsTranslationThenHoldsOnOcclusionAndRecoversOriginalTexture() {
        val f=frame()
        val t=SpatialAnchor.create(3,f,flat,.5,.5)!!
        val first=TrackedSpot(Spot(3,.5,.5,10.0))
        val moved=t.update(frame(4),flat,first,defaultResponse,20,false){_,_->null}
        assertFalse(moved.stale); assertEquals(.52,moved.spot.x,.009)
        val hidden=t.update(frame(covered=true),flat,moved,defaultResponse,220,false){_,_->null}
        assertTrue(hidden.stale); assertFalse(hidden.moveBack); assertEquals(moved.spot.ev,hidden.spot.ev,0.0)
        val back=t.update(f,flat,hidden,defaultResponse,420,false){_,_->null}
        assertFalse(back.stale); assertEquals(3,back.spot.id)
    }
    @Test fun returningFromOutsideTheGateRequiresVisualConfirmation() {
        val f = frame()
        val t = SpatialAnchor.create(2,f,flat,.5,.5)!!
        val initial = TrackedSpot(Spot(2,.5,.5,7.0))
        val turnedAway = flat.copy(axes=floatArrayOf(-1f,0f,0f,0f,1f,0f,0f,0f,-1f))
        var v = t.update(f,turnedAway,initial,defaultResponse,100,false){_,_->null}
        assertFalse(v.inView); assertTrue(v.stale); assertEquals(7.0,v.spot.ev,0.0)
        v = t.update(frame(covered=true),flat,v,defaultResponse,400,false){_,_->null}
        assertTrue(v.stale); assertEquals(7.0,v.spot.ev,0.0)
        v = t.update(f,flat,v,defaultResponse,600,false){_,_->null}
        assertFalse(v.stale); assertTrue(v.inView); assertEquals(f.spot(.5,.5,defaultResponse),v.spot.ev,.12)
    }

    @Test fun lostPointFadesOnlyAfterDisplacementAndCanRecoverBeforeExpiry() {
        val f=frame(); val hidden=frame(covered=true)
        val t=SpatialAnchor.create(0,f,flat,.5,.5)!!
        val ref=TrackedSpot(Spot(0,.5,.5,9.0),kelvin=4200)
        val away=t.update(hidden,flat,ref,defaultResponse,10,true){_,_->null}
        assertTrue(away.moveBack); assertEquals(4200,away.kelvin)
        val fading=t.update(hidden,flat,away,defaultResponse,3010,true){_,_->null}
        assertEquals(.5f,fading.alpha,.01f)
        val back=t.update(f,flat,fading,defaultResponse,3100,false){_,_->5000}
        assertFalse(back.stale); assertEquals(5000,back.kelvin); assertEquals(1f,back.alpha,0f)
        t.update(hidden,flat,back,defaultResponse,4000,true){_,_->null}
        val expired=t.update(hidden,flat,back,defaultResponse,10001,true){_,_->null}
        assertEquals(0f,expired.alpha,0f)
    }
}
