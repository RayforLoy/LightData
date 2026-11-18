package studio.lightdata
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assume
import org.junit.Assert.assertTrue
import java.util.zip.ZipFile
import org.json.JSONObject
import java.io.File
class RecordedPanoramaTest {
 @Test fun replayOptionalPrivateCaptureAcrossBackends() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val file=File(ctx.filesDir,"replay.zip")
  Assume.assumeTrue(file.exists())
  ZipFile(file).use { zip ->
   val numbers = zip.entries().asSequence().map { it.name }
       .filter { it.endsWith(".jpg") }.map { it.removeSuffix(".jpg").toInt() }.sorted().toList()
   fun read(i: Int): Pair<Frame,JSONObject> {
    val json=JSONObject(zip.getInputStream(zip.getEntry("$i.json")).bufferedReader().readText())
    val bitmap=zip.getInputStream(zip.getEntry("$i.jpg")).use { BitmapFactory.decodeStream(it) }
    val pixels=IntArray(bitmap.width*bitmap.height)
    bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    val f=Frame(bitmap.width,bitmap.height,pixels,json.getDouble("cameraEV"),json.getString("camera"))
    bitmap.recycle()
    return f to json
   }
   val rows=numbers.map { i -> JSONObject(zip.getInputStream(zip.getEntry("$i.json")).bufferedReader().readText()) }
   fun axes(j: JSONObject)=FloatArray(9) { j.getJSONArray("axes").getDouble(it).toFloat() }
   val acceptedIndices=rows.map { it.getInt("captures") }.distinct()
   val gaps=acceptedIndices.zipWithNext().any { (a,b) -> b>a+1 }
   if (gaps) {
    Log.w("ScanReplay","Incomplete accepted history; replay cannot reproduce on-device decisions.")
    return
   }
   val origin=axes(rows[0])
   val identity=floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
   for(mode in InstrumentationRegistry.getArguments().getString("mode")?.let { listOf(it) } ?: listOf("LK","ORB","ECC","自动")) {
    val first=read(numbers.first())
    val source=first.second.getJSONArray("source")
    val target=first.second.getJSONArray("target")
    val mosaic=Mosaic(Field(target.getDouble(0),target.getDouble(1)),identity,first.first,defaultResponse,OpenCvScanAligner.forMode(mode))
    val session=ScanSession(first.first,Field(target.getDouble(0),target.getDouble(1)),origin,defaultResponse,OpenCvScanAligner.forMode(mode))
    val times=mutableListOf<Double>()
    val work=mutableListOf<Double>()
    var accepted=0
    val decisions=mutableMapOf<String,Int>()
    val details=org.json.JSONArray()
    for(number in numbers) {
     val (f,j)=read(number)
     val sensor=axes(j)
     val pose=FloatArray(9) { i -> (0..2).sumOf { k -> origin[k*3+i/3].toDouble()*sensor[k*3+i%3] }.toFloat() }
     session.capture(Field(source.getDouble(0),source.getDouble(1)),sensor,j.getLong("timeMs"),null,j.getDouble("exposureMs")) { f }
     val before=mosaic.coverage
     val t=System.nanoTime()
     mosaic.add(f,Field(source.getDouble(0),source.getDouble(1)),pose)
     details.put(JSONObject().put("frame",number).put("decision",mosaic.lastDecision).put("checks",org.json.JSONArray(mosaic.lastOverlapChecks)).put("coverage",mosaic.coverage).put("recordedAccepted",j.optBoolean("accepted")))
     decisions[mosaic.lastDecision]=(decisions[mosaic.lastDecision] ?: 0)+1
     times+=(System.nanoTime()-t)/1e6
     if(mosaic.lastDecision !in listOf("ready","no_new_area")) work+=times.last()
     if(mosaic.coverage>before)accepted++
    }

    File(ctx.filesDir,"replay-decisions.json").writeText(details.toString())
    val output=mosaic.view(identity)
    val bmp=android.graphics.Bitmap.createBitmap(output.pixels,output.w,output.h,android.graphics.Bitmap.Config.ARGB_8888)
    File(ctx.filesDir,"replay-panorama.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    bmp.recycle()
    Log.i("ScanReplay",mode+" accepted="+accepted+" coverage="+mosaic.coverage+" medianMs="+times.sorted()[times.size/2]+" workMedianMs="+work.sorted().getOrNull(work.size/2)+" workP90Ms="+work.sorted().getOrNull(work.size*9/10)+" session="+session.guide(0).coverage+" decisions="+decisions)
    session.close()
    mosaic.close()
   }
  }
 }
}
