package studio.lightdata

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.*

internal class GlassPalette(light: Boolean) {
    val text = if (light) Color(0xff141719) else Color(0xfffaf9f3)
    val secondary = if (light) Color(0xff34393a) else Color(0xffdeded9)
    val backdrop = if (light) Color(0xfff5f3ef) else Color(0xff171819)
    val grid = text.copy(alpha=.32f)
    val accent = if (light) Color(0xff7b4300) else amber
    val under = if (light) Color(0xff005c36) else green
    val over = if (light) Color(0xff98251e) else Color(0xffff9388)
    val button = backdrop.copy(alpha=.4f)
}

internal val LocalGlassPalette = staticCompositionLocalOf<GlassPalette?> { null }

// Read a postage-stamp copy of the displayed GL surface. The backdrop and contrast decision
// then see exactly the selected LUT, exposure and crop, without a second camera or GL renderer.
private class GroundGlass(val thumbnail: Bitmap, val luminance: IntArray)

@Composable
private fun glassImage(a: MainActivity): GroundGlass? {
    var image by remember { mutableStateOf<GroundGlass?>(null) }
    LaunchedEffect(a) {
        val handler = Handler(Looper.getMainLooper())
        val size = 32
        val copy = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        while (true) {
            val view = a.previewView.get()
            if (view != null && view.isAttachedToWindow && view.holder.surface.isValid) {
                val status = suspendCancellableCoroutine { c ->
                    try {
                        PixelCopy.request(view, copy, { if (c.isActive) c.resume(it) }, handler)
                    } catch (_: IllegalArgumentException) { c.resume(PixelCopy.ERROR_SOURCE_INVALID) }
                }
                if (status == PixelCopy.SUCCESS) {
                    copy.getPixels(pixels, 0, size, 0, 0, size, size)
                    val blurred = IntArray(pixels.size)
                    for (y in 0 until size) for (x in 0 until size) {
                        var r = 0; var g = 0; var b = 0
                        for (dy in -1..1) for (dx in -1..1) {
                            val p = pixels[(y + dy).coerceIn(0,size-1)*size + (x + dx).coerceIn(0,size-1)]
                            r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255
                        }
                        blurred[y*size+x] = android.graphics.Color.rgb(r/9, g/9, b/9)
                    }
                    image = GroundGlass(Bitmap.createBitmap(blurred,size,size,Bitmap.Config.ARGB_8888),
                        IntArray(pixels.size) { luma(blurred[it]) })
                }
            }
            delay(400)
        }
    }
    return image
}

@Composable
private fun GlassBackdrop(image: GroundGlass?, frame: Rect) {
    if (image == null) return
    val paint = remember(image, frame) {
        val bitmap = image.thumbnail
        val texture = BitmapShader(bitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        texture.setLocalMatrix(Matrix().apply { setRectToRect(RectF(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat()),
            RectF(frame.left, frame.top, frame.right, frame.bottom), Matrix.ScaleToFit.FILL) })
        Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = texture }
    }
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
    }
}

private fun reflection(x: Float): Float {
    val phase = x - 2 * floor(x / 2)
    return if (phase <= 1) phase else 2 - phase
}

private fun brightness(image: GroundGlass?, background: Rect, panel: Rect): Int {
    if (image == null || background.isEmpty) return 0
    var sum = 0
    val size = image.thumbnail.width
    for (y in 0..4) for (x in 0..4) {
        val u = reflection((panel.left + panel.width*(x+.5f)/5 - background.left)/background.width)
        val v = reflection((panel.top + panel.height*(y+.5f)/5 - background.top)/background.height)
        sum += image.luminance[(v*size).toInt().coerceIn(0,size-1)*size + (u*size).toInt().coerceIn(0,size-1)]
    }
    return sum/25
}

@Composable
private fun GlassPanel(brightness: Int, content: @Composable () -> Unit) {
    var light by remember { mutableStateOf(brightness > 135) }
    LaunchedEffect(brightness) { if (brightness > 148) light = true else if (brightness < 119) light = false }
    val palette = remember(light) { GlassPalette(light) }
    val scope = rememberCoroutineScope()
    var fadeJob by remember { mutableStateOf<Job?>(null) }
    var solid by remember { mutableStateOf(false) }
    val opacity by animateFloatAsState(if (solid) 1f else 0f, tween(if (solid) 0 else 550), label = "测光面板背景")
    Box(Modifier.fillMaxSize().background(palette.backdrop.copy(alpha=opacity)).pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            solid = true
            fadeJob?.cancel()
            do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
            fadeJob = scope.launch { delay(3000); solid = false }
        }
    }) { CompositionLocalProvider(LocalGlassPalette provides palette) { content() } }
}

@Composable
private fun GlassButton(label:String, action:()->Unit) {
    Box(Modifier.background(Color.Black.copy(alpha=.76f)).clickable(onClick=action).padding(horizontal=9.dp,vertical=7.dp)) {
        BasicText(label,style=TextStyle(fontSize=12.sp,color=Color.White))
    }
}

@Composable
internal fun FullFinderScreen(
    a: MainActivity, landscape: Boolean, finder: @Composable ()->Unit, instruments: @Composable ()->Unit,
    histogram: @Composable ()->Unit, meter: @Composable ()->Unit,
) {
    val controller = a.fullFinder
    DisposableEffect(a) {
        val decor = a.window.decorView
        val bars = WindowCompat.getInsetsController(a.window, decor)
        val initial = ViewCompat.getRootWindowInsets(decor)
        val status = WindowInsetsCompat.Type.statusBars()
        val navigation = WindowInsetsCompat.Type.navigationBars()
        val behavior = bars.systemBarsBehavior
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars.systemBarsBehavior = behavior
            if (initial?.isVisible(status) != false) bars.show(status)
            if (initial?.isVisible(navigation) != false) bars.show(navigation)
        }
    }
    val image = glassImage(a)
    var current by remember(landscape) { mutableStateOf(controller.current(landscape)) }
    var editing by remember(landscape) { mutableStateOf<GlassPart?>(null) }
    var controls by remember { mutableStateOf(false) }
    BackHandler(!controls && !a.settings && a.toolsPage.isEmpty() && !a.frozen && !a.scanActive) {
        if (editing != null) editing = null else controller.toggle()
    }
    if (controls) Dialog(onDismissRequest = { controls = false }) {
        Column(Modifier.background(Color(0xff191a1c)).verticalScroll(rememberScrollState()).padding(6.dp)) {
            instruments()
            GlassButton("返回取景") { controls = false }
        }
    }
    LaunchedEffect(a.toolsPage,a.settings,a.frozen,a.scanActive) { controls = false }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val aspect = a.sample?.frame?.let { it.w.toFloat()/it.h }
            ?: if (landscape) max(a.format.w,a.format.h).div(min(a.format.w,a.format.h)).toFloat()
            else min(a.format.w,a.format.h).div(max(a.format.w,a.format.h)).toFloat()
        val distance = a.parallax.enabled
        val (layout, bounds) = glassBounds(w,h,density.density,aspect,landscape,distance,current)
        fun place(r:Rect) = with(density) {
            Modifier.offset { IntOffset(r.left.roundToInt(),r.top.roundToInt()) }.size(r.width.toDp(),r.height.toDp())
        }
        fun use(next:GlassLayout?) { current = next; controller.use(landscape, next) }
        fun save() {
            val input = EditText(a).apply { isSingleLine = true; hint = "预设名称" }
            android.app.AlertDialog.Builder(a).setTitle("保存当前" + if(landscape) "横屏布局" else "竖屏布局")
                .setView(input).setNegativeButton("取消",null).setPositiveButton("保存") { _,_ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) a.notifyUser("请输入预设名称") else {
                        fun persist() { controller.save(landscape,name,layout); a.notifyUser("已保存布局「$name」") }
                        if (name in controller.names(landscape)) android.app.AlertDialog.Builder(a)
                            .setMessage("覆盖已有布局「$name」？").setNegativeButton("取消",null)
                            .setPositiveButton("覆盖") { _,_ -> persist() }.show()
                        else persist()
                    }
                }.show()
        }
        fun menu() {
            val names = controller.names(landscape)
            val options = listOf("取景操作","移动取景框","移动直方图","移动测光表","取景框大小","保存布局预设","恢复默认布局","退出全屏") + names.map { "使用预设 · $it" }
            a.choose("全屏取景",options) { i -> when(i) {
                0 -> { editing=null; controls=true }
                in 1..3 -> editing=GlassPart.values()[i-1]
                4 -> {
                    val scales = listOf(.55f,.7f,.85f,1f)
                    a.choose("取景框大小",scales.map { "${(it*100).roundToInt()}%" }) { use(layout.copy(finderScale=scales[it])) }
                }
                5 -> save()
                6 -> { editing=null; use(null) }
                7 -> controller.toggle()
                else -> { editing=null; use(controller.load(landscape,names[i-8])) }
            } }
        }
        GlassBackdrop(image,bounds[0])
        Box(place(bounds[0])) { finder() }
        Box(place(bounds[1])) { GlassPanel(brightness(image,bounds[0],bounds[1]),histogram) }
        Box(place(bounds[2])) {
            GlassPanel(brightness(image,bounds[0],bounds[2])) {
                val foreground = LocalGlassPalette.current!!.text
                if (landscape) Column {
                    if(distance) ParallaxDistanceWheel(a, Modifier.fillMaxWidth().height(44.dp),
                        foreground=foreground, background=Color.Transparent)
                    Box(Modifier.weight(1f)) { meter() }
                } else Row {
                    if(distance) ParallaxDistanceWheel(a, Modifier.fillMaxHeight().width(40.dp), vertical=true,
                        foreground=foreground, background=Color.Transparent)
                    Box(Modifier.weight(1f)) { meter() }
                }
            }
        }
        val part = editing
        if (part != null) {
            val r = bounds[part.ordinal]
            Box(place(r).border(2.dp, amber))
            val latest by rememberUpdatedState(layout)
            Box(Modifier.fillMaxSize().pointerInput(part,w,h,r.width,r.height) {
                var position = latest
                detectDragGestures(onDragStart={position=latest},onDragEnd={controller.use(landscape,position)},
                    onDragCancel={controller.use(landscape,position)}) { change,delta ->
                    change.consume()
                    val xy=position.anchors[part.ordinal]
                    position=position.move(part, xy.x + if(w-r.width>1) delta.x/(w-r.width) else 0f,
                        xy.y + if(h-r.height>1) delta.y/(h-r.height) else 0f)
                    current=position
                }
            })
        }
        Row(Modifier.align(if(landscape) Alignment.TopEnd else Alignment.TopStart)) {
            GlassButton("全屏菜单",::menu)
            if (part!=null) GlassButton("移动${part.label} · 完成") { editing=null }
            else if (a.scanActive || a.sample?.missing == true || a.frozen || a.pendingGroundEv != null)
                GlassButton("取景操作") { controls=true }
        }
    }
}
