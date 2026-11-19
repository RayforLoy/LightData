package studio.lightdata

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*

@Composable
internal fun FaceMeterOverlay(a: MainActivity) {
    if (!a.faceMeterEnabled || a.scanActive) return
    val boxes = a.facesForOverlay.orEmpty()
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            boxes.forEachIndexed { index, face ->
                drawRect(if (index == 0) amber else Color.White.copy(alpha=.72f),
                    Offset((size.width*face.left).toFloat(),(size.height*face.top).toFloat()),
                    Size((size.width*face.width).toFloat(),(size.height*face.height).toFloat()),
                    style = Stroke((if(index==0) 2.dp else 1.dp).toPx()))
            }
        }
        BasicText(a.faceMeterStatus,Modifier.align(Alignment.TopStart).padding(6.dp)
            .background(Color.Black.copy(alpha=.58f)).padding(horizontal=4.dp,vertical=2.dp),
            style=TextStyle(color=if(boxes.isEmpty()) Color.White else amber,fontSize=10.sp))
    }
}
