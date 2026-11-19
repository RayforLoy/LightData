package studio.lightdata

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

internal data class MeterReport(
    val timeMs: Long, val image: Bitmap, val exposure: Exposure, val focalMm: Double,
    val compensationEv: Double, val mode: String, val rows: List<Pair<String,String>>,
    val location: Location? = null, val histogram: IntArray? = null, val histogramMinEv: Double = 0.0,
    val histogramMaxEv: Double = 0.0, val middleEv: Double = 0.0, val lowEv: Double = 0.0,
    val highEv: Double = 0.0, val logCounts: Boolean = true, val histogramCurve: DoubleArray? = null,
    val spots: List<Spot> = emptyList(),
)
internal data class ReportItem(val uri: Uri, val name: String, val timeMs: Long)

internal class ReportStore(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val album = Environment.DIRECTORY_PICTURES + "/LightData/Reports/"
    private val collection get() = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    @Suppress("DEPRECATION")
    private val legacyDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"LightData/Reports")

    /** Called on a worker thread. The supplied preview is owned by the caller. */
    fun save(report: MeterReport): ReportItem {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(report.timeMs))
        val name = "LightData-${stamp}-${UUID.randomUUID().toString().take(8)}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, report.timeMs)
        }
        val jpeg = File.createTempFile("meter-report-", ".jpg", context.cacheDir)
        try {
            val composed = render(report)
            try {
                jpeg.outputStream().buffered().use {
                    if (!composed.compress(Bitmap.CompressFormat.JPEG, 94, it)) throw IOException("报告编码失败")
                }
            } finally { composed.recycle() }
            val exif = reportExif(report)
            fun write(stream: OutputStream) {
                stream.buffered().use { output ->
                    jpeg.inputStream().buffered().use { input ->
                        check(input.read() == 0xff && input.read() == 0xd8)
                        output.write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte()))
                        val segmentSize = exif.size + 2
                        output.write(segmentSize ushr 8)
                        output.write(segmentSize and 255)
                        output.write(exif)
                        input.copyTo(output)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, album)
                values.put(MediaStore.Images.Media.IS_PENDING, 1)
                val uri = resolver.insert(collection, values) ?: throw IOException("系统相册无法创建图片")
                try {
                    write(resolver.openOutputStream(uri) ?: throw IOException("系统相册无法写入图片"))
                    check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
                    return ReportItem(uri, name, report.timeMs)
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri,null,null) }
                    throw t
                }
            }
            val dir = legacyDir
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建留档相册")
            val temporary = File.createTempFile(".pending-", ".tmp", dir)
            val final = File(dir,name)
            try {
                write(temporary.outputStream())
                if (!temporary.renameTo(final)) throw IOException("无法保存报告")
                @Suppress("DEPRECATION")
                values.put(MediaStore.Images.Media.DATA, final.path)
                val uri = try {
                    resolver.insert(collection,values) ?: throw IOException("系统相册无法登记图片")
                } catch (t: Throwable) { final.delete(); throw t }
                return ReportItem(uri,name,report.timeMs)
            } finally { temporary.delete() }
        } finally { jpeg.delete() }
    }

    fun list(): List<ReportItem> {

        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN)
        val path: String
        val condition: String
        if (Build.VERSION.SDK_INT >= 29) {
            path = album
            condition = "relative_path = ? AND is_pending = 0"
        } else {
            path = legacyDir.absolutePath + "/%"
            condition = "_data LIKE ?"
        }
        return buildList {
            resolver.query(collection,columns,"$condition AND mime_type = ?",arrayOf(path,"image/jpeg"),"datetaken DESC, _id DESC")?.use { c ->
                while (c.moveToNext()) add(ReportItem(ContentUris.withAppendedId(collection,c.getLong(0)),c.getString(1),c.getLong(2)))
            } ?: throw IOException("系统相册暂不可用")
        }
    }
    fun delete(item: ReportItem): Boolean = resolver.delete(item.uri,null,null) == 1

    private fun render(report: MeterReport): Bitmap {
        val width = 1200
        val margin = 60f
        val inner = width - 2 * margin
        val ratio = min(inner / report.image.width, 1450f / report.image.height)
        val photoW = report.image.width * ratio
        val photoH = report.image.height * ratio
        val showHistogram = report.histogram?.isNotEmpty() == true
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { textSize = 27f }
        val rows = mutableListOf("曝光模式 / 曝光补偿" to ("${report.mode} 档 · ${signed(report.compensationEv)} EV"))
        rows.addAll(report.rows)
        report.location?.let { gps ->
            val accuracy = if (gps.hasAccuracy()) "（±${n(gps.accuracy.toDouble())} m）" else ""
            rows.add("位置" to String.format(Locale.US,"%.6f°, %.6f° %s",gps.latitude,gps.longitude,accuracy))
        }
        val labelWidth = 225f
        fun wrap(text:String, space:Float): List<String> = buildList {
            for (line in text.lines()) {
                var offset=0
                if (line.isEmpty()) add("")
                while (offset < line.length) {
                    val len=paint.breakText(line,offset,line.length,true,space,null).coerceAtLeast(1)
                    add(line.substring(offset,offset+len))
                    offset+=len
                }
            }
        }
        val wrapped = rows.map { (label,value) -> wrap(label,labelWidth-15) to wrap(value,inner-labelWidth) }
        val rowsTop = 226f + photoH + if(showHistogram) 284f else 0f
        val height = (rowsTop + wrapped.sumOf { maxOf(it.first.size,it.second.size)*37 + 24 } + 38).roundToInt()
        val bmp = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(245,244,240))
        fun text(value:String,x:Float,y:Float,size:Float,color:Int=Color.rgb(28,31,32)) {
            paint.textSize=size; paint.color=color
            canvas.drawText(value,x,y,paint)
        }
        text("LightData  /  测光留档",margin,65f,42f)
        val date=SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(Date(report.timeMs))
        paint.textSize=24f
        text(date,width-margin-paint.measureText(date),63f,24f)
        val exposure=report.exposure
        text("${if (exposure.t < 0.4) timeLabel(exposure.t) else n(exposure.t)} s    f/${n(exposure.a)}    ISO ${isoLabel(exposure.iso)}    ${n(report.focalMm)} mm",margin,140f,36f)
        val left=(width-photoW)/2
        paint.color=Color.WHITE
        canvas.drawBitmap(report.image,null,RectF(left,172f,left+photoW,172f+photoH),paint)
        if (showHistogram) {
            val top=190f + photoH
            drawReportHistogram(canvas,RectF(margin,top,width-margin,top+260f),report)
        }
        var y=rowsTop
        for ((label,value) in wrapped) {
            paint.color=Color.rgb(205,205,199)
            canvas.drawLine(margin,y-26f,width-margin,y-26f,paint)
            label.forEachIndexed { i,line -> text(line,margin,y+12+i*37,27f,Color.DKGRAY) }
            value.forEachIndexed { i,line -> text(line,margin+labelWidth,y+12+i*37,27f) }
            y += maxOf(label.size,value.size)*37+24
        }
        return bmp
    }
}
