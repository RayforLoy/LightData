package studio.lightdata

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

private enum class MoreItem(val caption: String) {
    FACE("人脸测光"), FULL("全屏取景"), PARALLAX("视差补偿"), OPTICS("光损补偿"),
    REPORTS("留档"), SHOTS("构图快照"), LAB("实验室")
}

@Composable
internal fun FloatingTools(a: MainActivity, landscape: Boolean) {
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
                        for (item in MoreItem.entries) {
                            val active = when (item) {
                                MoreItem.FACE -> a.faceMeterEnabled
                                MoreItem.FULL -> a.fullFinder.enabled
                                MoreItem.PARALLAX -> a.parallax.enabled
                                MoreItem.REPORTS -> a.reportEnabled
                                else -> false
                            }
                            val color = if (active) amber else Color.White
                            Column(Modifier.width(71.dp).heightIn(min = 58.dp).clickable(role = Role.Button) {
                                a.toolsPage = when (item) {
                                    MoreItem.FACE -> { a.toggleFaceMeter(); a.notifyUser(a.faceMeterStatus); "" }
                                    MoreItem.FULL -> { a.fullFinder.toggle(); "" }
                                    MoreItem.PARALLAX -> "parallax"
                                    MoreItem.OPTICS -> "optics"
                                    MoreItem.REPORTS -> "reports"
                                    MoreItem.SHOTS -> "shots"
                                    MoreItem.LAB -> "lab"
                                }
                            }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                MoreIcon(item, color)
                                Spacer(Modifier.height(3.dp))
                                BasicText(item.caption, style = TextStyle(fontSize = 12.sp, color = color))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreIcon(item: MoreItem, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val pen = w * .069f
        fun pt(x: Float,y: Float) = Offset(x*w,y*w)
        fun line(x: Float,y: Float,u: Float,v: Float) = drawLine(color,pt(x,y),pt(u,v),pen)
        fun box(x: Float,y: Float,width: Float,height: Float) =
            drawRect(color,pt(x,y),Size(w*width,w*height),style=Stroke(pen))
        when(item) {
            MoreItem.FACE -> {
                drawOval(color,pt(.18f,.08f),Size(w*.64f,w*.83f),style=Stroke(pen))
                for(x in listOf(.37f,.63f)) drawCircle(color,w*.044f,pt(x,.43f))
                line(.4f,.65f,.6f,.65f)
            }
            MoreItem.FULL -> for (x in listOf(.1f,.9f)) for (y in listOf(.14f,.86f)) {
                line(x,y,if(x<.5f) .34f else .66f,y); line(x,y,x,if(y<.5f) .37f else .63f)
            }
            MoreItem.PARALLAX -> { box(.08f,.34f,.53f,.52f); box(.37f,.12f,.53f,.52f) }
            MoreItem.OPTICS -> {
                drawCircle(color,w*.29f,pt(.38f,.45f),style=Stroke(pen))
                drawCircle(color,w*.29f,pt(.63f,.55f),style=Stroke(pen))
            }
            MoreItem.REPORTS -> { box(.2f,.09f,.6f,.82f); for(y in listOf(.35f,.53f,.7f)) line(.32f,y,.66f,y) }
            MoreItem.SHOTS -> {
                box(.07f,.2f,.86f,.62f); drawCircle(color,w*.20f,pt(.5f,.51f),style=Stroke(pen))
            }
            MoreItem.LAB -> {
                val flask = Path().apply {
                    moveTo(.39f*w,.12f*w); lineTo(.39f*w,.41f*w); lineTo(.12f*w,.85f*w)
                    lineTo(.88f*w,.85f*w); lineTo(.61f*w,.41f*w); lineTo(.61f*w,.12f*w)
                }
                drawPath(flask,color,style=Stroke(pen)); line(.31f,.12f,.69f,.12f); line(.25f,.65f,.75f,.65f)
            }
        }
    }
}
