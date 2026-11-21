package studio.lightdata

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

internal enum class FinderTool(val caption: String) {
    PREVIEW("预览"), SPOT("测光点"), REPORTS("留档"), OPTICS("光损补偿"), SOUND("声音"),
    FACE("人脸测光"), DYNAMIC("动态测光点"), CCT("色温"), FULL("全屏取景"), PARALLAX("视差补偿"),
    SHOTS("构图快照"), LAB("实验室")
}

internal class ToolLayout(private val a: MainActivity) {
    private fun read(key: String, default: List<FinderTool>): List<FinderTool> =
        a.prefs.getString(key, null)?.split(',')?.mapNotNull { name ->
            FinderTool.entries.find { it.name == name }
        }?.distinct() ?: default

    private val defaults = listOf(FinderTool.PREVIEW, FinderTool.SPOT, FinderTool.REPORTS, FinderTool.OPTICS)
    var toolbar by mutableStateOf(read("toolbarItems", defaults).take(4))
        private set
    var more by mutableStateOf((read("moreItems", emptyList()) + FinderTool.entries).distinct() - toolbar.toSet())
        private set

    private fun save(bar: List<FinderTool>, menu: List<FinderTool>) {
        toolbar = bar
        more = menu
        a.prefs.edit().putString("toolbarItems", bar.joinToString(",") { it.name })
            .putString("moreItems", menu.joinToString(",") { it.name }).apply()
    }

    fun move(item: FinderTool) {
        if (item in toolbar) save(toolbar - item, more + item)
        else if (toolbar.size < 4) save(toolbar + item, more - item)
        else {
            val slots = toolbar.toList()
            MeterDialog(a).setTitle("替换工具栏中的一项")
                .setItems(slots.map { it.caption }.toTypedArray()) { _, index ->
                    val old = slots[index]
                    save(toolbar.map { if (it == old) item else it }, more.map { if (it == item) old else it })
                }.setNegativeButton("取消", null).show()
        }
    }

    fun reorder(item: FinderTool, step: Int) {
        val bar = item in toolbar
        val group = (if (bar) toolbar else more).toMutableList()
        val index = group.indexOf(item)
        if (index + step !in group.indices) return
        group.add(index + step, group.removeAt(index))
        if (bar) save(group, more) else save(toolbar, group)
    }
    fun reset() = save(defaults, FinderTool.entries - defaults.toSet())
}

internal fun FinderTool.active(a: MainActivity): Boolean = when (this) {
    FinderTool.PREVIEW -> a.preview
    FinderTool.SPOT -> a.frozen
    FinderTool.SOUND -> a.soundEnabled
    FinderTool.FACE -> a.faceMeterEnabled
    FinderTool.DYNAMIC -> a.dynamicEnabled
    FinderTool.CCT -> a.cctEnabled
    FinderTool.FULL -> a.fullFinder.enabled
    FinderTool.PARALLAX -> a.parallax.enabled
    FinderTool.REPORTS -> a.reportEnabled
    FinderTool.OPTICS -> a.opticalLoss != 0.0 || a.converterRatio != 1.0 || a.useMeasuredLoss
    else -> false
}

internal fun FinderTool.open(a: MainActivity) {
    a.toolsPage = ""
    when (this) {
        FinderTool.PREVIEW -> a.preview = !a.preview
        FinderTool.SPOT -> if (!a.runningCal) a.toggleFreeze()
        FinderTool.SOUND -> a.toggleMeterSound()
        FinderTool.FACE -> { a.toggleFaceMeter(); a.notifyUser(a.faceMeterStatus) }
        FinderTool.DYNAMIC -> a.toggleDynamicSpots()
        FinderTool.CCT -> a.openColorTemperatureOptions()
        FinderTool.FULL -> a.fullFinder.toggle()
        FinderTool.PARALLAX -> a.toolsPage = "parallax"
        FinderTool.OPTICS -> a.toolsPage = "optics"
        FinderTool.REPORTS -> a.toolsPage = "reports"
        FinderTool.SHOTS -> a.toolsPage = "shots"
        FinderTool.LAB -> a.toolsPage = "lab"
    }
}

@Composable
internal fun FloatingTools(a: MainActivity, landscape: Boolean, layout: ToolLayout) {
    val rail = landscape && !a.fullFinder.enabled
    val corner = if (rail) Alignment.BottomStart else Alignment.TopEnd
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(start = if (rail) 58.dp else 4.dp, end = 6.dp,
            top = if (rail) 4.dp else 55.dp, bottom = 8.dp)) {
        Box(Modifier.align(corner)) {
            Popup(alignment = corner, onDismissRequest = { a.toolsPage = "" },
                properties = PopupProperties(focusable = true)) {
                val shape = RoundedCornerShape(10.dp)
                Column(Modifier.widthIn(max = 304.dp).shadow(8.dp, shape)
                    .background(Color(0xff202123), shape).border(1.dp, Color.DarkGray, shape)
                    .verticalScroll(rememberScrollState()).padding(6.dp)) {
                    FlowRow {
                        for (item in layout.more) {
                            val color = if (item.active(a)) amber else Color.White
                            Column(Modifier.width(71.dp).heightIn(min = 58.dp)
                                .clickable(role = Role.Button) { item.open(a) }.padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                FinderToolIcon(item, color, item.active(a))
                                Spacer(Modifier.height(3.dp))
                                InstrumentText(item.caption, style = TextStyle(fontSize = 12.sp, color = color))
                            }
                        }
                    }
                    InstrumentText("编辑工具栏与更多", Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        a.toolsPage = "toolEditor"
                    }.padding(12.dp), style = TextStyle(fontSize = 14.sp, color = amber))
                }
            }
        }
    }
}

@Composable
internal fun ToolEditor(a: MainActivity, layout: ToolLayout) {
    Dialog(onDismissRequest = { a.toolsPage = "more" }) {
        Column(Modifier.background(Color(0xff202123), RoundedCornerShape(10.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InstrumentText("编辑工具", Modifier.weight(1f), style = TextStyle(color = Color.White, fontSize = 18.sp))
                EditorButton("恢复默认") { layout.reset() }
                EditorButton("完成") { a.toolsPage = "" }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                for (bar in listOf(true, false)) {
                    val group = if (bar) layout.toolbar else layout.more
                    InstrumentText(if (bar) "工具栏  ${group.size}/4" else "更多", Modifier.padding(vertical = 10.dp),
                        style = TextStyle(color = amber, fontSize = 14.sp))
                    for ((index, item) in group.withIndex()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            InstrumentText(item.caption, Modifier.weight(1f), style = TextStyle(color = Color.White, fontSize = 14.sp))
                            EditorButton("↑", index > 0, "${item.caption} 上移") { layout.reorder(item, -1) }
                            EditorButton("↓", index < group.lastIndex, "${item.caption} 下移") { layout.reorder(item, 1) }
                            EditorButton(if (bar) "到更多" else "到工具栏") { layout.move(item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorButton(label: String, enabled: Boolean = true, description: String = label, action: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).widthIn(min = 38.dp).semantics { contentDescription = tr(description) }
        .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center) {
        InstrumentText(label, style = TextStyle(color = if (enabled) amber else Color.Gray, fontSize = 13.sp))
    }
}

@Composable
internal fun FinderToolIcon(item: FinderTool, color: Color, active: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val pen = w * .069f
        fun pt(x: Float,y: Float) = Offset(x*w,y*w)
        fun line(x: Float,y: Float,u: Float,v: Float) = drawLine(color,pt(x,y),pt(u,v),pen)
        fun box(x: Float,y: Float,width: Float,height: Float) =
            drawRect(color,pt(x,y),Size(w*width,w*height),style=Stroke(pen))
        when(item) {
            FinderTool.PREVIEW -> {
                drawOval(color, pt(.05f,.23f),Size(w*.9f,w*.54f),style=Stroke(pen))
                drawCircle(color,w*.14f)
            }
            FinderTool.SPOT -> {
                drawCircle(color,w*.29f,style=Stroke(pen))
                line(0f,.5f,.3f,.5f); line(.7f,.5f,1f,.5f)
                line(.5f,0f,.5f,.3f); line(.5f,.7f,.5f,1f)
            }
            FinderTool.SOUND -> {
                line(.16f,.39f,.16f,.65f); line(.16f,.39f,.48f,.18f)
                line(.48f,.18f,.48f,.83f); line(.48f,.83f,.16f,.65f)
                if (active) drawArc(color,-52f,104f,false,pt(.17f,.15f),Size(w*.73f,w*.72f),style=Stroke(pen))
                else line(.07f,.95f,.91f,.07f)
            }
            FinderTool.DYNAMIC -> {
                box(.08f,.23f,.28f,.28f); box(.58f,.48f,.3f,.3f)
                line(.36f,.37f,.74f,.37f); line(.74f,.37f,.74f,.48f)
            }
            FinderTool.CCT -> {
                drawCircle(color,w*.16f,pt(.5f,.74f),style=Stroke(pen))
                line(.42f,.58f,.42f,.18f); line(.58f,.58f,.58f,.18f); line(.42f,.18f,.58f,.18f)
            }
            FinderTool.FACE -> {
                drawOval(color,pt(.18f,.08f),Size(w*.64f,w*.83f),style=Stroke(pen))
                for(x in listOf(.37f,.63f)) drawCircle(color,w*.044f,pt(x,.43f))
                line(.4f,.65f,.6f,.65f)
            }
            FinderTool.FULL -> for (x in listOf(.1f,.9f)) for (y in listOf(.14f,.86f)) {
                line(x,y,if(x<.5f) .34f else .66f,y); line(x,y,x,if(y<.5f) .37f else .63f)
            }
            FinderTool.PARALLAX -> { box(.08f,.34f,.53f,.52f); box(.37f,.12f,.53f,.52f) }
            FinderTool.OPTICS -> {
                drawCircle(color,w*.29f,pt(.38f,.45f),style=Stroke(pen))
                drawCircle(color,w*.29f,pt(.63f,.55f),style=Stroke(pen))
            }
            FinderTool.REPORTS -> { box(.2f,.09f,.6f,.82f); for(y in listOf(.35f,.53f,.7f)) line(.32f,y,.66f,y) }
            FinderTool.SHOTS -> {
                box(.07f,.2f,.86f,.62f); drawCircle(color,w*.20f,pt(.5f,.51f),style=Stroke(pen))
            }
            FinderTool.LAB -> {
                val flask = Path().apply {
                    moveTo(.39f*w,.12f*w); lineTo(.39f*w,.41f*w); lineTo(.12f*w,.85f*w)
                    lineTo(.88f*w,.85f*w); lineTo(.61f*w,.41f*w); lineTo(.61f*w,.12f*w)
                }
                drawPath(flask,color,style=Stroke(pen)); line(.31f,.12f,.69f,.12f); line(.25f,.65f,.75f,.65f)
            }
        }
    }
}
