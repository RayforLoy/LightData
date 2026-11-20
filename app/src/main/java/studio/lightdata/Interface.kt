package studio.lightdata

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ink = Color(0xff101112)
private val panel = Color(0xff191a1c)
private val line = Color(0xff3a3c3e)
private val paper = Color(0xfff0efea)
private val muted = Color(0xffa5a6a3)
internal val amber = Color(0xffffbd59)
private val red = Color(0xffec756a)
internal val green = Color(0xff6dd8a0)
private val spotColors = listOf(Color(0xffffce58), Color(0xff55ccff), Color(0xffff85bb),
    Color(0xff68e3a8), Color(0xffba9dff), Color(0xffff9864))
private fun spotColor(id: Int): Color = spotColors.getOrNull(id - 1)
    ?: Color.hsv(((id * 137.508) % 360).toFloat(), .55f, 1f)

private val mono = FontFamily.Monospace

@Composable
private fun Label(
    s: String,
    sz: Int = 12,
    color: Color = LocalGlassPalette.current?.text ?: paper,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    monoType: Boolean = false,
) {
    InstrumentText(
        s,
        modifier,
        style =
            TextStyle(
                color = color,
                fontSize = sz.sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (monoType) mono else FontFamily.SansSerif,
            ),
    )
}

@Composable
private fun Action(s: String, on: Boolean = false, doIt: () -> Unit) {
    Box(
        Modifier.padding(2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) amber else panel)
            .clickable(onClick = doIt)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Label(s, 12, if (on) ink else paper, bold = true)
    }
}

@Composable
fun Instrument(a: MainActivity) {
    val toolLayout = remember(a) { ToolLayout(a) }
    LaunchedEffect(a.showFps) {
        while (a.showFps) {
            a.updateFps()
            kotlinx.coroutines.delay(500)
        }
    }
    LaunchedEffect(a.useLut) {
        if (a.useLut && a.lutEntries.isEmpty()) {
            val result = withContext(Dispatchers.IO) { runCatching { a.lutAssets.entries() } }
            a.lutEntries = result.getOrDefault(emptyList())
            a.lutCatalogStatus = if (result.isFailure) "LUT 目录读取失败 · 使用亮度模拟" else ""
        }
    }
    val variant = if (a.useLut && a.preview) a.variant else null
    LaunchedEffect(variant) {
        a.activeAtlas = null
        a.failedLut = null
        if (variant != null) {
            val atlas = withContext(Dispatchers.IO) { runCatching { a.lutAssets.load(variant) }.getOrNull() }
            a.activeAtlas = atlas
            if (atlas == null) a.failedLut = variant.file
        }
    }
    BackHandler(a.settings || a.resizing || a.frozen || a.scanActive) {
        when {
            a.settings -> a.settings = false
            a.resizing -> a.resizing = false
            a.frozen -> a.toggleFreeze()
            else -> a.cancelScan()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(ink)) {
        val landscape = maxWidth > maxHeight
        val shortSide = minOf(maxWidth, maxHeight)
        val square = shortSide >= 540.dp && maxOf(maxWidth, maxHeight) <= shortSide * 1.30f
        val sideBySide = if (square) !landscape else landscape
        val prefix = (if (square) "square" else "") + if (landscape) "l" else "p"
        val content = @Composable {
            Panels(a, sideBySide, prefix, if (square) .5f else .6f, if (square) .5f else .46f)
        }
        if (a.fullFinder.enabled) {
            FullFinderScreen(a, landscape,
                finderOverlay = { Finder(a, overlayOnly = true) },
                instruments = { FinderHeader(a); FinderFooter(a); Toolbar(a, false, toolLayout) },
                histogram = { Histogram(a) }, meter = { Meter(a) })
        } else if (landscape) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Toolbar(a, true, toolLayout)
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Toolbar(a, false, toolLayout)
                Box(Modifier.weight(1f)) { content() }
            }
        }
        if (a.toolsPage == "more") FloatingTools(a, landscape, toolLayout)
        else if (a.toolsPage == "toolEditor") ToolEditor(a, toolLayout)
        else if (a.toolsPage.isNotEmpty()) MoreToolsDialog(a, toolLayout)
        if (a.settings)
            Settings(
                a,
                Modifier.align(Alignment.Center).windowInsetsPadding(WindowInsets.safeDrawing)
                    .widthIn(max = 500.dp).fillMaxWidth().padding(12.dp),
            )
    }
}

@Composable
private fun Panels(a: MainActivity, sideBySide: Boolean, prefix: String, defaultP: Float, defaultQ: Float) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val p = a.layout[prefix + "1"] ?: defaultP
        val q = a.layout[prefix + "2"] ?: defaultQ
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        if (sideBySide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(p).fillMaxHeight()) { Finder(a) }
                Split(a, prefix + "1", true, w, defaultP)
                Column(Modifier.weight(1 - p).fillMaxHeight()) {
                    Box(Modifier.weight(q)) { Histogram(a) }
                    if (a.parallax.enabled) ParallaxDistanceWheel(a, Modifier.fillMaxWidth().height(44.dp))
                    Split(a, prefix + "2", false, h, defaultQ)
                    Box(Modifier.weight(1 - q)) { Meter(a) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(p)) { Finder(a) }
                Split(a, prefix + "1", false, h, defaultP)
                Row(Modifier.fillMaxWidth().weight(1 - p)) {
                    Box(Modifier.weight(q)) { Histogram(a) }
                    if (a.parallax.enabled) ParallaxDistanceWheel(a, Modifier.fillMaxHeight().width(42.dp), vertical = true)
                    Split(a, prefix + "2", true, w, defaultQ)
                    Box(Modifier.weight(1 - q)) { Meter(a) }
                }
            }
        }
    }
}

@Composable
private fun Split(a: MainActivity, key: String, vertical: Boolean, span: Float, initial: Float) {
    val width = if (a.resizing) 14.dp else 1.dp
    var m =
        if (vertical) Modifier.fillMaxHeight().width(width)
        else Modifier.fillMaxWidth().height(width)
    if (a.resizing)
        m =
            m.pointerInput(key, span) {
                detectDragGestures { change, delta ->
                    change.consume()
                    val v =
                        ((a.layout[key] ?: initial) + (if (vertical) delta.x else delta.y) / span)
                            .coerceIn(.22f, .78f)
                    a.layout[key] = v
                    a.put(key, v)
                }
            }
    Box(m.background(if (a.resizing) amber else line))
}

@Composable
private fun Toolbar(a: MainActivity, land: Boolean, layout: ToolLayout) {
    @Composable
    fun brand(modifier: Modifier, size: Int) {
        InstrumentText("LightData", modifier, style = TextStyle(color = paper, fontWeight = FontWeight.Bold,
            textAlign = if (land) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start),
            maxLines = 1, softWrap = false,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(4.sp, size.sp, .25.sp))
    }
    @Composable
    fun buttons(slot: Dp) {
        for (item in layout.toolbar) {
            val active = item.active(a)
            ToolButton(item.caption, active, slot, { item.open(a) }) { c -> FinderToolIcon(item, c, active) }
        }
        ToolButton("更多", a.toolsPage.isNotEmpty(), slot, { a.toolsPage = "more" }) { c ->
            Canvas(Modifier.size(19.dp)) {
                for (i in 0..2) drawCircle(c,size.width*.09f,Offset(size.width*(.16f+i*.34f),size.height*.5f))
            }
        }
        ToolButton(if (a.resizing) "完成" else "设置", a.resizing, slot, {
            if (a.resizing) a.resizing = false else a.settings = true
        }) { c ->
            Canvas(Modifier.size(19.dp)) {
                val w = size.width
                for (i in 0..2) {
                    val y = w * (.18f + i * .32f)
                    drawLine(c, Offset(.04f*w,y), Offset(.96f*w,y), 2f)
                    drawCircle(c, w * .105f, Offset(w * if (i == 1) .68f else .32f, y))
                }
            }
        }
    }
    if (land) {
        BoxWithConstraints(Modifier.width(55.dp).fillMaxHeight().padding(vertical = 5.dp)) {
            val minHeight = 24.dp + 48.dp * (layout.toolbar.size + 2)
            val compact = maxHeight < minHeight
            val scroll = if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Column(Modifier.fillMaxSize().then(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                brand(Modifier.fillMaxWidth().padding(horizontal = 2.dp), 16)
                if (!compact) Spacer(Modifier.weight(1f))
                buttons(52.dp)
            }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 5.dp)) {
            val slot = minOf(52.dp, maxWidth * .84f / (layout.toolbar.size + 2))
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                brand(Modifier.weight(1f).padding(end = 3.dp), 20)
                buttons(slot)
            }
        }
    }
}

@Composable
private fun ToolButton(title: String, active: Boolean, width: Dp, click: () -> Unit, icon: @Composable (Color) -> Unit) {
    val color = if (active) amber else paper
    Column(Modifier.width(width).height(48.dp).clickable(role = Role.Button, onClick = click),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        icon(color)
        InstrumentText(title, Modifier.fillMaxWidth(), style = TextStyle(color = color,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center), maxLines = 1, softWrap = false,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(7.sp, 10.sp, .5.sp))
    }
}

@Composable
private fun FinderHeader(a: MainActivity) {
    val s = a.sample
    val f = s?.frame
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), itemVerticalAlignment = Alignment.CenterVertically) {
            Action(a.format.name + " ▾") { if (!a.frozen) a.formatDialog() }
            Action(n(a.focal) + " mm") { if (!a.frozen) a.changeFocal() }
            if (!a.format.isFullFrame) {
                for (axis in FocalAxis.entries) if (axis in a.equivalentAxes) {
                    val mm = a.format.equivalentFocal(a.focal, axis)
                    Label("${axis.label}等效 ${n(round(mm * 10) / 10)} mm", 11, muted,
                        Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }
        }
        Label(when {
            a.frozen -> "● HOLD"
            s?.scan?.coverage == 1.0 -> "● 拼接构图"
            a.scanActive -> "● 扫描采集"
            s?.missing == true -> "○ 待确认中心"
            f == null -> "○ WAIT"
            else -> "● LIVE"
        }, 10, if (a.frozen) amber else muted, monoType = true)
    }
}

@Composable
private fun FinderFooter(a: MainActivity) {
    val s = a.sample
        if (a.spatial.views.any { it.moveBack })
            Label("近处测点视差过大，请移回原位",12,amber,Modifier.padding(horizontal=8.dp))
        if (a.dynamicEnabled && !a.frozen) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=6.dp)) {
                if (a.spatial.views.none { it.spot.id > 0 }) Label("点按画面添加动态测光点",11,muted)
                for (v in a.spatial.views.filter { it.spot.id > 0 }) {
                    val status = if (v.stale) tr(" · 上次读数") else ""
                    Label("#${v.spot.id}  ${n(v.spot.ev)} EV100 · Zone ${n(v.spot.ev-a.effectiveMiddleEv+5)}$status",
                        11,(if (v.stale) muted else spotColor(v.spot.id)).copy(alpha=v.alpha),Modifier.padding(4.dp))
                }
            }
        }
        if (a.pendingGroundEv != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Label("取下手机，对准实景，点选毛玻璃上测量的同一物点", 12, amber, Modifier.weight(1f).padding(horizontal = 6.dp))
            Action("取消比对") { a.cancelGroundComparison() }
        }
        if (a.frozen) {
            if (a.spots.isEmpty()) Label("点按画面添加测光点", 12, amber, Modifier.padding(horizontal = 10.dp))
            else Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                a.spots.forEach { point ->
                    val relative = point.ev - a.effectiveMiddleEv
                    val headroom = a.film.high - relative
                    val margin = if (headroom >= 0) "距上限 " + n(headroom) else "超上限 " + n(-headroom)
                    val label = "#${point.id}  " + n(point.ev) + " EV100 · Zone " + n(relative + 5) + "  ·  " + margin + " EV"
                    Label(label, 11, spotColor(point.id), Modifier.clickable {
                        val zones = listOf("0","I","II","III","IV","V","VI","VII","VIII","IX","X")
                        a.choose("放置 #${point.id} · " + n(point.ev) + " EV100", zones.map { "Zone $it" } + "恢复原测光") {
                            if (it == zones.size) a.clearPlacement() else a.placeSpot(point, it)
                        }
                    }.padding(horizontal = 7.dp, vertical = 6.dp), bold = point.id == a.placedSpotId)
                }
                Label("点标签放置分区",10,muted)
            }
        }
        if (a.scanActive && s?.scan?.lowLight == true && s.scan.coverage < 1 && !a.frozen)
            Label("环境过暗，拼接成功率会下降 · 可补光或放慢转动", 12, amber, Modifier.padding(horizontal = 10.dp))
        if ((s?.missing == true || a.scanActive) && !a.frozen && !a.runningCal)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Action(if (!a.scanActive) "确认中心 · 开始拼接" else "退出拼接", !a.scanActive) { a.toggleScan() }
                val guide = s?.scan
                Label(
                    when {
                        !a.scanActive -> "目标超出镜头视角，请对准画面中心"
                        guide == null -> "正在采集中心画面"
                        guide.homeDirection != null && guide.coverage >= 1 -> "按箭头转回拼接中心"
                        guide.needsOverlap -> "请移回已拍区域，保留重叠"
                        guide.coverage >= 1 -> "已完成 · 转动设备二次构图"
                        guide.settling -> "请放慢转动"
                        else -> "将白圈缓慢移向黄菱形"
                    },
                    11, muted, Modifier.weight(1f),
                )
                if (guide != null && a.scanActive) {
                    Column(horizontalAlignment=Alignment.End) {
                        Label("${(guide.coverage * 100).toInt()}%", 13, amber, monoType=true)
                        Label("${guide.captures} 帧", 10, muted)
                    }
                }
            }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(
                if (a.message.isNotEmpty()) a.message else a.current?.label ?: "等待镜头",
                10,
                if (a.message.isNotEmpty()) amber else muted,
                Modifier.weight(1f),
            )
            Label(if (!a.preview) "取景" else if (a.useLut) a.lutStatus else "亮度模拟", 10, if (a.preview) amber else muted)
        }
}

@Composable
internal fun FinderPreview(a: MainActivity, glass: Rect? = null) {
    val s = a.sample ?: return
    val f = s.frame
    val live = s.live?.let { a.latestLive ?: it }
    val look =
        Look(
            f,
            a.response,
            (live?.ev ?: f.cameraEv) - a.effectiveMiddleEv,
            a.film,
            a.preview,
            a.mode == "M",
            a.style,
            if (a.useLut && a.preview) a.activeAtlas?.takeIf { it.entry.file == a.variant?.file } else null,
            live,
        )
    AndroidView(
        factory = { ctx -> Preview(ctx).apply {
            a.previewView = java.lang.ref.WeakReference(this)
            onCubeUnavailable = { file ->
                if (a.variant?.file == file) {
                    a.failedLut = file
                    a.activeAtlas = null
                }
            }
        } },
        modifier = Modifier.fillMaxSize(),
        update = {
            it.setGlassFrame(glass?.let { rect -> GlassFrame(rect.left, rect.top, rect.right, rect.bottom) })
            it.onRendered = if (a.showFps) a::markRendered else null
            it.show(look, if (a.frozen || s.still) 1.0 else a.previewScale)
        },
    )
}

@Composable
private fun Finder(a: MainActivity, overlayOnly: Boolean = false) {
    val s = a.sample
    val f = s?.frame
    Column(Modifier.fillMaxSize().background(if (overlayOnly) Color.Transparent else Color(0xff0a0b0c))) {
        if (!overlayOnly) FinderHeader(a)
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (f != null) {
                val aspect = f.w.toFloat() / f.h
                val fitted =
                    if (overlayOnly) Modifier.fillMaxSize()
                    else if (maxWidth / maxHeight > aspect) Modifier.fillMaxHeight().aspectRatio(aspect)
                    else Modifier.fillMaxWidth().aspectRatio(aspect)
                Box(fitted) {
                    if (!overlayOnly) FinderPreview(a)
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(a.frozen, a.pendingGroundEv, a.reportEnabled, a.dynamicEnabled) {
                            detectTapGestures(onDoubleTap = if (a.reportEnabled) { _ -> a.archiveFinder() } else null) {
                                if (a.pendingGroundEv != null) {
                                    a.compareGroundPoint(it.x / size.width.toDouble(), it.y / size.height.toDouble())
                                } else if (a.dynamicEnabled && !a.frozen)
                                    a.spatial.place(it.x / size.width.toDouble(),it.y / size.height.toDouble(),false)
                                else if (a.frozen)
                                    a.point(
                                        it.x / size.width.toDouble(),
                                        it.y / size.height.toDouble(),
                                    )
                            }
                        }
                    ) {
                        val edge = Color.White.copy(alpha = .75f)
                        val k = 12.dp.toPx()
                        val w = size.width
                        val h = size.height
                        for (x in listOf(0f, w)) for (y in listOf(0f, h)) {
                            drawLine(edge, Offset(x, y), Offset(if (x == 0f) k else w - k, y), 3f)
                            drawLine(edge, Offset(x, y), Offset(x, if (y == 0f) k else h - k), 3f)
                        }
                        val guide = s.scan?.takeIf { a.scanActive && !a.frozen }
                        if (guide == null) {
                            drawLine(edge, center - Offset(k,0f), center + Offset(k,0f), 2f)
                            drawLine(edge, center - Offset(0f,k), center + Offset(0f,k), 2f)
                        } else {
                            drawCircle(Color.Black, k*.68f, center, style=Stroke(3.dp.toPx()))
                            drawCircle(paper, k*.68f, center, style=Stroke(1.5.dp.toPx()))
                            guide.startMark?.let { mark ->
                                val start = Offset(mark.center.x.toFloat()*w, mark.center.y.toFloat()*h)
                                val tangent = Offset(mark.right.x.toFloat()*w, mark.right.y.toFloat()*h)
                                if (tangent.getDistance() > .001f) {
                                    val right = tangent * (k / tangent.getDistance())
                                    val down = Offset(-right.y,right.x)
                                    for (v in listOf(right,down)) {
                                        drawLine(Color.Black,start-v,start+v,3.dp.toPx())
                                        drawLine(amber,start-v,start+v,1.5.dp.toPx())
                                    }
                                }
                            }
                        }
                        guide?.homeDirection?.let {
                            val dir = Offset(it.x.toFloat(),it.y.toFloat())
                            val tip = center + dir * (k*3)
                            val base = tip - dir*k
                            val side = Offset(-dir.y,dir.x) * (k*.55f)
                            drawLine(amber,center + dir*k,tip,3.dp.toPx())
                            drawLine(amber,tip,base + side,3.dp.toPx())
                            drawLine(amber,tip,base - side,3.dp.toPx())
                        }
                        if (guide != null) {
                            val rotated = guide.mapTurn % 180 != 0
                            val cols = if (rotated) GRID_HEIGHT else GRID_WIDTH
                            val rows = if (rotated) GRID_WIDTH else GRID_HEIGHT
                            val cell = min(8.dp.toPx(),min(w*.32f/cols,h*.25f/rows))
                            val origin = Offset(6.dp.toPx(),6.dp.toPx())
                            val map = Size(cell*cols,cell*rows)
                            fun at(p: FinderPoint) = origin + Offset(
                                p.x.toFloat().coerceIn(0f,1f)*map.width,
                                p.y.toFloat().coerceIn(0f,1f)*map.height)
                            drawRect(Color.Black.copy(alpha=.85f),origin,map)
                            for (i in guide.tiles.indices) {
                                val pos = FinderPoint((i%GRID_WIDTH+.5)/GRID_WIDTH,
                                    (i/GRID_WIDTH+.5)/GRID_HEIGHT).turned(guide.mapTurn)
                                drawRect(lerp(Color.DarkGray,green,guide.tiles[i].toFloat()),
                                    at(pos)-Offset(cell*.44f,cell*.44f),Size(cell*.88f,cell*.88f))
                            }
                            guide.next?.let {
                                val p = at(it)
                                val r = cell*.72f
                                val diamond = Path().apply {
                                    moveTo(p.x,p.y-r); lineTo(p.x+r,p.y); lineTo(p.x,p.y+r)
                                    lineTo(p.x-r,p.y); close()
                                }
                                drawPath(diamond,amber,style=Stroke(1.5.dp.toPx()))
                            }
                            val start = at(FinderPoint(.5,.5))
                            drawLine(amber,start-Offset(cell,0f),start+Offset(cell,0f),2.dp.toPx())
                            drawLine(amber,start-Offset(0f,cell),start+Offset(0f,cell),2.dp.toPx())
                            val pos = at(guide.aim)
                            drawCircle(Color.Black,cell*.55f,pos,style=Stroke(3.dp.toPx()))
                            drawCircle(paper,cell*.55f,pos,style=Stroke(1.5.dp.toPx()))
                        }
                        val tracked = a.spatial.onScreen()
                        for (v in a.spots.map { TrackedSpot(it) } + tracked) {
                            val pt = v.spot
                            val c = (if (v.stale) muted else if (pt.id==0) amber else spotColor(pt.id)).copy(alpha=v.alpha)
                            val at = Offset((pt.x * w).toFloat(), (pt.y * h).toFloat())
                            drawCircle(Color.Black.copy(alpha=v.alpha), 9.dp.toPx(), at, style = Stroke(4.dp.toPx()))
                            drawCircle(c, 9.dp.toPx(), at, style = Stroke(2.dp.toPx()))
                            drawContext.canvas.nativeCanvas.drawText(
                                (if (pt.id==0) (v.kelvin?.let { "$it K" } ?: "— K") else "#${pt.id}") +
                                    (if (v.stale) tr(" · 上次读数") else ""),
                                at.x + 10.dp.toPx(),
                                at.y,
                                android.graphics.Paint(1).apply {
                                    color = c.toArgb()
                                    setShadowLayer(2.dp.toPx(), 0f, 0f, android.graphics.Color.BLACK)
                                    textSize = 14.dp.toPx()
                                },
                            )
                        }
                    }
                    FramingOverlay(a,f.w,f.h)
                    FaceMeterOverlay(a)
                    if (a.showLevel && !a.frozen) FinderLevel(a)
                    s.scan?.takeIf { a.scanActive && !a.frozen }?.let { guide ->
                        val pitch = guide.startPitchRadians
                        val roll = guide.startRollRadians
                        val level = when {
                            !a.showLevel || pitch == null || roll == null -> ""
                            guide.startOverhead -> " · 离垂直 %.1f°".format(java.util.Locale.ROOT,90 - abs(Math.toDegrees(pitch)))
                            else -> " · 横滚 %+.1f° · 俯仰 %+.1f°".format(java.util.Locale.ROOT,
                                Math.toDegrees(roll), Math.toDegrees(pitch))
                        }
                        Label("＋ 起始$level",10,amber,Modifier.align(Alignment.BottomStart).padding(4.dp)
                            .background(ink.copy(alpha=.78f)).padding(horizontal=4.dp,vertical=1.dp))
                    }
                    TemperatureOverlay(a,Modifier.fillMaxSize())
                    if (a.showFps) Label("${if (a.frozen) 0 else a.renderedFps} FPS", 11, paper,
                        Modifier.align(Alignment.TopEnd).padding(5.dp).background(ink.copy(alpha=.78f))
                            .padding(horizontal=5.dp,vertical=2.dp), monoType=true)
                }
            } else if (!a.allowed || a.message.contains("重试"))
                Action("启用相机 / 重试", true) { a.requestCamera() }
            else Label("正在等待相机", 13, muted)
        }
        if (!overlayOnly) FinderFooter(a)
    }
}

@Composable
private fun HistogramCountLabel(bars: HistogramBars, gain: Double, logCounts: Boolean, color: Color) {
    Label((if (logCounts) "像素 log " else "像素 ") + ceil(bars.peak() / gain).toInt(), 9, color)
}

@Composable
private fun Histogram(a: MainActivity) {
    val palette = LocalGlassPalette.current
    val paper = palette?.text ?: paper
    val muted = palette?.secondary ?: muted
    val line = palette?.grid ?: line
    val amber = palette?.accent ?: amber
    val red = palette?.over ?: red
    val green = palette?.under ?: green
    val sample = a.sample
    val film = a.film
    val middle = a.effectiveMiddleEv
    val response = a.response
    val reading = a.reading
    var window by remember(a.centerHistogramOnZone) { mutableStateOf<HistogramWindow?>(null) }
    val chart = window ?: HistogramWindow.automatic(reading, middle, a.centerHistogramOnZone)
    val currentChart by rememberUpdatedState(chart)
    val bins = remember(sample?.hist, sample?.frame?.cameraEv, response, chart.left, chart.span) {
        sample?.let { chart.bins(it.hist, response, it.frame.cameraEv, 128) } ?: IntArray(128)
    }
    val bars = displayHistogram(bins, sample?.hist, sample?.frame?.cameraEv ?: 0.0, chart, response,
        a.smoothHistogram && !a.frozen, sample?.frame?.id, a.scanActive)
    val densityMax = ceil(film.dots.maxOf { it.second })
    Column(
        Modifier.fillMaxSize().background(if (palette == null) ink else Color.Transparent).padding(5.dp)
            .border(1.dp, if (a.cal == null) red else line).padding(7.dp)
    ) {
        Label("曝光直方图", 15, bold = true)
        Label(
            if (reading == null) "等待测光" else
                "%.1f EV100".format(java.util.Locale.US, reading) + if (a.cal == null) " · 估测" else "",
            11, if (a.cal == null) red else paper, monoType = true,
        )
        if (remember(reading, sample?.hist) { sample != null && dimMeterWarning(reading, sample.hist) })
            Label("环境极暗，测光可能不准确", 11, amber)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            HistogramCountLabel(bars, chart.gain, a.logCounts, muted)
            Label("密度 D " + n(round(densityMax / chart.gain * 10) / 10), 9, amber)
        }
        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .semantics {
                    contentDescription = tr("场景 EV100 直方图，可横向拖动与双指缩放，双击恢复自动")
                    stateDescription = tr("%.1f 至 %.1f EV100，纵轴 %.2f 倍").format(
                        java.util.Locale.US, chart.left, chart.right, chart.gain
                    )
                }
                .pointerInput(a.centerHistogramOnZone) {
                    var lastTapUp: Long? = null
                    var lastTapPosition = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var tap = true
                        var moved = false
                        var travel = 0f
                        do {
                            val event = awaitPointerEvent()
                            val first = event.changes.first()
                            if (event.changes.size > 1 || first.isConsumed ||
                                (first.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
                            if (!event.changes.any { it.pressed }) {
                                if (tap && !moved && first.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                                    // Input timestamps remain accurate when a busy GL or main thread
                                    // delivers both taps together. A pinch cannot start a tap pair.
                                    val interval = lastTapUp?.let { down.uptimeMillis - it }
                                    if (interval != null &&
                                        interval in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                                        (down.position - lastTapPosition).getDistance() <= 40.dp.toPx()) {
                                        window = null
                                        lastTapUp = null
                                    } else {
                                        lastTapUp = first.uptimeMillis
                                        lastTapPosition = first.position
                                    }
                                    first.consume()
                                } else lastTapUp = null
                                break
                            }
                            val fingers = event.changes.filter { it.pressed && it.previousPressed }
                            if (fingers.isEmpty()) continue
                            val panX = fingers.map { it.position.x - it.previousPosition.x }.average()
                            travel += abs(panX).toFloat()
                            if (fingers.size >= 2 || travel > viewConfiguration.touchSlop) moved = true
                            if (!moved) continue
                            val w = size.width.coerceAtLeast(1)
                            val anchor = fingers.map { it.previousPosition.x }.average() / w
                            var zx = 1.0
                            var zy = 1.0
                            if (fingers.size >= 2) {
                                val oldGap = fingers[0].previousPosition - fingers[1].previousPosition
                                val gap = fingers[0].position - fingers[1].position
                                val minGap = 18.dp.toPx()
                                if (abs(oldGap.x) >= minGap && abs(gap.x) >= minGap)
                                    zx = abs(gap.x / oldGap.x).toDouble()
                                if (abs(oldGap.y) >= minGap && abs(gap.y) >= minGap)
                                    zy = abs(gap.y / oldGap.y).toDouble()
                            }
                            window = (window ?: currentChart).transform(panX / w, anchor, zx, zy)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            fun x(ev: Double) = (chart.fraction(ev) * w).toFloat()
            clipRect {
                val grid = if (chart.span > 24) 4 else if (chart.span > 12) 2 else 1
                for (tick in ceil(chart.left / grid).toInt()..floor(chart.right / grid).toInt()) {
                    val xx = x((tick * grid).toDouble())
                    drawLine(line, Offset(xx, 0f), Offset(xx, h))
                }
                val topCount = bars.peak() / chart.gain
                val logTop = ln1p(topCount)
                if (!a.logCounts) {
                    for (i in 1..3) drawLine(line, Offset(0f, h * i / 4), Offset(w, h * i / 4))
                }
                for (i in bins.indices) {
                    val count = bars.value(i).toDouble()
                    val fraction = if (a.logCounts) ln1p(count) / logTop else count / topCount
                    val bh = (h * fraction).toFloat()
                    drawRect(muted, Offset(w * i / bins.size, h - bh), Size(w / bins.size + 1, bh))
                }
                if (a.logCounts) {
                    val axisPaint = android.graphics.Paint(1).apply {
                        color = paper.toArgb()
                        textSize = 9.dp.toPx()
                        setShadowLayer(2.dp.toPx(), 0f, 0f, android.graphics.Color.BLACK)
                    }
                    var count = 1.0
                    while (count < topCount) {
                        val yy = (h * (1 - ln1p(count) / logTop)).toFloat()
                        drawLine(line, Offset(0f, yy), Offset(w, yy))
                        if (yy >= axisPaint.textSize) {
                            drawContext.canvas.nativeCanvas.drawText(count.toInt().toString(), 1.dp.toPx(), yy, axisPaint)
                        }
                        count *= 10
                    }
                }
                val path = Path()
                for (i in 0..128) {
                    val ev = chart.left + chart.span * i / 128
                    val y = (h * (1 - chart.gain * film.density(ev - middle) / densityMax)).toFloat()
                    if (i == 0) path.moveTo(0f, y) else path.lineTo(w * i / 128, y)
                }
                drawPath(path, amber, style = Stroke(1.5.dp.toPx()))
                for ((ev, c) in listOf(middle + film.low to green, middle to paper, middle + film.high to red))
                    drawLine(c, Offset(x(ev), 0f), Offset(x(ev), h), 1.5.dp.toPx())
                val paint = android.graphics.Paint(1).apply {
                    color = android.graphics.Color.WHITE
                    textSize = 11.dp.toPx()
                }
                val labelRows = mutableListOf<Float>()
                // If several readings map to the same pixel, share the vertical stroke.
                val points = a.spots.map { TrackedSpot(it) } + a.spatial.views.filter { it.spot.id > 0 }
                val columns = points.sortedBy { it.spot.ev }.groupBy { x(it.spot.ev).roundToInt() }
                for (group in columns.values) for ((index, v) in group.withIndex()) {
                    val pt = v.spot
                    val c = (if (v.stale) muted else spotColor(pt.id)).copy(alpha=v.alpha)
                    val xx = x(pt.ev)
                    if (xx !in 0f..w) continue
                    paint.color = c.toArgb()
                    val segment = 5.dp.toPx()
                    val pattern = if (group.size == 1) null else PathEffect.dashPathEffect(
                        floatArrayOf(segment, segment * (group.size - 1)), segment * (group.size - index))
                    drawLine(c, Offset(xx, 0f), Offset(xx, h), 1.dp.toPx(), pathEffect = pattern)
                    val label = "#" + pt.id + if (v.stale) tr(" · 上次读数") else ""
                    val labelX = min(xx + 2, w - paint.measureText(label)).coerceAtLeast(0f)
                    val row = labelRows.indexOfFirst { it < labelX }.let { if (it < 0) labelRows.size else it }
                    if (row == labelRows.size) labelRows.add(0f)
                    labelRows[row] = labelX + paint.measureText(label) + 2.dp.toPx()
                    drawContext.canvas.nativeCanvas.drawText(label, labelX, (12 + 13 * row).dp.toPx(), paint)
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(32.dp)) {
            val w = size.width
            val paint = android.graphics.Paint(1).apply {
                textSize = 10.dp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val maxLabels = (w / 33.dp.toPx()).toInt().coerceAtLeast(2)
            val step = listOf(1, 2, 3, 5, 8, 10, 16, 32).first { it * maxLabels >= chart.span }
            paint.color = muted.toArgb()
            for (tick in ceil(chart.left / step).toInt()..floor(chart.right / step).toInt()) {
                val ev = tick * step
                val label = ev.toString()
                val width = paint.measureText(label)
                val xx = (chart.fraction(ev.toDouble()) * w).toFloat().coerceIn(width / 2, max(width / 2,w - width / 2))
                drawContext.canvas.nativeCanvas.drawText(label, xx, 11.dp.toPx(), paint)
            }
            if (sample != null) {
                val zones = listOf("0","I","II","III","IV","V","VI","VII","VIII","IX","X")
                val gap = max(1, ceil(28.dp.toPx() * chart.span / max(w,1f)).toInt())
                paint.color = amber.toArgb()
                for (zone in zones.indices) {
                    if ((zone - 5) % gap != 0) continue
                    val xx = (chart.fraction(middle + zone - 5) * w).toFloat()
                    val label = if (zone == 5) "Zone V" else zones[zone]
                    val half = paint.measureText(label) / 2
                    if (xx - half >= 0 && xx + half <= w)
                        drawContext.canvas.nativeCanvas.drawText(label, xx, 26.dp.toPx(), paint)
                }
            }
        }
        if (sample != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("欠 %.1f".format(java.util.Locale.US, middle + film.low), 10, green, monoType = true)
            Label("过 %.1f".format(java.util.Locale.US, middle + film.high), 10, red, monoType = true)
        }
    }
}

@Composable
private fun Meter(a: MainActivity) {
    val palette = LocalGlassPalette.current
    val amber = palette?.accent ?: amber
    val e = a.exposure
    Column(Modifier.fillMaxSize().background(if (palette == null) panel else Color.Transparent).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            for (mode in listOf("A", "S", "M")) {
                MeterButton(mode, a.mode == mode, !a.frozen, Modifier.weight(1f)) { a.modeTo(mode) }
            }
            Spacer(Modifier.width(3.dp))
            MeterButton("连续", !a.single, !a.frozen, Modifier.weight(1.5f)) {
                a.single = false
                a.held = null
            }
            MeterButton("单次", a.single, !a.frozen, Modifier.weight(1.5f)) { a.meterOnce() }
        }
        if (a.frozen && a.placedSpotId != null) {
            Label("按 #${a.placedSpotId} 放置于 Zone ${a.placedZone}", 12, amber)
        } else if (a.mode == "M") {
            Label(if (a.sample == null) "— EV" else "曝光偏差  " + signed((a.meterMiddleTarget ?: a.effectiveMiddleEv) - a.effectiveMiddleEv) + " EV",
                12, amber, Modifier.padding(vertical = 4.dp), monoType = true)
        } else {
            val requested = a.compensationEv
            val residual = a.meterMiddleTarget?.let { it - a.effectiveMiddleEv - requested }
            ExposureCompensation(requested, residual, !a.frozen, a::adjustCompensation) {
                a.input("曝光补偿（EV）", a.compensationEv, -5.0..5.0, a::adjustCompensation)
            }
        }
        ExposureTable(a, e, Modifier.weight(2.25f))
        val iso = (speeds + a.manual.iso).distinct().sorted()
        Wheel("ISO", iso, e.iso, !a.frozen && !a.isoLocked, ::isoLabel, Modifier.weight(1f),
            lockLabel = if (a.isoLocked) "已锁定 · 解锁" else "锁定至 " + isoLabel(a.filmIso),
            onLongPress = if (a.frozen) null else a::customIsoDialog,
            onLock = { if (!a.frozen) a.toggleIsoLock() }) {
            a.setValues(a.manual.copy(iso = it))
        }
    }
}

@Composable
private fun MeterButton(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, action: () -> Unit) {
    val palette = LocalGlassPalette.current
    val ink = palette?.text ?: ink
    val paper = palette?.text ?: paper
    val selectedFill = palette?.accent ?: amber
    Box(modifier.padding(1.dp).height(31.dp).clip(RoundedCornerShape(4.dp))
        .background(if (selected) selectedFill else palette?.button ?: ink).clickable(enabled = enabled, onClick = action),
        contentAlignment = Alignment.Center) {
        InstrumentText(if (text == "连续" && UiLanguage.usesEnglish) "Cont." else text,
            style=TextStyle(color=if (selected) palette?.backdrop ?: ink else paper,fontWeight=FontWeight.Bold),
            maxLines=1,softWrap=false,autoSize=androidx.compose.foundation.text.TextAutoSize.StepBased(8.sp,12.sp,.5.sp))
    }
}

@Composable
private fun ExposureCompensation(ev: Double, residual: Double?, enabled: Boolean, update: (Double) -> Unit, edit: () -> Unit) {
    val palette = LocalGlassPalette.current
    val muted = palette?.secondary ?: muted
    val line = palette?.grid ?: line
    val amber = palette?.accent ?: amber
    val change by rememberUpdatedState(update)
    val onEdit by rememberUpdatedState(edit)
    fun stopAt(x: Float, width: Float, inset: Float): Double {
        val fraction = ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return ((fraction * 30).roundToInt() - 15) / 3.0
    }
    Column(Modifier.fillMaxWidth().semantics {
        contentDescription = tr("曝光补偿")
        onLongClick("输入曝光补偿") { if (enabled) onEdit(); enabled }
        progressBarRangeInfo = ProgressBarRangeInfo(ev.toFloat(), -5f..5f, 29)
        setProgress { value -> if (enabled) change((value * 3).roundToInt() / 3.0); enabled }
    }) {
        Row(Modifier.fillMaxWidth().pointerInput(enabled) {
            if (enabled) detectTapGestures(onLongPress = { onEdit() })
        }, horizontalArrangement = Arrangement.SpaceBetween) {
            Label(if (residual != null && abs(residual) > .25) "超限 " + signed(residual) + " EV" else "曝光补偿",
                10, if (residual != null && abs(residual) > .25) red else muted)
            Label((if (ev >= 0.0) "+" else "") + n(ev) + " EV", 11, amber, monoType = true)
        }
        Canvas(Modifier.fillMaxWidth().height(25.dp).pointerInput(enabled) {
            if (enabled) detectTapGestures(onLongPress = { onEdit() }, onTap = {
                change(stopAt(it.x, size.width.toFloat(), 6.dp.toPx()))
            })
        }.pointerInput(enabled) {
            if (enabled) detectHorizontalDragGestures { pointer, _ ->
                pointer.consume()
                change(stopAt(pointer.position.x, size.width.toFloat(), 6.dp.toPx()))
            }
        }) {
            val inset = 6.dp.toPx()
            val length = size.width - 2*inset
            fun x(thirds: Number) = inset + (thirds.toFloat()+15)/30f*length
            val mid=size.height/2
            drawLine(line, Offset(inset, mid), Offset(size.width-inset, mid), 1.dp.toPx())
            drawLine(amber, Offset(x(0),mid), Offset(x(ev * 3),mid), 1.5.dp.toPx())
            for (i in -15..15) {
                val half = (if (i==0) 5.dp else if (i%3==0) 4.dp else 2.dp).toPx()
                drawLine(if (i==0) paper else muted, Offset(x(i),mid-half), Offset(x(i),mid+half))
            }
            drawCircle(amber, 4.dp.toPx(), Offset(x(ev * 3),mid))
        }
    }
}

@Composable
private fun ExposureTable(a: MainActivity, e: Exposure, m: Modifier) {
    val palette = LocalGlassPalette.current
    val paper = palette?.text ?: paper
    val muted = palette?.secondary ?: muted
    val amber = palette?.accent ?: amber
    val line = palette?.grid ?: line
    val pairs = remember(e) { equivalentExposures(e) }
    val selected = pairs.indexOf(e)
    val correction = remember(e.t, a.film.name) { reciprocity(a.film.name, e.t) }
    BoxWithConstraints(m.fillMaxWidth()) {
        val count =
            when {
                maxWidth >= 570.dp -> 9
                maxWidth >= 380.dp -> 7
                maxWidth >= 235.dp -> 5
                else -> 3
            }.coerceAtMost(pairs.size)
        val first = (selected - count / 2).coerceIn(0, pairs.size - count)
        val shown = pairs.subList(first, first + count)
        val corrections =
            remember(shown, a.film.name) { shown.map { reciprocity(a.film.name, it.t) } }
        val showTimes = corrections.any { it.status == ReciprocityStatus.CORRECTED }
        val compact = maxHeight < 100.dp
        val size = if (maxWidth < 170.dp) 11 else if (maxWidth < 280.dp) 13 else 17
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InstrumentText(if (a.useMeasuredLoss) "含实测 +" + n(a.opticalLoss) + " EV" else if (a.opticalLoss > 0) "含光损 +" + n(a.opticalLoss) + " EV" else if (showTimes) "等效曝光 · → 实拍" else "等效曝光", Modifier.weight(1f), style=TextStyle(color=muted,fontSize=(if (compact) 10 else 12).sp), maxLines=1, softWrap=false)
                Label("ISO " + isoLabel(e.iso), if (compact) 10 else 12, muted, monoType = true)
            }
            Row(
                Modifier.weight(1f).fillMaxWidth().pointerInput(a.mode, a.frozen) {
                    if (a.frozen) return@pointerInput
                    var apertureRow = false
                    var remainder = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            apertureRow = it.y > this.size.height * .5
                            remainder = 0f
                        }
                    ) { change, delta ->
                        change.consume()
                        remainder -= delta
                        val pitch = 20.dp.toPx()
                        val steps = (remainder / pitch).toInt()
                        if (steps != 0) {
                            remainder -= steps * pitch
                            val current = a.exposure
                            if (a.mode == "A" || (a.mode == "M" && apertureRow))
                                a.setValues(a.manual.copy(a = stepFromValue(apertures, current.a, steps)))
                            else a.setValues(a.manual.copy(t = stepFromValue(shutters, current.t, steps)))

                        }
                    }
                }
            ) {
                for ((idx, pair) in shown.withIndex()) {
                    val currentPair by rememberUpdatedState(pair)
                    val active = pair == e
                    val color = if (active) paper else muted
                    Column(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 1.dp)
                            .background(if (active) palette?.button ?: Color(0xff342c22) else Color.Transparent)
                            .pointerInput(a.frozen) {
                                if (!a.frozen) detectTapGestures(onTap = { a.selectPair(currentPair) },
                                    onLongPress = { a.editExposureValue(it.y < this.size.height * .5f) })
                            }
                            .semantics {
                                if (!a.frozen) {
                                    onClick(tr("选择曝光组合")) { a.selectPair(pair); true }
                                    customActions = listOf(
                                        CustomAccessibilityAction(tr("输入快门速度")) { a.editExposureValue(true); true },
                                        CustomAccessibilityAction(tr("输入光圈")) { a.editExposureValue(false); true },
                                    )
                                }
                                contentDescription = tr(
                                    "快门 " +
                                        timeLabel(pair.t) +
                                        "，光圈 " +
                                        n(pair.a) +
                                        "，ISO " +
                                        isoLabel(pair.iso))
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(
                            Modifier.fillMaxWidth()
                                .height(2.dp)
                                .background(if (active) amber else line)
                        )
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Label(timeLabel(pair.t), size, color, bold = active, monoType = true)
                        }
                        if (showTimes) {
                            val actual = corrections[idx]
                            Label(
                                if (actual.status == ReciprocityStatus.CORRECTED)
                                    "→" + n(round(actual.exposureSeconds!! * 10) / 10) + "″" + if (actual.networkEstimate) " 网" else ""
                                else "·",
                                if (compact) 9 else 11,
                                amber,
                                monoType = true,
                            )
                        }
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Label("ƒ/" + n(pair.a), size, color, bold = active, monoType = true)
                        }
                        Canvas(Modifier.fillMaxWidth().height(if (compact) 4.dp else 7.dp)) {
                            for (i in 0..2) {
                                val x = this.size.width * (i + .5f) / 3
                                drawLine(
                                    if (active && i == 1) amber else muted,
                                    Offset(x, if (i == 1) 0f else this.size.height * .5f),
                                    Offset(x, this.size.height),
                                    1.dp.toPx(),
                                )
                            }
                        }
                    }
                }
            }
            if (
                e.t < 1 &&
                    !a.film.digital &&
                    corrections.any {
                        it.meteredSeconds > 1 && it.status == ReciprocityStatus.UNPUBLISHED
                    }
            )
                Label("长曝光 · 厂家未给补偿值", 10, muted)
            if (e.t >= 1 && !a.film.digital)
                Label(
                    correction.label,
                    if (compact) 10 else 12,
                    if (correction.status == ReciprocityStatus.CORRECTED) amber else muted,
                )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Wheel(
    title: String,
    values: List<Double>,
    value: Double,
    enabled: Boolean,
    fmt: (Double) -> String,
    m: Modifier,
    lockLabel: String,
    onLongPress: (() -> Unit)?,
    onLock: () -> Unit,
    change: (Double) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val muted = palette?.secondary ?: muted
    val amber = palette?.accent ?: amber
    val selected = values.indexOf(value).coerceAtLeast(0)
    val cb by rememberUpdatedState(change)
    val current by rememberUpdatedState(selected)
    BoxWithConstraints(
        m.fillMaxWidth().semantics { contentDescription = tr(title + " " + fmt(value)) }
    ) {
        val count =
            when {
                maxWidth >= 570.dp -> 9
                maxWidth >= 380.dp -> 7
                maxWidth >= 235.dp -> 5
                else -> 3
            }
        val slots =
            ((-values.size)..values.size).map { selected + it * 3 }.filter { it in values.indices }
        val center = slots.indexOf(selected)
        val first = (center - count / 2).coerceIn(0, max(0, slots.size - count))
        val shown = slots.drop(first).take(count)
        Column(
            Modifier.fillMaxSize().pointerInput(values, enabled) {
                if (!enabled) return@pointerInput
                var remainder = 0f
                var index = 0
                detectHorizontalDragGestures(
                    onDragStart = {
                        index = current
                        remainder = 0f
                    }
                ) { c, dx ->
                    c.consume()
                    val pitch = this.size.width.toFloat() / (shown.size * 3)
                    remainder -= dx
                    val steps = (remainder / pitch).toInt()
                    if (steps != 0) {
                        index = (index + steps).coerceIn(values.indices)
                        cb(values[index])
                        remainder -= steps * pitch
                    }
                }
            }
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(title, 11, muted, Modifier.weight(1f))
                Label(lockLabel, 11, amber, Modifier.clickable(onClick = onLock).padding(horizontal = 3.dp, vertical = 2.dp))
            }
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in shown) {
                    val active = i == selected
                    Box(
                        Modifier.weight(1f).fillMaxHeight().combinedClickable(
                            enabled = enabled || onLongPress != null,
                            onLongClickLabel = tr("自定义 ISO"),
                            onLongClick = onLongPress,
                            onClick = { if (enabled) cb(values[i]) },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Label(
                            fmt(values[i]),
                            if (count >= 5) 17 else 12,
                            if (active) amber else muted,
                            bold = active,
                            monoType = true,
                        )
                        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp)) {
                            for (j in 0..2) {
                                val x = size.width * (j + .5f) / 3
                                drawLine(
                                    if (active && j == 1) amber else muted,
                                    Offset(x, if (j == 1) 0f else size.height / 2),
                                    Offset(x, size.height),
                                    1.dp.toPx(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FinderSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked,role=Role.Switch,onValueChange=change)
        .padding(vertical=10.dp), verticalAlignment=Alignment.CenterVertically) {
        Label(label,14,paper,Modifier.weight(1f))
        Box(Modifier.size(36.dp,20.dp).background(if(checked) amber else line,RoundedCornerShape(10.dp))
            .padding(3.dp),contentAlignment=if(checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Spacer(Modifier.size(14.dp).background(if(checked) ink else muted,RoundedCornerShape(7.dp)))
        }
    }
}

@Composable
private fun Settings(a: MainActivity, m: Modifier) {
    var soundSettings by remember { mutableStateOf(false) }
    BackHandler(soundSettings) { soundSettings = false }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)).clickable {
            a.settings = false
        }
    )
    Column(
        m.background(panel, RoundedCornerShape(8.dp))
            .border(1.dp, line, RoundedCornerShape(8.dp))
            .clickable(enabled = false) {}
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (soundSettings) Action("‹ 返回") { soundSettings = false }
            Label(if (soundSettings) "声音" else "设置", 22, paper, Modifier.weight(1f), true)
            Action("完成") { a.settings = false }
        }
        if (soundSettings) {
            SoundSettingsPanel(a)
            return@Column
        }
        val languages = listOf("system", "zh", "en")
        val languageNames = listOf("跟随系统", "简体中文", "English")
        Setting("语言", languageNames[languages.indexOf(UiLanguage.selection)]) {
            a.choose("语言", languageNames) { index ->
                val selected = languages[index]
                a.prefs.edit().putString("uiLanguage", selected).apply()
                UiLanguage.update(a, selected)
            }
        }
        Setting("声音", "音效设置") { soundSettings = true }
        FinderSwitch("肤色自动提亮", a.skinLiftEnabled) { a.configureSkinLift(it, a.skinLiftEv) }
        if (a.skinLiftEnabled) {
            val zones = listOf("0","I","II","III","IV","V","VI","VII","VIII","IX","X")
            fun zoneLabel(zone: Int) = "Zone " + zones[zone] + "（" + signed(zone - 5.0) + " EV）"
            val zone = 5.0 + a.skinLiftEv
            val label = if (zone == zone.toInt().toDouble()) zoneLabel(zone.toInt())
                else "第 " + n(zone) + " 区（" + signed(a.skinLiftEv) + " EV）"
            Setting("人脸目标分区", label) {
                a.choose("人脸目标分区", (0..10).map(::zoneLabel) + "自定义 EV…") { index ->
                    if (index <= 10) a.configureSkinLift(true, index - 5.0)
                    else a.input("人脸曝光补偿（EV）", a.skinLiftEv, -5.0..5.0) { a.configureSkinLift(true, it) }
                }
            }
        }
        fun open(url: String) {
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(a, tr("请安装可打开网页的浏览器"), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("直方图像素纵轴", 14, paper, Modifier.weight(1f))
            for ((index, label) in listOf("线性", "对数").withIndex()) {
                Action(label, a.logCounts == (index == 1)) {
                    a.logCounts = index == 1
                    a.prefs.edit().putBoolean("logCounts", a.logCounts).apply()
                }
            }
        }
        Label("全画幅等效焦距（可多选）", 14, paper)
        FlowRow {
            for (axis in FocalAxis.entries) {
                val selected = axis in a.equivalentAxes
                Box(Modifier.padding(2.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (selected) amber else ink)
                    .toggleable(selected, role = Role.Checkbox) { a.toggleEquivalentAxis(axis) }
                    .padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Label(axis.label, 12, if (selected) ink else paper)
                }
            }
        }
        FinderSwitch("直方图平滑刷新", a.smoothHistogram) { enabled ->
            a.smoothHistogram = enabled
            a.prefs.edit().putBoolean("smoothHistogram", enabled).apply()
        }
        Setting("直方图横轴中心", if (a.centerHistogramOnZone) "跟随 Zone V" else "跟随测光读数") {
            a.choose("直方图横轴中心", listOf("跟随 Zone V", "跟随测光读数")) { index ->
                a.centerHistogramOnZone = index == 0
                a.prefs.edit().putBoolean("centerHistogramOnZone", a.centerHistogramOnZone).apply()
            }
        }
        Setting("组件比例", "拖动分界线") {
            a.settings = false
            a.resizing = true
        }
        Setting("预览按钮", if (a.useLut) "LUT 色彩模拟" else "亮度模拟") {
            a.choose("拍摄预览", listOf("亮度模拟", "LUT 色彩模拟")) { i ->
                a.useLut = i == 1
                a.prefs.edit().putBoolean("useLut", a.useLut).apply()
            }
        }
        Setting("实时预览分辨率", n(a.previewScale) + "×") {
            val scales = listOf(.25, .5, .75, 1.0)
            a.choose("实时预览分辨率", scales.map { "${it}×" }) { i ->
                a.previewScale = scales[i]
                a.put("previewScale", scales[i])
            }
        }
        Label("拼接配准算法",14,paper)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (mode in listOf("LK","ORB","ECC","自动"))
                Action(mode,a.scanAlgorithm == mode) { a.chooseScanAlgorithm(mode) }
        }
        Row {
            Action(if (a.recordScan) "关闭拼接诊断记录" else "开启拼接诊断记录",a.recordScan) { a.recordScan = !a.recordScan }
            Action("导出拼接诊断 ZIP") { a.exportScan() }
        }
        FinderSwitch("电子水平尺 · 俯拍水平泡",a.showLevel) { enabled ->
            a.showLevel = enabled
            a.prefs.edit().putBoolean("showLevel",enabled).apply()
        }
        FinderSwitch("显示实际预览 FPS",a.showFps) { enabled ->
            a.showFps = enabled
            a.prefs.edit().putBoolean("showFps",enabled).apply()
        }
        Label("画面静止或冻结时恢复完整取景分辨率。", 11, muted)
        a.sample?.let { Label("镜头输入 ${it.captureWidth}×${it.captureHeight} · 取景裁框 ${it.frame.w}×${it.frame.h}", 11, muted) }
        if (a.useLut) {
            Label("Andrea Volpato / spektrafilm", 11, muted)
            Action("CC BY-SA 4.0 ↗") { open("https://creativecommons.org/licenses/by-sa/4.0/") }
            val variants = a.variants
            val entry = a.variant
            if (entry == null) Label(a.lutStatus, 11, amber) else {
                Setting("LUT 版本", "${variants.indexOf(entry) + 1} / ${variants.size}") {
                    a.choose("选择 LUT 烘焙版本", variants.map { it.label }) { i ->
                        a.chosenLuts[a.film.name] = variants[i].file
                        a.put("lutVariant:" + a.film.name, variants[i].file)
                    }
                }
                Label(entry.label, 11, muted)
                if (entry.approximation.isNotBlank()) Label(entry.approximation, 11, muted)
                if (entry.source.startsWith("https://")) Action("原始配置 ↗") { open(entry.source) }
                if (a.failedLut == entry.file) Label(a.lutStatus, 11, amber)
            }
        }
        Setting(
            "手机镜头",
            if (a.cameraChoice == "自动") "自动 · " + (a.current?.id ?: "—")
            else a.current?.label ?: "—",
        ) {
            a.choose("手机镜头", listOf("自动选择") + a.lenses.map { it.label }) { i ->
                a.cameraChoice = if (i == 0) "自动" else a.lenses[i - 1].id
                a.put("camera", a.cameraChoice)
                a.selectCamera(true)
            }
        }
        Setting("曝光曲线", a.film.name + " · " + a.filmSpeedLabel + " " + isoLabel(a.filmIso)) { a.chooseFilm() }
        Setting("测光 ISO", isoLabel(a.manual.iso) + if (a.isoLocked) " · 已锁定" else " · 手动") {
            if (a.frozen) a.notifyUser("请先退出点测光")
            else if (a.isoLocked) a.choose("跟随胶片 " + a.filmSpeedLabel + " " + isoLabel(a.filmIso), listOf("解锁并设置 ISO")) {
                a.input("测光 ISO", a.manual.iso, .001..10000000.0) { value ->
                    a.toggleIsoLock()
                    a.setValues(a.manual.copy(iso = value))
                }
            }
            else a.choose("测光 ISO", listOf("设置 ISO", "恢复胶片 " + a.filmSpeedLabel + " " + isoLabel(a.filmIso))) { i ->
                if (i == 1) a.toggleIsoLock()
                else a.input("测光 ISO", a.manual.iso, .001..10000000.0) { a.setValues(a.manual.copy(iso = it)) }
            }
        }
        FlowRow {
            Action("新建预设") { a.editCustomPreset() }
            a.customPresets.find { it.id == a.selectedPresetId }?.let { preset ->
                Action("编辑") { a.editCustomPreset(preset) }
            }
            Action("导入 JSON") { a.pickPresetFile() }
            if (a.customPresets.isNotEmpty()) Action("导出 JSON") { a.exportPresetFile() }
        }
        Label(a.film.info + " 上下限为预览用估算值。", 11, muted)
        if (colorCurves.containsKey(a.film.name)) Label("分层色彩预览 · 手机 RGB 光谱近似，日光中性校正。", 11, muted)
        val record=filmRecords.find{it.film.name==a.film.name}
        Row {
            if (record != null) Action(filmSourceLabel(record.document)) { open(filmSourceUrl(record.document)) }
            val online = onlineReciprocity[a.film.name]
            val result = reciprocity(a.film.name, 10.0)
            if (!result.networkEstimate) result.sourceUrl?.let { url -> Action("倒易律 ↗") { open(url) } }
            online?.let { curve -> Action("倒易律 · 数据来源网络 ↗") { open(curve.url) } }
        }
        val styles = listOf("彩色", "黑白", "Aerochrome", "Lomo Purple")
        Setting("色彩映射", styles[a.style]) {
            a.choose("色彩映射", styles) {
                a.style = it
                a.put("style", it)
            }
        }
        if (a.style >= 2) Label("可见光近似配色" + if (a.style == 2) "，无法还原真实红外反射。" else "。", 11, muted)
        Setting("镜头校准", a.cal?.let { n(it.stops) + " EV" } ?: "未校准") { a.calibrationDialog() }
        Label(
            a.cal?.let { it.channel + " · ISO " + it.iso + " · 镜头 " + a.current?.id }
                ?: "推荐使用实体灰卡或九级阶梯卡。也可对静止场景估测。",
            11,
            muted,
        )
    }
}

@Composable
internal fun OpticsCompensationControls(a: MainActivity) {
    a.measuredOpticalLoss?.let { measured ->
        Setting("光损补偿来源", if (a.useMeasuredLoss) "毛玻璃实测 +" + n(measured) + " EV" else "滤镜与皮腔") {
            a.choose("光损补偿来源", listOf("毛玻璃同点实测 +" + n(measured) + " EV", "滤镜与皮腔 +" + n(a.geometricLoss) + " EV")) {
                a.chooseOpticalSource(it == 0)
            }
        }
    }
    if (!a.useMeasuredLoss) Setting("滤镜补偿", n(a.filterStops) + " EV") {
        a.choose("滤镜补偿",listOf("输入 ND 档数","输入滤镜因子","关闭滤镜补偿")) { index ->
            when (index) {
                0 -> a.input("ND 档数（EV）",a.filterStops,0.0..24.0) { a.adjustOptics(it,a.bellowsDrawMm) }
                1 -> a.input("滤镜因子（例如 8× = 3 EV）",2.0.pow(a.filterStops),1.0..16777216.0) {
                    a.adjustOptics(log2(it),a.bellowsDrawMm)
                }
                else -> a.adjustOptics(0.0,a.bellowsDrawMm)
            }
        }
    }
    if (!a.useMeasuredLoss) Setting("皮腔补偿", if (a.bellowsDrawMm == 0.0) "关闭" else n(a.bellowsDrawMm) + " mm · +" + n(a.opticalLoss-a.filterStops) + " EV") {
        a.input("镜头主平面至胶片总距（mm，0 关闭）", a.bellowsDrawMm,0.0..4000.0) {
            a.adjustOptics(a.filterStops,it)
        }
    }
}

@Composable
private fun Setting(name: String, value: String, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Label(name, 14, paper)
        Label(value + " ›", 13, amber)
    }
}
