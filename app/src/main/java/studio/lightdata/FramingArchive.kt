package studio.lightdata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.*

internal data class FramingShot(val file: File, val time: Long, val format: Format, val focal: Double,
                               val horizontalMm: Double, val verticalMm: Double,
                               val roll: Double?, val pitch: Double?, val axes: FloatArray?) {
    val label: String get() = SimpleDateFormat("MM-dd HH:mm:ss",Locale.ROOT).format(Date(time)) +
        " · " + format.name + " · " + isoLabel(focal) + " mm"
    fun bitmap(): Bitmap = DataInputStream(file.inputStream().buffered()).use {
        it.readUTF()
        BitmapFactory.decodeStream(it) ?: throw IOException("无法读取构图快照")
    }
}

internal class FramingArchive(private val dir: File) {
    fun list(): List<FramingShot> = dir.listFiles { f -> f.extension=="shot" }.orEmpty()
        .mapNotNull { runCatching { loadHeader(it) }.getOrNull() }.sortedByDescending { it.time }

    private fun loadHeader(file: File): FramingShot {
        val m = DataInputStream(file.inputStream().buffered()).use { JSONObject(it.readUTF()) }
        fun number(key: String) = if (m.has(key)) m.getDouble(key) else null
        val axes=m.optJSONArray("axes")?.let { v -> FloatArray(9) { v.getDouble(it).toFloat() } }
        return FramingShot(file,m.getLong("time"),Format(m.getString("format"),m.getDouble("w"),m.getDouble("h")),
            m.getDouble("focal"),m.getDouble("horizontal"),m.getDouble("vertical"),number("roll"),number("pitch"),axes)
    }

    fun save(frame: Frame, format: Format, focal: Double, axes: FloatArray?, level: LevelReading?): FramingShot {
        dir.mkdirs()
        val temp=File.createTempFile("capture-", ".tmp", dir)
        val file=File(dir,UUID.randomUUID().toString()+".shot")
        val wide=if (frame.w>=frame.h) max(format.w,format.h) else min(format.w,format.h)
        val high=if (frame.w>=frame.h) min(format.w,format.h) else max(format.w,format.h)
        val meta=JSONObject().put("time",System.currentTimeMillis()).put("format",format.name)
            .put("w",format.w).put("h",format.h).put("focal",focal).put("horizontal",wide).put("vertical",high)
        level?.let { meta.put("roll",it.rollRadians).put("pitch",it.pitchRadians) }
        axes?.let { meta.put("axes",JSONArray(it.toList())) }
        try {
            val bitmap=Bitmap.createBitmap(frame.pixels,frame.w,frame.h,Bitmap.Config.ARGB_8888)
            try {
                DataOutputStream(temp.outputStream().buffered()).use {
                    it.writeUTF(meta.toString())
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) throw IOException("图像编码失败")
                }
            } finally { bitmap.recycle() }
            if (!temp.renameTo(file)) throw IOException("保存构图快照失败")
            return loadHeader(file)
        } finally { temp.delete() }
    }
}
