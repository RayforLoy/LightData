package studio.lightdata

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.*

private val reportSpotColors = intArrayOf(0xffffce58.toInt(), 0xff55ccff.toInt(), 0xffff85bb.toInt(),
    0xff68e3a8.toInt(), 0xffba9dff.toInt(), 0xffff9864.toInt())
internal fun reportSpotColor(id: Int) = reportSpotColors.getOrNull(id-1)
    ?: Color.HSVToColor(floatArrayOf(((id*137.508)%360).toFloat(),.55f,1f))

internal fun drawReportHistogram(canvas: Canvas, area: RectF, report: MeterReport) {
    val bins = report.histogram ?: return
    if (bins.isEmpty() || report.histogramMaxEv <= report.histogramMinEv) return
    val unit = area.width() / 1080f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.rgb(26,29,32)
    canvas.drawRect(area,paint)
    val left = area.left + 20*unit
    val right = area.right - 20*unit
    val top = area.top + 46*unit
    val bottom = area.bottom - 42*unit
    val w = right-left
    val h = bottom-top
    val span = report.histogramMaxEv-report.histogramMinEv
    fun x(ev: Double) = left + ((ev-report.histogramMinEv)/span).toFloat()*w
    paint.textSize = 22*unit
    paint.color = Color.WHITE
    canvas.drawText("场景 EV100 · 像素" + (if(report.logCounts) "（对数）" else "（线性）") + "    /    胶片密度",left,top-14*unit,paint)
    canvas.save()
    canvas.clipRect(left,top,right,bottom)
    val peak = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
    val binWidth = w/bins.size
    for ((index,count) in bins.withIndex()) {
        val fraction = if(report.logCounts) ln1p(count.toDouble())/ln1p(peak.toDouble()) else count.toDouble()/peak
        paint.color = Color.rgb(172,179,180)
        canvas.drawRect(left+index*binWidth,bottom-h*fraction.toFloat(),left+(index+1)*binWidth,bottom,paint)
    }
    val curve = report.histogramCurve
    if(curve != null && curve.size>1) {
        val path = Path()
        for ((i,value) in curve.withIndex()) {
            val px = left + w*(i+.5f)/curve.size
            val py = bottom-h*value.toFloat().coerceIn(0f,1f)
            if(i==0) path.moveTo(px,py) else path.lineTo(px,py)
        }
        paint.color = Color.rgb(255,189,89)
        paint.strokeWidth = 3*unit
        paint.style = Paint.Style.STROKE
        canvas.drawPath(path,paint)
        paint.style = Paint.Style.FILL
    }
    for ((ev,color) in listOf(report.lowEv to Color.rgb(109,216,160),report.middleEv to Color.WHITE,report.highEv to Color.rgb(236,117,106))) {
        paint.strokeWidth = 3*unit
        paint.color = color
        canvas.drawLine(x(ev),top,x(ev),bottom,paint)
    }
    for (point in report.spots) {
        val at = x(point.ev)
        paint.strokeWidth = 2*unit
        paint.color = reportSpotColor(point.id)
        canvas.drawLine(at,top,at,bottom,paint)
        paint.textSize = 19*unit
        canvas.drawText("#${point.id}", at+2*unit, top+19*unit,paint)
    }
    canvas.restore()
    paint.color = Color.LTGRAY
    paint.textSize = 21*unit
    for (tick in ceil(report.histogramMinEv/2).toInt()..floor(report.histogramMaxEv/2).toInt()) {
        val label = (tick*2).toString()
        val origin = (x(tick*2.0)-paint.measureText(label)/2).coerceIn(left,right-paint.measureText(label))
        canvas.drawText(label,origin,area.bottom-10*unit,paint)
    }
}
