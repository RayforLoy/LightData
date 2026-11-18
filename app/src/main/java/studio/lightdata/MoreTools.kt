package studio.lightdata

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import kotlin.math.*

@Composable
private fun TextLine(text:String,color:Color=Color.White) {
    BasicText(text,style=TextStyle(color=color,fontSize=14.sp))
}
@Composable
private fun Choice(text:String, onClick:()->Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=12.dp,horizontal=8.dp)) { TextLine(text) }
}

@Composable
internal fun MoreToolsDialog(a:MainActivity) {
    val page=a.toolsPage
    fun back() {
        a.toolsPage=when(page) { "ground","flash" -> "lab"; "lab","shots" -> "more"; else -> "" }
    }
    Dialog(onDismissRequest={ back() },properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxSize().background(Color(0xff151617)).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.clickable { back() }.padding(12.dp)) { TextLine("‹ 返回",amber) }
                TextLine(when(page) { "shots"->"构图快照"; "lab"->"实验室"; "ground"->"对焦屏点测光"; "flash"->"闪光灯测光"; else->"更多" })
            }
            key(page) {
                Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth()) {
                    when(page) {
                        "ground" -> GroundLabPanel(a) { a.toolsPage="" }
                        "flash" -> FlashLabPanel(a)
                        "lab" -> {
                            Choice("对焦屏点测光") { a.toolsPage="ground" }
                            Choice("闪光灯测光") { a.toolsPage="flash" }
                        }
                        "shots" -> ShotsPanel(a)
                        else -> {
                            Choice("构图快照") { a.toolsPage="shots" }
                            Choice("实验室") { a.toolsPage="lab" }
                            OpticsCompensationControls(a)
                            Choice("导入测光确认音") { a.selectMeterSound() }
                            Choice("恢复 Sony α7 III 确认音") {
                                a.resetMeterSound()
                                android.app.AlertDialog.Builder(a)
                                    .setTitle("已恢复 Sony α7 III 确认音")
                                    .setMessage("Sony A7III Focus Beep\n录音：iainmccurdy · Freesound\n许可：CC BY 4.0")
                                    .setNeutralButton("录音来源") { _,_ ->
                                        a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://freesound.org/people/iainmccurdy/sounds/613889/")))
                                    }.setPositiveButton("完成",null).show()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShotsPanel(a:MainActivity) {
    val scope=rememberCoroutineScope()
    var items by remember { mutableStateOf(emptyList<FramingShot>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { items=withContext(Dispatchers.IO) { a.framingArchive.list() } }
    fun work(task:suspend ()->Unit) {
        if(busy)return
        busy=true
        scope.launch {
            try { task() } catch(e:java.io.IOException) {message=e.localizedMessage ?: "读写快照失败"}
            finally {busy=false}
        }
    }
    Choice(if(busy) "处理中…" else "保存当前构图") {
        val sample=a.sample
        if(sample==null) {message="等待取景画面";return@Choice}
        val live=if(sample.live!=null) a.latestLive ?: sample.live else null
        val format=a.format
        val focal=a.focal
        val rotation=a.windowManager.defaultDisplay.rotation
        val axes=if(a.frozen) a.frozenAxes else a.pose.axes(rotation,live?.image?.poseTimestampNs ?: 0)
        val level=if(a.frozen) a.frozenLevel else axes?.let(::levelReading) ?: a.pose.level(rotation)
        work {
            val shot=withContext(Dispatchers.IO) {
                a.framingArchive.save(live?.fullFrame() ?: sample.frame,format,focal,axes,level)
            }
            items=listOf(shot)+items
            message="已保存"
        }
    }
    if(message.isNotBlank()) TextLine(message,amber)
    if(a.overlayShot!=null) Choice("关闭当前叠影") { a.overlayShot=null; a.overlayBitmap=null }
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        for(percent in listOf(20,40,60)) Box(Modifier.clickable { a.overlayOpacity=percent/100f }.padding(6.dp)) {
            TextLine("$percent%",if(abs(a.overlayOpacity-percent/100f)<.01f) amber else Color.White)
        }
    }
    for(shot in items) {
        val angle=shot.roll?.let { " · 横滚 %.1f°".format(Math.toDegrees(it)) }.orEmpty()+
            shot.pitch?.let { " · 俯仰 %.1f°".format(Math.toDegrees(it)) }.orEmpty()
        Choice(shot.label+angle+" · 叠影") {
            work {
                val bitmap=withContext(Dispatchers.IO) {shot.bitmap()}
                a.overlayShot=shot
                a.overlayBitmap=bitmap
                a.toolsPage=""
            }
        }
    }
}

@Composable
internal fun FramingOverlay(a:MainActivity, frameWidth:Int, frameHeight:Int) {
    val shot=a.overlayShot ?: return
    val bitmap=a.overlayBitmap ?: return
    val image=remember(bitmap) { bitmap.asImageBitmap() }
    val wide=if(frameWidth>=frameHeight) max(a.format.w,a.format.h) else min(a.format.w,a.format.h)
    val high=if(frameWidth>=frameHeight) min(a.format.w,a.format.h) else max(a.format.w,a.format.h)
    Canvas(Modifier.fillMaxSize()) {
        val w=(size.width*shot.horizontalMm/shot.focal*a.focal/wide).roundToInt().coerceAtLeast(1)
        val h=(size.height*shot.verticalMm/shot.focal*a.focal/high).roundToInt().coerceAtLeast(1)
        clipRect {
            drawImage(image,dstOffset=IntOffset((size.width.toInt()-w)/2,(size.height.toInt()-h)/2),
                dstSize=IntSize(w,h),alpha=a.overlayOpacity)
        }
    }
}
