package studio.lightdata

import android.app.AlertDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*

internal class TemperatureOptions(private val a: MainActivity) {
    var enabled by mutableStateOf(a.prefs.getBoolean("cctEnabled",false))
        private set

    var referenceEnabled by mutableStateOf(enabled && a.prefs.getBoolean("cctReference",false))
        private set

    fun open() {
        val labels = listOf(
            if (enabled) "关闭 AWB 色温显示" else "开启 AWB 色温显示",
            if (referenceEnabled) "关闭色温参考点" else "开启色温参考点")
        a.choose("色温估测",labels) { index ->
            if (index==0) {
                enableDisplay(!enabled)
                if (!enabled) setReference(false)
            } else if (referenceEnabled) setReference(false)
            else MeterDialog(a).setTitle("色温参考点")
                .setMessage("AWB 显示当前镜头估算的光源色温。拖动取景器右上角的读数到同一光线下的灰卡或白色中性物体，可估算该处色温。彩色物体、混合光、过暗或过亮会影响结果；部分镜头缺少所需的标定数据。")
                .setPositiveButton("开启色温参考点") { _,_ ->
                    enableDisplay(true)
                    setReference(true)
                }.setNegativeButton("取消",null).show()
        }
    }

    private fun enableDisplay(active: Boolean) {
        enabled=active
        a.rig.estimateWhitePoint=active
        a.prefs.edit().putBoolean("cctEnabled",active).apply()
    }

    private fun setReference(active: Boolean) {
        referenceEnabled=active
        a.prefs.edit().putBoolean("cctReference",active).apply()
        if (!active) a.clearTemperatureReference()
    }
}

@Composable
internal fun TemperatureOverlay(a: MainActivity,modifier: Modifier = Modifier) {
    if (!a.cctEnabled || a.scanActive) return
    var gateSize by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    val kelvin = a.sample?.frame?.whitePoint?.kelvin
    BoxWithConstraints(modifier.fillMaxSize().onSizeChanged { gateSize = it }) {
        InstrumentText(kelvin?.let { "AWB ≈ ${it} K" } ?: "AWB · 暂无数据",
            Modifier.align(Alignment.TopEnd).widthIn(max=maxWidth*.50f).padding(end=5.dp,top=if (a.showFps) 29.dp else 5.dp)
                .onGloballyPositioned { origin=it.positionInParent() }
                .background(Color.Black.copy(alpha=.68f))
                .pointerInput(a.temperature.referenceEnabled) {
                    if (!a.temperature.referenceEnabled) return@pointerInput
                    detectDragGestures(
                        onDragStart={ cursor=origin+it },
                        onDragCancel={ cursor=null },
                        onDragEnd={
                            cursor?.let { p ->
                                if (p.x>=0 && p.y>=0 && p.x<gateSize.width && p.y<gateSize.height)
                                    a.placeTemperatureReference((p.x/gateSize.width).toDouble(),(p.y/gateSize.height).toDouble())
                            }
                            cursor=null
                        }
                    ) { change,delta ->
                        change.consume()
                        cursor=cursor?.plus(delta)
                    }
                }.padding(horizontal=6.dp,vertical=4.dp),
            style=TextStyle(color=Color.White,fontSize=11.sp))
        cursor?.let { center ->
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(amber,8.dp.toPx(),center,style=Stroke(2.dp.toPx()))
            }
        }
    }
}
