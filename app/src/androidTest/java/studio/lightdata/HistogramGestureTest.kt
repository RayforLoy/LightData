package studio.lightdata

import android.Manifest
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.ArrayDeque
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistogramGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiAutomation
        get() = instrumentation.uiAutomation

    private val axisPattern get() = Regex(tr("%.1f 至 %.1f EV100，纵轴 %.2f 倍")
        .split(Regex("""%\.\df""")).joinToString("""(-?[\d.]+)""") { Regex.escape(it) })

    private data class Axis(
        val left: Double,
        val right: Double,
        val gain: Double,
        val bounds: Rect,
    ) {
        val span
            get() = right - left
    }

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        device.rootInActiveWindow?.let(queue::add)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next.isVisibleToUser && predicate(next)) return next
            for (i in 0 until next.childCount) next.getChild(i)?.let(queue::add)
        }
        return null
    }

    private fun axes(until: (Axis) -> Boolean = { true }): Axis {
        val end = SystemClock.uptimeMillis() + 6000
        var observed = ""
        do {
            node { it.text?.toString() == tr("继续取景") }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val chart = node { it.contentDescription?.toString() == tr("场景 EV100 直方图，可横向拖动与双指缩放，双击恢复自动") }
            if (chart != null) {
                val state =
                    if (Build.VERSION.SDK_INT >= 30) chart.stateDescription
                    else
                        chart.extras.getCharSequence(
                            "androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY"
                        )
                observed = state?.toString().orEmpty()
                val match = axisPattern.matchEntire(observed)
                if (match != null) {
                    val (left, right, gain) = match.destructured
                    val bounds = Rect().also(chart::getBoundsInScreen)
                    val result = Axis(left.toDouble(), right.toDouble(), gain.toDouble(), bounds)
                    if (until(result)) return result
                }
            }
            SystemClock.sleep(60)
        } while (SystemClock.uptimeMillis() < end)
        error("直方图未达到预期状态。最后一次读到「$observed」")
    }

    private fun touch(downTime: Long, action: Int, vararg points: Pair<Float, Float>, synchronous: Boolean = true) {
        val ids =
            Array(points.size) { id ->
                MotionEvent.PointerProperties().apply {
                    this.id = id
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
        val coords =
            Array(points.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = points[i].first
                    y = points[i].second
                    pressure = 1f
                    size = .02f
                }
            }
        val event =
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                points.size,
                ids,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        try {
            assertTrue("触摸事件未注入", device.injectInputEvent(event, synchronous))
        } finally {
            event.recycle()
        }
        SystemClock.sleep(15)
    }

    private fun pan(bounds: Rect) {
        val down = SystemClock.uptimeMillis()
        fun position(fraction: Float) =
            bounds.left + fraction * bounds.width() to bounds.exactCenterY()
        touch(down, MotionEvent.ACTION_DOWN, position(.22f))
        for (i in 1..8) touch(down, MotionEvent.ACTION_MOVE, position(.22f + .05f * i))
        touch(down, MotionEvent.ACTION_UP, position(.62f))
    }

    private fun stretch(bounds: Rect) {
        val down = SystemClock.uptimeMillis()
        fun position(sign: Int, fraction: Float): Pair<Float, Float> =
            bounds.exactCenterX() + sign * (.15f + fraction * .15f) * bounds.width() to
                bounds.exactCenterY() + sign * (.20f + fraction * .14f) * bounds.height()
        touch(down, MotionEvent.ACTION_DOWN, position(-1, 0f))
        touch(
            down,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            position(-1, 0f),
            position(1, 0f),
        )
        for (i in 1..8) touch(
            down,
            MotionEvent.ACTION_MOVE,
            position(-1, i / 8f),
            position(1, i / 8f),
        )
        touch(
            down,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            position(-1, 1f),
            position(1, 1f),
        )
        touch(down, MotionEvent.ACTION_UP, position(-1, 1f))
    }

    @Test
    fun actualTouchChangesHorizontalAndVerticalAxesAndDoubleTapResets() {
        val app = instrumentation.targetContext
        device.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            axes()
            // Freeze an acquired frame so the auto-centered range has a repeatable reference.
            val deadline = SystemClock.uptimeMillis() + 5000
            var measured = false
            while (!measured && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity {
                    measured = it.sample != null
                    if (measured && !it.frozen) it.toggleFreeze()
                }
                if (!measured) SystemClock.sleep(100)
            }
            val initial = axes()
            pan(initial.bounds)
            val panned = axes { it.left < initial.left - .5 && abs(it.span - initial.span) <= .15 }
            assertEquals(initial.gain, panned.gain, .01)
            stretch(panned.bounds)
            val zoomed = axes { it.span < panned.span * .7 && it.gain > panned.gain * 1.35 }
            val point = zoomed.bounds.exactCenterX() to zoomed.bounds.exactCenterY()
            repeat(2) {
                val t = SystemClock.uptimeMillis()
                // Keep physical tap timing independent of emulator rendering time.
                touch(t, MotionEvent.ACTION_DOWN, point, synchronous = false)
                touch(t, MotionEvent.ACTION_UP, point, synchronous = false)
                if (it == 0) SystemClock.sleep(65)
            }
            val reset = axes {
                abs(it.span - initial.span) <= .15 && abs(it.gain - initial.gain) <= .02
            }
            assertEquals(initial.left, reset.left, .15)
            val report =
                listOf("初始" to initial, "平移" to panned, "双轴缩放" to zoomed, "复位" to reset)
                    .joinToString("；") { (name, a) ->
                        "$name ${a.left}…${a.right} EV100 ×${a.gain}"
                    }
            Log.i("LightDataGestures", report)
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$report\n") },
            )
        }
    }
}
