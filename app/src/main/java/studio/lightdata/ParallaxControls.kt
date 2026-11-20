package studio.lightdata

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import kotlin.math.*

private val distances = listOf(50,75,100,125,150,200,250,300,400,500,600,750,
    1000,1250,1500,2000,2500,3000,4000,5000,7500,10000,15000,20000,30000,50000,100000)
    .map(Int::toDouble) + Double.POSITIVE_INFINITY

private fun distanceLabel(mm: Double) = when {
    mm.isInfinite() -> "∞"
    mm < 1000 -> "${n(mm)} mm"
    else -> "${n(mm / 1000)} m"
}

@Composable
private fun ParallaxLabel(value: String, color: Color = Color.White, size: Int = 14, modifier: Modifier = Modifier) {
    InstrumentText(value, modifier, style = TextStyle(color = color, fontSize = size.sp))
}

@Composable
internal fun ParallaxSettings(a: MainActivity) {
    val config = a.parallax
    fun apply(next: ParallaxConfig) {
        if (!a.updateParallax(next)) a.notifyUser("距离过近。请增加被摄距离，或检查 Z 偏移与拍摄镜头焦距。")
    }
    Row(Modifier.fillMaxWidth().toggleable(config.enabled, role = Role.Switch) { enabled ->
        val next = config.copy(enabled = enabled)
        apply(if (enabled && !next.valid(a.focal)) next.copy(distanceMm = Double.POSITIVE_INFINITY) else next)
    }
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ParallaxLabel("取景器视差补偿", modifier = Modifier.weight(1f))
        ParallaxLabel(if (config.enabled) "已开启" else "已关闭", if (config.enabled) amber else Color.LightGray)
    }
    if (!config.enabled) return
    ParallaxLabel("按设备默认方向，从相机后方向前看。以手机取景镜头中心为零点，量取拍摄镜头中心的偏移。", Color.LightGray)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AxisGuide(config.xMm, config.yMm, "背视 · 右 X+ / 上 Y+", Modifier.weight(1f))
        AxisGuide(config.zMm, config.yMm, "侧视 · 向前 Z+ / 上 Y+", Modifier.weight(1f))
    }
    ParallaxLabel("○ 取景镜头     ● 拍摄镜头", amber, 12)
    val coords = listOf("X · 向右为正" to config.xMm, "Y · 向上为正" to config.yMm, "Z · 朝被摄物为正" to config.zMm)
    for ((i, axis) in coords.withIndex()) {
        fun change(value: Double) {
            val current = a.parallax
            apply(when (i) {
                0 -> current.copy(xMm = value)
                1 -> current.copy(yMm = value)
                else -> current.copy(zMm = value)
            })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ParallaxLabel(axis.first, modifier = Modifier.weight(1f))
            ParallaxLabel("−", amber, 18, Modifier.clickable(enabled = axis.second > -2000) {
                change((axis.second - 1).coerceAtLeast(-2000.0))
            }.padding(10.dp))
            ParallaxLabel("${n(axis.second)} mm  ›", amber, modifier = Modifier.clickable {
                a.input("${axis.first}（mm）", axis.second, -2000.0..2000.0, ::change)
            }.padding(vertical = 8.dp))
            ParallaxLabel("+", amber, 18, Modifier.clickable(enabled = axis.second < 2000) {
                change((axis.second + 1).coerceAtMost(2000.0))
            }.padding(10.dp))
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ParallaxLabel("被摄物至焦平面", modifier = Modifier.weight(1f))
        ParallaxLabel(distanceLabel(config.distanceMm) + "  ›", amber, modifier = Modifier.clickable {
            a.editSubjectDistance()
        }.padding(8.dp))
        ParallaxLabel("∞", amber, modifier = Modifier.clickable {
            apply(a.parallax.copy(distanceMm = Double.POSITIVE_INFINITY))
        }.padding(8.dp))
    }
    ParallaxDistanceWheel(a, Modifier.fillMaxWidth().height(54.dp))
    ParallaxLabel("校正在所选距离的平面上成立。镜头光轴应平行；应用以标称焦距近似焦平面至拍摄镜头的距离，近摄时请核对实际取景。", Color.LightGray, 12)
}

@Composable
private fun AxisGuide(horizontal: Double, up: Double, title: String, modifier: Modifier) {
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(86.dp).background(Color(0xff242528))) {
            val mid = center
            val extent = max(100.0, max(abs(horizontal), abs(up)))
            val scale = (min(size.width, size.height) * .38f / extent).toFloat()
            val target = mid + Offset(horizontal.toFloat() * scale, -up.toFloat() * scale)
            drawLine(Color.Gray, Offset(4.dp.toPx(), mid.y), Offset(size.width-4.dp.toPx(), mid.y))
            drawLine(Color.Gray, Offset(mid.x, 4.dp.toPx()), Offset(mid.x, size.height-4.dp.toPx()))
            drawLine(amber, mid, target, 1.5.dp.toPx())
            drawCircle(Color.White, 7.dp.toPx(), mid, style = Stroke(1.5.dp.toPx()))
            drawCircle(amber, 3.5.dp.toPx(), target)
        }
        ParallaxLabel(title, Color.LightGray, 12)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ParallaxDistanceWheel(
    a: MainActivity, modifier: Modifier = Modifier, vertical: Boolean = false,
    foreground: Color = Color.White, background: Color = Color(0xff191a1c),
) {
    val config = a.parallax
    if (!config.enabled) return
    // Preserve custom distances as stops even when the gesture moves away and back.
    var custom by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(config.distanceMm) {
        if (config.distanceMm !in distances) custom = config.distanceMm
    }
    val options = (distances + listOfNotNull(custom, config.distanceMm)).distinct()
        .filter { config.copy(distanceMm = it).valid(a.focal) }.sorted()
    if (options.isEmpty()) return
    val selected = options.indexOf(config.distanceMm).coerceAtLeast(0)
    val current by rememberUpdatedState(selected)
    fun select(index: Int): Boolean = a.updateParallax(a.parallax.copy(distanceMm = options[index.coerceIn(options.indices)]))
    BoxWithConstraints(modifier.background(background).semantics {
        contentDescription = tr("被摄物至焦平面距离 ${distanceLabel(config.distanceMm)}")
        progressBarRangeInfo = ProgressBarRangeInfo(selected.toFloat(), 0f..options.lastIndex.toFloat(), (options.size-2).coerceAtLeast(0))
        setProgress { select(it.roundToInt()) }
    }, contentAlignment = Alignment.Center) {
        val major = if (vertical) maxHeight else maxWidth
        val count = if (major > 300.dp) 5 else 3
        val gesture = Modifier.fillMaxSize().pointerInput(options, count, vertical) {
            var remainder = 0f
            var index = 0
            val spacing = (if (vertical) size.height else size.width).toFloat() / count
            if (spacing <= 0) return@pointerInput
            detectDragGestures(onDragStart = { remainder = 0f; index = current }) { c, delta ->
                c.consume()
                remainder -= if (vertical) delta.y else delta.x
                val step = (remainder / spacing).toInt()
                if (step != 0) {
                    index = (index + step).coerceIn(options.indices)
                    select(index)
                    remainder -= step * spacing
                }
            }
        }
        @Composable fun cell(offset: Int, m: Modifier) {
            val idx = selected + offset
            val value = options.getOrNull(idx)
            Box(m.combinedClickable(onLongClickLabel = tr("输入物距"), onLongClick = { a.editSubjectDistance() }, onClick = { if (value != null) select(idx) }),
                contentAlignment = Alignment.Center) {
                if (value != null) {
                    InstrumentText(if (vertical && value.isFinite()) distanceLabel(value).replace(" ", "\n") else distanceLabel(value),
                        style = TextStyle(color = if (offset == 0) amber else foreground.copy(alpha = .75f),
                            fontSize = (if (vertical) 11 else 13).sp, fontFamily = FontFamily.Monospace,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                    Canvas(if (vertical) Modifier.align(Alignment.CenterEnd).width(5.dp).fillMaxHeight()
                        else Modifier.align(Alignment.BottomCenter).height(5.dp).fillMaxWidth()) {
                        for (k in 0..2) {
                            val f = (k+.5f)/3
                            val short = if (k == 1) 0f else .55f
                            val start = if (vertical) Offset(size.width*short,size.height*f) else Offset(size.width*f,size.height*short)
                            val end = if (vertical) Offset(size.width,size.height*f) else Offset(size.width*f,size.height)
                            drawLine(if (offset == 0 && k == 1) amber else foreground.copy(alpha=.65f),start,end)
                        }
                    }
                }
            }
        }
        if (vertical) Column(gesture, horizontalAlignment = Alignment.CenterHorizontally) {
            ParallaxLabel("物距", foreground, 10)
            for (i in -(count/2)..count/2) cell(i, Modifier.weight(1f).fillMaxWidth())
        } else Column(gesture) {
            ParallaxLabel("被摄物至焦平面", foreground, 10, Modifier.padding(start=4.dp))
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (i in -(count/2)..count/2) cell(i, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}
