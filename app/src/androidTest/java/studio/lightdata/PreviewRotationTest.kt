package studio.lightdata

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PreviewRotationTest {
    @Test fun reducedAndFullResolutionFillRotatedSurfaceAndAllFourGateCorners() {
        val gray = listOf(65,105,145,185)
        val film = customFilm(true, 20.0, 10.0)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var view: Preview
            activity.onActivity { a ->
                view = Preview(a)
                a.setContentView(FrameLayout(a).apply { addView(view, FrameLayout.LayoutParams(-1,-1)) })
            }
            for (landscape in listOf(false,true,false)) {
                activity.onActivity { it.requestedOrientation = if (landscape)
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                for (glass in listOf(true,false)) {
                    val w = if (landscape) 160 else 96
                    val h = if (landscape) 96 else 160
                    val frame = Frame(w,h,IntArray(w*h) { i ->
                        val code = gray[(if (i%w < w/2) 0 else 1) + (if (i/w < h/2) 0 else 2)]
                        android.graphics.Color.rgb(code,code,code)
                    },0.0,"rotation")
                    val gate = if (!glass) null else if (landscape) GlassFrame(0f,0f,.82f,.96f)
                        else GlassFrame(.15f,0f,1f,.85f)
                    activity.onActivity {
                        view.setGlassFrame(gate)
                        view.show(Look(frame,defaultResponse,0.0,film,false,false,0), if(glass) .5 else 1.0)
                    }
                    val until = SystemClock.elapsedRealtime() + 6500
                    var filled = false
                    val pixels = Bitmap.createBitmap(120,120,Bitmap.Config.ARGB_8888)
                    while(!filled && SystemClock.elapsedRealtime() < until) {
                        Thread.sleep(80)
                        if ((view.width > view.height) != landscape || view.width == 0 || view.height == 0 ||
                            !view.holder.surface.isValid || view.holder.surfaceFrame.width() != view.width ||
                            view.holder.surfaceFrame.height() != view.height) continue
                        val done = CountDownLatch(1)
                        var copied = false
                        PixelCopy.request(view,pixels,{ copied=it == PixelCopy.SUCCESS; done.countDown() },Handler(Looper.getMainLooper()))
                        if (!done.await(1,TimeUnit.SECONDS) || !copied) continue
                        filled = gray.withIndex().all { (i,code) ->
                            val x = (gate?.left ?: 0f) + (gate?.width ?: 1f) * if (i%2==0) .04f else .96f
                            val y = (gate?.top ?: 0f) + (gate?.height ?: 1f) * if (i<2) .04f else .96f
                            abs((pixels.getPixel((120*x).toInt(),(120*y).toInt()) and 255)-code) <= 2
                        }
                    }
                    assertTrue("Frame didn't fill gate (landscape=$landscape, glass=$glass)", filled)
                }
            }
        }
    }
}
