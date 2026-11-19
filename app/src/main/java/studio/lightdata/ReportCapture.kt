package studio.lightdata

import android.graphics.*
import android.location.Location
import kotlin.math.*

/** Run on the UI thread. Snapshot every setting before dispatching the immutable look to GL. */
internal fun MainActivity.captureMeterReport(location: Location?, complete: (Result<MeterReport>) -> Unit) {
    val measured = sample
    val view = previewView.get()
    val scene = reading
    if (measured == null || scene == null || view == null || !view.isAttachedToWindow || !view.holder.surface.isValid) {
        complete(Result.failure(IllegalStateException("等待有效取景和测光后再留档")))
        return
    }
    val time = System.currentTimeMillis()
    val frame = measured.frame
    val target = exposure
    val middle = target.ev + opticalLoss
    val filmAtTap = film
    val formatAtTap = format
    val focalAtTap = focal
    val modeAtTap = mode
    val compensation = if (mode == "M" || placedSpotId != null) 0.0 else compensationEv
    val atlas = if (useLut && preview) activeAtlas?.takeIf { it.entry.file == variant?.file } else null
    val look = Look(frame,response,frame.cameraEv-middle,filmAtTap,preview,mode=="M",style,atlas)
    val points = spots.toList()
    val chart = HistogramWindow.automatic(scene, middle, centerHistogramOnZone)
    val bins = chart.bins(measured.hist,response,frame.cameraEv,128)
    val maxDensity = ceil(filmAtTap.dots.maxOf { it.second }).coerceAtLeast(.001)
    val curve = DoubleArray(bins.size) {
        (filmAtTap.density(chart.left + chart.span * (it+.5)/bins.size - middle)/maxDensity).coerceIn(0.0,1.0)
    }
    val log = logCounts
    val overlay = overlayBitmap
    val shot = overlayShot
    val opacity = overlayOpacity
    val rotation = windowManager.defaultDisplay.rotation
    val level = if (frozen) frozenLevel else pose.level(rotation)
    val rec = reciprocity(filmAtTap.name,target.t)
    val rows = buildList {
        add("画幅" to "${formatAtTap.name}   ${n(formatAtTap.w)} × ${n(formatAtTap.h)} mm")
        val field = target()
        val horizontal = if(frame.w>=frame.h) max(field.x,field.y) else min(field.x,field.y)
        val vertical = if(frame.w>=frame.h) min(field.x,field.y) else max(field.x,field.y)
        add("标称视角（水平 × 垂直）" to "${n(Math.toDegrees(2*atan(horizontal)))}° × ${n(Math.toDegrees(2*atan(vertical)))}°")
        add("取景镜头" to "${current?.label ?: frame.id} · ${cameraChoice}选择")
        add("测光" to (if(single) "单次" else "连续") + (if(frozen) " · 画面已冻结" else ""))
        frame.sensorReading?.let { sensor ->
            val e = sensor.exposure
            add("手机采集参数" to "${timeLabel(e.t)} · f/${n(e.a)} · ISO ${isoLabel(e.iso)} · 后级增益 ${n(sensor.postRawBoost)}×")
        }
        if (faceMeterEnabled) add("人脸重点测光" to faceMeterStatus)
        add((if (faceMeterEnabled && !scanActive && frame.faceHistogram != null) "人脸测光" else "场景测光") to "${n(scene)} EV100")
        held?.let { add("锁定读数" to "${n(it)} EV100") }
        add("胶片 / 感光度" to "${filmAtTap.name} · 标称 ISO ${isoLabel(filmIso)} · " +
            if (isoLocked) "跟随胶片" else "手动 ISO")
        add("中灰与曝光界限" to "Zone V ${n(middle)} · 欠 ${n(middle+filmAtTap.low)} · 过 ${n(middle+filmAtTap.high)} EV100")
        add("相对测光偏差" to "${signed((held ?: scene)-middle)} EV")
        add("倒易律" to (rec.exposureSeconds?.let { timeLabel(it)+" · " }.orEmpty() + rec.label +
            if(rec.networkEstimate) " · 数据来源网络" else ""))
        add("光损补偿" to (if(useMeasuredLoss) "毛玻璃实测" else "几何计算") + " ${signed(opticalLoss)} EV")
        if (!useMeasuredLoss) add("滤镜 / 皮腔伸长量" to "${signed(filterStops)} EV · ${n(bellowsDrawMm)} mm")
        val styleName = listOf("彩色","黑白","Aerochrome","Lomo Purple").getOrNull(style).orEmpty()
        val previewName = when { !preview -> "关闭"; useLut -> lutStatus; else -> "亮度模拟" }
        if(rec.networkEstimate && rec.sourceUrl != null) add("倒易律数据来源" to rec.sourceUrl)
        add("取景预览" to "$previewName · $styleName" + (atlas?.entry?.let { " · ${it.description}" } ?: ""))
        add("取景分辨率" to "${frame.w} × ${frame.h} · 实时渲染倍率 ${n(previewScale)}×")
        add("逐镜头校准" to (cal?.let { "${it.channel} · ISO ${it.iso} · ${n(it.stops)} EV" } ?: "未校准 · 估测"))
        if(dimMeterWarning(scene,measured.hist)) add("测光提示" to "环境极暗，读数可能不准确")
        add("视差补偿" to if(parallax.enabled) {
            val distance = if(parallax.distanceMm.isInfinite()) "∞" else "${n(parallax.distanceMm)} mm"
            "X ${n(parallax.xMm)} / Y ${n(parallax.yMm)} / Z ${n(parallax.zMm)} mm · 物距 $distance"
        } else "关闭")
        add("拼接" to (measured.scan?.let { "${scanAlgorithm} · ${it.captures} 帧 · 覆盖 ${n(100*it.coverage)}%" }
            ?: if(measured.missing) "超出镜头可覆盖视角" else "单镜头取景"))
        level?.let { add("水平状态" to "横滚 ${n(Math.toDegrees(it.rollRadians))}° · 俯仰 ${n(Math.toDegrees(it.pitchRadians))}°") }
        shot?.let { add("构图叠影" to "${it.label} · ${n(100.0*opacity)}%") }
        placedSpotId?.let { add("测光点放置" to "#$it → Zone $placedZone") }
        for (point in points) add("测光点 #${point.id}" to "${n(point.ev)} EV100 · 相对中灰 ${signed(point.ev-middle)} EV · X ${n(100*point.x)}% / Y ${n(100*point.y)}%")
    }
    val gps = location?.let(::Location)
    view.renderReportFrame(look) { result ->
        complete(result.mapCatching { bitmap ->
            val canvas = Canvas(bitmap)
            if (overlay != null && shot != null) {
                val wide = if(frame.w>=frame.h) max(formatAtTap.w,formatAtTap.h) else min(formatAtTap.w,formatAtTap.h)
                val high = if(frame.w>=frame.h) min(formatAtTap.w,formatAtTap.h) else max(formatAtTap.w,formatAtTap.h)
                val halfW = (bitmap.width * shot.horizontalMm / shot.focal * focalAtTap / wide / 2).toFloat()
                val halfH = (bitmap.height * shot.verticalMm / shot.focal * focalAtTap / high / 2).toFloat()
                val x = bitmap.width*.5f
                val y = bitmap.height*.5f
                canvas.drawBitmap(overlay,null,RectF(x-halfW,y-halfH,x+halfW,y+halfH),Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha=(255*opacity).roundToInt() })
            }
            val radius = max(4f,min(bitmap.width,bitmap.height)*.014f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = max(1f,radius*.22f); textSize = 2.25f*radius }
            for (point in points) {
                val x = (point.x*bitmap.width).toFloat()
                val y = (point.y*bitmap.height).toFloat()
                paint.color = reportSpotColor(point.id)
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(x,y,radius,paint)
                paint.style = Paint.Style.FILL
                canvas.drawText("#${point.id}",x+radius,y,paint)
            }
            MeterReport(time,bitmap,target,focalAtTap,compensation,modeAtTap,rows,gps,bins,chart.left,chart.right,middle,
                middle+filmAtTap.low,middle+filmAtTap.high,log,curve,points)
        })
    }
}
