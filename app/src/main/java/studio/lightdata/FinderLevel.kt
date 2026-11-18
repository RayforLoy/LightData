package studio.lightdata

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.*

@Composable
internal fun BoxScope.FinderLevel(activity: MainActivity) {
    val reading = remember(activity) { mutableStateOf<LevelReading?>(null) }
    LaunchedEffect(activity) {
        while (true) {
            reading.value = activity.pose.level(activity.windowManager.defaultDisplay.rotation)
            delay(65L)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val attitude = reading.value ?: return@Canvas
        val anchor = Offset(center.x, size.height * .78f)
        val shadow = Color.Black.copy(alpha = .8f)
        val reference = Color.White.copy(alpha = .85f)
        val stroke = 1.6.dp.toPx()

        fun segment(color: Color, from: Offset, to: Offset) {
            drawLine(shadow, from, to, stroke + 1.8.dp.toPx())
            drawLine(color, from, to, stroke)
        }

        if (attitude.overhead) {
            val radius = min(17.dp.toPx(), size.minDimension * .095f)
            val distance = hypot(attitude.bubbleX, attitude.bubbleY)
            val tolerance = Math.toRadians(1.5)
            val travel = radius * .77f
            // Expand the central few degrees while keeping the bubble inside the outer ring.
            val scale = travel / max(sin(Math.toRadians(9.0)), distance)
            val bubble = anchor + Offset((attitude.bubbleX * scale).toFloat(), (attitude.bubbleY * scale).toFloat())
            val accent = if (distance <= sin(tolerance)) green else amber
            drawCircle(shadow, radius, anchor, style = Stroke(stroke + 1.8.dp.toPx()))
            drawCircle(reference, radius, anchor, style = Stroke(stroke))
            drawCircle(reference, radius * .23f, anchor, style = Stroke(1.dp.toPx()))
            drawCircle(shadow, radius * .20f + 1.dp.toPx(), bubble)
            drawCircle(accent, radius * .20f, bubble)
        } else {
            val width = min(34.dp.toPx(), size.width * .16f)
            val roll = attitude.rollRadians
            val direction = Offset(cos(roll).toFloat(), sin(roll).toFloat())
            val accent = if (abs(sin(roll)) <= sin(Math.toRadians(1.5))) green else amber
            for (side in listOf(-1f, 1f)) {
                val end = Offset(side * width, 0f)
                segment(reference, anchor + end * .78f, anchor + end)
            }
            segment(accent, anchor - direction * (width * .68f), anchor + direction * (width * .68f))
        }
    }
}
