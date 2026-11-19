package studio.lightdata

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Standard EXIF APP1/TIFF. The platform ExifInterface writer quantizes ExposureTime
// to 1/10000 s; encode unsigned rationals directly to preserve fast shutter settings.
private data class TiffTag(val id:Int,val type:Int,val count:Int,val bytes:ByteArray)
private fun le(size:Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
private fun ascii(id:Int,s:String):TiffTag {
    val bytes=(s+"\u0000").toByteArray(Charsets.US_ASCII)
    return TiffTag(id,2,bytes.size,bytes)
}
private fun short(id:Int,x:Int) = TiffTag(id,3,1,le(2).putShort(x.toShort()).array())
private fun long(id:Int,x:Long) = TiffTag(id,4,1,le(4).putInt(x.toInt()).array())
private fun ratio(x:Double):Pair<Long,Long> {
    val precision = if(x == 0.0) 1L else minOf(1_000_000_000L,(2_147_483_647.0/abs(x)).toLong()).coerceAtLeast(1L)
    val num=(x*precision).roundToLong()
    var gcd=abs(num)
    var d=precision
    while(d!=0L) { val r=gcd%d; gcd=d; d=r }
    return num/gcd to precision/gcd
}
private fun rational(id:Int,values:List<Pair<Long,Long>>,signed:Boolean=false):TiffTag {
    val data=le(values.size*8)
    for ((num,den) in values) data.putInt(num.toInt()).putInt(den.toInt())
    return TiffTag(id,if(signed) 10 else 5,values.size,data.array())
}
private fun rational(id:Int,value:Double,signed:Boolean=false)=rational(id,listOf(ratio(value)),signed)
private fun dms(v:Double):List<Pair<Long,Long>> {
    // Round the complete coordinate before splitting it so seconds never become 60.
    val micros=(abs(v)*3600*1_000_000).roundToLong()
    return listOf(micros/3_600_000_000 to 1L,micros/60_000_000%60 to 1L,micros%60_000_000 to 1_000_000L)
}

internal fun reportExif(r:MeterReport):ByteArray {
    val date=Date(r.timeMs)
    val stamp=SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US).format(date)
    val offset=SimpleDateFormat("XXX",Locale.US).format(date).replace("Z","+00:00")
    val e=r.exposure
    val root=mutableListOf(ascii(0x0131,"LightData"),ascii(0x0132,stamp),short(0x0112,1))
    val exposure=mutableListOf(
        rational(0x829a,e.t),rational(0x829d,e.a),
        short(0x8822,when(r.mode) {"M"->1; "A"->3; "S"->4; else->0}),
        short(0x8827,e.iso.roundToInt().coerceIn(1,65535)),
        ascii(0x9003,stamp),ascii(0x9004,stamp),ascii(0x9011,offset),
        rational(0x9204,r.compensationEv,true),rational(0x920a,r.focalMm),rational(0xa215,e.iso),
        ascii(0x9291,"%03d".format(Locale.US,Math.floorMod(r.timeMs,1000L))),
        TiffTag(0x9000,7,4,"0232".toByteArray(Charsets.US_ASCII)),
    )
    if(e.iso >= 65535) {
        exposure.add(short(0x8830,3))
        exposure.add(long(0x8833,e.iso.roundToLong()))
    }
    val gps=mutableListOf<TiffTag>()
    r.location?.let {
        gps.add(TiffTag(0,1,4,byteArrayOf(2,3,0,0)))
        gps.add(ascii(1,if(it.latitude<0) "S" else "N"))
        gps.add(rational(2,dms(it.latitude)))
        gps.add(ascii(3,if(it.longitude<0) "W" else "E"))
        gps.add(rational(4,dms(it.longitude)))
        if(it.hasAltitude()) {
            gps.add(TiffTag(5,1,1,byteArrayOf(if(it.altitude<0) 1 else 0)))
            gps.add(rational(6,abs(it.altitude)))
        }
        if(it.hasAccuracy()) gps.add(rational(0x1f,it.accuracy.toDouble()))
    }
    fun directorySize(n:Int)=2 + n*12 + 4
    val rootStart=8
    val exposureStart=rootStart+directorySize(root.size+1+if(gps.isNotEmpty()) 1 else 0)
    root.add(long(0x8769,exposureStart.toLong()))
    val gpsStart=exposureStart+directorySize(exposure.size)
    if(gps.isNotEmpty()) root.add(long(0x8825,gpsStart.toLong()))
    var payload=gpsStart+if(gps.isNotEmpty()) directorySize(gps.size) else 0
    val dirs=listOf(rootStart to root,exposureStart to exposure,gpsStart to gps).filter { it.second.isNotEmpty() }
    val capacity=payload+dirs.sumOf { (_,tags) -> tags.sumOf { if(it.bytes.size>4) (it.bytes.size+1)/2*2 else 0 } }
    val data=le(capacity)
    data.putShort(0x4949.toShort()).putShort(42.toShort()).putInt(rootStart)
    for ((offsetInFile,tags) in dirs) {
        data.position(offsetInFile)
        data.putShort(tags.size.toShort())
        for(t in tags.sortedBy { it.id }) {
            data.putShort(t.id.toShort()).putShort(t.type.toShort()).putInt(t.count)
            if(t.bytes.size<=4) {
                data.put(t.bytes)
                repeat(4-t.bytes.size) { data.put(0.toByte()) }
            } else {
                data.putInt(payload)
                val next=data.position()
                data.position(payload)
                data.put(t.bytes)
                payload+=(t.bytes.size+1)/2*2
                data.position(next)
            }
        }
        data.putInt(0)
    }
    return byteArrayOf(0x45,0x78,0x69,0x66,0,0)+data.array()
}
