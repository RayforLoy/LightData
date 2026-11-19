package studio.lightdata

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ReportGestureTest {
    @Test fun doubleTapSavesCurrentMeterAndSingleTapDoesNot() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        inst.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT <= 28) {
            inst.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
            inst.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val a = inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val store = ReportStore(context)
        val before = store.list().map { it.uri }.toSet()
        val wasEnabled = a.reportEnabled
        try {
            inst.runOnMainSync { a.setReportCaptureEnabled(true) }
            val xy = IntArray(2)
            var ready = false
            for (attempt in 1..60) {
                inst.runOnMainSync {
                    val view = a.previewView.get()
                    if (a.reading != null && view != null && view.width > 0) {
                        ready = true
                        view.getLocationOnScreen(xy)
                        xy[0] += view.width/2
                        xy[1] += view.height/3
                    }
                }
                if (ready) break
                SystemClock.sleep(200)
            }
            assertTrue("Expected a metered camera frame",ready)
            fun tap() {
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,xy[0].toFloat(),xy[1].toFloat(),0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    inst.uiAutomation.injectInputEvent(event,true)
                    event.recycle()
                    SystemClock.sleep(20)
                }
            }
            tap()
            SystemClock.sleep(450)
            assertTrue(store.list().all { it.uri in before })
            tap()
            SystemClock.sleep(50)
            tap()
            for (attempt in 1..50) {
                if (store.list().any { it.uri !in before }) break
                SystemClock.sleep(200)
            }
            assertEquals("Double tap should save exactly one JPEG",1,store.list().count { it.uri !in before })
        } finally {
            store.list().filter { it.uri !in before }.forEach(store::delete)
            inst.runOnMainSync { a.setReportCaptureEnabled(wasEnabled); a.finish() }
        }
    }
}
