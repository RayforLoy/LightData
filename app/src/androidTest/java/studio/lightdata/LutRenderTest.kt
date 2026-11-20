package studio.lightdata

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class LutRenderTest {
    @Test fun cameraUsesItsNegotiatedInputForMeterAndFrozenFrame() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName,android.Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use { run ->
            var input: Sample? = null
            var compact = false
            val until = android.os.SystemClock.elapsedRealtime()+10000
            do {
                run.onActivity { a ->
                    val s=a.sample ?: return@onActivity
                    val lens=a.current ?: return@onActivity
                    val size = android.util.Size(s.captureWidth, s.captureHeight)
                    compact = size != lens.size
                    assertTrue(size == lens.size || size == lens.compactSize)
                    a.toggleFreeze()
                    input=a.sample
                }
                if (input == null) Thread.sleep(100)
            } while(input == null && android.os.SystemClock.elapsedRealtime()<until)
            val frozen=requireNotNull(input)
            android.util.Log.i("LightDataQA","YUV ${frozen.captureWidth}x${frozen.captureHeight}; crop ${frozen.frame.w}x${frozen.frame.h}; compact fallback=$compact")
            assertEquals(frozen.frame.pixels.count { it ushr 24 != 0 },frozen.hist.sum())
            Thread.sleep(300)
            run.onActivity { assertSame(frozen,it.sample); assertTrue(it.frozen) }
        }
    }

    @Test fun packagedCatalogLoadsSelectedFilmVariantsAndClearsForCustomFilm() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = LutAssets(app.assets)
        val catalog = assets.entries()
        assertTrue(catalog.isNotEmpty())
        catalog.distinctBy { it.file }.forEach { entry ->
            assertEquals(CUBE_SIZE*CUBE_SIZE*CUBE_SIZE*3, assets.load(entry).pixels.remaining())
        }
        val byFilm = catalog.groupBy { it.film }
        val stock = filmRecords.filter { it.film.name in byFilm }.maxBy { byFilm.getValue(it.film.name).size }
        val entries = byFilm.getValue(stock.film.name)
        val prefs = app.getSharedPreferences("meter", android.content.Context.MODE_PRIVATE)
        val keys = listOf("film","customPresetId","filmIsoLocked","iso")
        val original = prefs.all.filterKeys { it in keys }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.selectFilm(stock)
                    it.useLut = true
                    it.preview = true
                }
                fun await(test: (MainActivity) -> Boolean) {
                    val until = android.os.SystemClock.elapsedRealtime()+8000
                    do {
                        var matched = false
                        scenario.onActivity { matched = test(it) }
                        if (matched) return
                        Thread.sleep(80)
                    } while (android.os.SystemClock.elapsedRealtime()<until)
                    fail("LUT selection did not settle")
                }
                for (entry in listOf(entries.first(),entries.last()).distinct()) {
                    scenario.onActivity { it.chosenLuts[stock.film.name] = entry.file }
                    await { it.activeAtlas?.entry?.file == entry.file && it.lutStatus == "LUT 预览" }
                }
                scenario.onActivity {
                    val unavailable = entries.first().copy(file="missing-asset-for-test")
                    it.lutEntries = it.lutEntries + unavailable
                    it.chosenLuts[stock.film.name] = unavailable.file
                }
                await { it.activeAtlas == null && it.lutStatus == "LUT 加载失败 · 使用亮度模拟" }
                scenario.onActivity { it.selectPreset(CustomPreset.create(stock.film.name,false,12.0,4.0,100.0)) }
                await { it.activeAtlas == null && it.lutStatus == "无对应 LUT · 使用亮度模拟" }
            }
        } finally {
            val edit = prefs.edit()
            keys.forEach { edit.remove(it) }
            original.forEach { (key,value) -> when(value) {
                is String -> edit.putString(key,value)
                is Boolean -> edit.putBoolean(key,value)
            } }
            edit.commit()
        }
    }

    @Test fun fragmentShaderSamplesCubeAndRetainsFilmLuminanceAtBothResolutions() {
        val frame = Frame(80,60,IntArray(4800) { 0xff787878.toInt() },0.0,"test")
        val film = customFilm(true,14.0,6.0)
        val cube = ByteArray(33*33*33*3) { i ->
            // Analytical test input: a constant chromaticity across the grid.
            when (i%3) { 0 -> 200; 1 -> 80; else -> 40 }.toByte()
        }
        val atlas = LutAtlas(LutEntry("test","test","derived","test"),packCube(cube))
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var preview: Preview
            var look = Look(frame,defaultResponse,0.0,film,true,false,0)
            activity.onActivity { a ->
                preview = Preview(a)
                a.setContentView(FrameLayout(a).apply { addView(preview,FrameLayout.LayoutParams(240,180)) })
                preview.show(look, .5)
            }
            fun waitFor(width: Int, stripe: Boolean = false, u: Double = .5, v: Double = .5, check: (Int) -> Boolean) {
                var rgb = 0
                val stop = android.os.SystemClock.elapsedRealtime()+6500
                while (android.os.SystemClock.elapsedRealtime()<stop) {
                    Thread.sleep(100)
                    if (preview.renderedSceneWidth != width || !preview.holder.surface.isValid) continue
                    val bmp = Bitmap.createBitmap(width, width*3/4, Bitmap.Config.ARGB_8888)
                    var code = PixelCopy.ERROR_UNKNOWN
                    val done=CountDownLatch(1)
                    PixelCopy.request(preview,bmp,{ code=it; done.countDown() },Handler(Looper.getMainLooper()))
                    if (!done.await(2,TimeUnit.SECONDS) || code!=PixelCopy.SUCCESS) continue
                    rgb=bmp.getPixel((bmp.width*u).toInt(),(bmp.height*v).toInt())
                    if (check(rgb)) return
                    if (stripe && (0 until 12).any { check(bmp.getPixel(it,bmp.height/2)) }) return
                }
                fail("Expected scene width ${width}, actual ${preview.renderedSceneWidth}, surface ${preview.holder.surfaceFrame}, last RGB ${Integer.toHexString(rgb)}")
            }
            waitFor(40) { abs((it and 255)-120)<=2 }
            look=look.copy(atlas=atlas)
            activity.onActivity { preview.show(look,1.0) }
            waitFor(80) { color ->
                (color ushr 16 and 255) > (color and 255) + 80 && abs(luma(color)-120)<=2
            }
            activity.onActivity { preview.show(look.copy(delta=1.0),1.0) }
            val brighter=(255*linearToSrgb(2*srgbToLinear(120/255.0))).roundToInt()
            waitFor(80) { color -> abs(luma(color)-brighter)<=2 }
            activity.onActivity { preview.show(look.copy(atlas=null),.25) }
            waitFor(20) { abs((it and 255)-120)<=2 }
            for (limited in listOf(false,true)) for (bt709 in listOf(false,true)) for (transfer in listOf(false,true)) {
                val encoding=YuvEncoding(limited,bt709,transfer)
                val planes=listOf(
                    Rig.Plane(ByteArray(84*60) { 112 },84,1),
                    Rig.Plane(ByteArray(84*30) { if (it%2==0) 93 else 9 },84,2),
                    Rig.Plane(ByteArray(44*30) { 153.toByte() },44,1),
                )
                val live=LiveCapture(Rig.Packet(80,60,planes,encoding),0.0,"test",1,0,Field(1.0,1.0),Field(1.0,1.0))
                val expected=encoding.rgb(112,93,153)
                activity.onActivity { preview.show(look.copy(atlas=null,live=live,preview=false),.5) }
                waitFor(40) { pixel -> listOf(16,8,0).all { shift ->
                    abs((pixel ushr shift and 255) - (expected ushr shift and 255)) <= 2
                } }
            }
            val pattern=listOf(
                Rig.Plane(ByteArray(80*60) { if(it%80<40) 75 else 155.toByte() },80,1),
                Rig.Plane(ByteArray(40*30) { if(it/40<15) 112 else 151.toByte() },40,1),
                Rig.Plane(ByteArray(40*30) { 120 },40,1),
            )
            for(turn in listOf(0,90,180,270)) {
                val live=LiveCapture(Rig.Packet(80,60,pattern,YuvEncoding()),0.0,"test",1,turn,
                    Field(1.0,1.0),Field(.8,.8))
                activity.onActivity { preview.show(look.copy(atlas=null,live=live,preview=false),1.0) }
                val full=live.fullFrame()
                for ((x,y) in listOf(.25 to .25,.75 to .25,.25 to .75,.75 to .75)) {
                    val expected=full.sample(x,y)
                    waitFor(full.w,u=x,v=y) { pixel -> listOf(16,8,0).all { shift ->
                        abs((pixel ushr shift and 255)-(expected ushr shift and 255))<=2
                    } }
                }
            }
            // A monochrome process sensitive to red changes patch brightness even when
            // the neutral and red originals have the same Rec.709 luminance.
            val monochrome = ByteArray(cube.size) { i -> (30+12*(i/3%33)).coerceAtMost(255).toByte() }
            val monoAtlas = LutAtlas(atlas.entry.copy(file="red-sensitive"),packCube(monochrome))
            val narrow = customFilm(true, 2.35, .35)
            val mono = look.copy(atlas=monoAtlas,film=narrow)
            activity.onActivity { preview.show(mono,1.0) }
            waitFor(80) { color -> abs(luma(color)-120)<=2 }
            val red = frame.copy(pixels=IntArray(frame.w*frame.h) { 0xffef0000.toInt() })
            val ceiling = (255*linearToSrgb(narrow.positive(narrow.high))).roundToInt()
            activity.onActivity { preview.show(mono.copy(frame=red),1.0) }
            waitFor(80) { color -> abs(luma(color)-ceiling)<=2 && abs((color and 255)-ceiling)<=2 }
            activity.onActivity { preview.show(mono.copy(frame=red,delta=2.0),1.0) }
            waitFor(80,true) { color -> (color ushr 16 and 255) > (color ushr 8 and 255) + 100 }
            activity.onActivity { preview.show(mono.copy(frame=red,delta=-4.0),1.0) }
            waitFor(80,true) { color -> (color ushr 8 and 255) > (color ushr 16 and 255) + 100 }
        }
    }
}
