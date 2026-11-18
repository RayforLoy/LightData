package studio.lightdata

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale
import kotlin.math.pow

private data class GroundRun(
    val lens: String, val delaySeconds: Int, val reference: Spot?, val aperture: Double,
    val calibration: GroundCalibration?, val iso: Double, val film: String,
)

private fun storedGround(a: MainActivity, lens: String) = runCatching {
    val j = JSONObject(a.prefs.getString("groundGlass:$lens", null) ?: return@runCatching null)
    GroundCalibration(j.getDouble("offset"), j.getDouble("aperture")).takeIf {
        it.offsetEv.isFinite() && it.referenceAperture.isFinite() && it.referenceAperture > 0
    }
}.getOrNull()

private fun GroundRun.store(a: MainActivity, measured: Double): GroundCalibration {
    val c = GroundCalibration(requireNotNull(reference).ev - measured, aperture)
    a.prefs.edit().putString("groundGlass:$lens", JSONObject().put("offset", c.offsetEv)
        .put("aperture", aperture).toString()).apply()
    return c
}

private fun LiveCapture.groundEv(response: DoubleArray): Double? {
    val codes = IntArray(25) { i ->
        luma(image.pixel((image.w/2 + i%5 - 2).coerceIn(0,image.w-1),
            (image.h/2 + i/5 - 2).coerceIn(0,image.h-1)))
    }
    codes.sort()
    if (codes[12] !in 4..251) return null
    return ev + kotlin.math.log2(codes.sumOf { 2.0.pow(response[it]) } / codes.size)
}

@Composable
private fun GroundLine(text: String, action: (() -> Unit)? = null) {
    val style = TextStyle(color = if (action == null) Color.LightGray else Color(0xffffc86c), fontSize = 14.sp)
    BasicText(text, Modifier.fillMaxWidth().then(if (action == null) Modifier else
        Modifier.background(Color(0xff262626)).clickable(onClick = action))
        .padding(horizontal = 10.dp, vertical = 8.dp), style)
}

@Composable
internal fun GroundLabPanel(a: MainActivity, onBackToFinder: () -> Unit) {
    val lens = a.current?.id
    var calibration by remember(lens) { mutableStateOf(lens?.let { storedGround(a,it) }) }
    var aperture by remember(lens) { mutableDoubleStateOf(calibration?.referenceAperture ?: 8.0) }
    var workingStop by remember(lens) { mutableDoubleStateOf(a.exposure.a) }
    var seconds by remember { mutableIntStateOf(5) }
    var selected by remember(lens) { mutableStateOf<Spot?>(null) }
    var request by remember { mutableStateOf<GroundRun?>(null) }
    var status by remember(lens) { mutableStateOf("") }

    fun start(ref: Spot?) {
        if (lens == null || !a.allowed || a.runningCal || a.scanActive) {
            status = "请先开启取景并退出拼接或镜头校准"
            return
        }
        if (a.frozen) a.toggleFreeze()
        a.lastGroundMeasurement = null
        request = GroundRun(lens, seconds, ref, if (ref == null) workingStop else aperture, calibration, a.manual.iso, a.film.name)
    }

    GroundLine("校准时，在户外选一块均匀受光的远处表面。先用主界面点测光记录该表面，再将大画幅镜头对焦到无限远。将同一手机镜头贴在毛玻璃对应位置，保持光照和校准光圈稳定。")
    GroundLine("手机镜头 ${lens ?: "—"} · " + (calibration?.let {
        "已校准  f/${n(it.referenceAperture)} · %+.1f EV".format(Locale.ROOT,it.offsetEv)
    } ?: "待校准"))
    GroundLine("校准光圈  f/${n(aperture)}") {
        a.input("大画幅镜头校准时的光圈",aperture,.5..256.0) { aperture = it }
    }
    GroundLine(selected?.let { "室外参考  #${it.id} · ${n(it.ev)} EV100" } ?: "选择主界面测光点") {
        val spots = a.spots.filter { it.ev.isFinite() }
        if (!a.frozen || a.sample?.frame?.id != lens || spots.isEmpty() || a.sample?.scan != null) {
            status = "返回主界面，点击测光点并点选参考表面，再打开实验室"
        } else a.choose("室外参考点",spots.map { "#${it.id}   ${n(it.ev)} EV100" }) { selected = spots[it] }
    }
    GroundLine("黑屏倒计时  ${seconds} 秒") {
        val delays = listOf(3,5,10,15,30)
        a.choose("盖上冠布所需时间",delays.map { "$it 秒" }) { seconds = delays[it] }
    }
    GroundLine("采集无限远毛玻璃参考") {
        selected?.let { start(it) } ?: run { status = "请先选择室外参考点" }
    }
    GroundLine("实测时将大画幅镜头收至拍摄光圈，手机镜头贴住毛玻璃上要放在中灰的位置。开始后盖好冠布；返回键可取消。结果已包含皮腔伸长造成的光损失。更换毛玻璃、镜头系统或贴合方式后请重新校准。")
    GroundLine("此次测量的大画幅拍摄光圈  f/${n(workingStop)}") {
        a.input("实际贴屏测量时的大画幅光圈",workingStop,.5..256.0) { workingStop = it }
    }
    GroundLine("黑屏并测量") {
        if (calibration == null) status = "请先完成无限远校准" else start(null)
    }
    if (status.isNotEmpty()) GroundLine(status)
    a.lastGroundMeasurement?.takeIf { it.lens == lens }?.let { last ->
        GroundLine("回取景器测同一物点 · 毛玻璃基准 ${n(last.equivalentEv)} EV100") {
            onBackToFinder()
            a.beginGroundComparison(last.equivalentEv)
        }
        GroundLine("用主界面点测光选取刚才在毛玻璃上测到的同一物点；两次读数之差可代替皮腔与镜前滤镜补偿。")
    }
    val run = request ?: return
    LaunchedEffect(run) {
        // Turning the device display off pauses Camera2 on many phones. Use a dedicated
        // black, minimally lit window, and keep the sensor session active under the cloth.
        val black = Dialog(a,android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        black.setContentView(View(a).apply { setBackgroundColor(android.graphics.Color.BLACK) })
        black.setOnCancelListener { request = null; status = "已取消" }
        black.setCanceledOnTouchOutside(false)
        black.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            attributes = attributes.apply { screenBrightness = 0f }
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        black.show()
        try {
            fun active() = a.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && a.current?.id == run.lens && !a.frozen
            val deadline = SystemClock.elapsedRealtime() + run.delaySeconds * 1000L
            while (active() && SystemClock.elapsedRealtime() < deadline) delay(75)
            var previous = a.latestLive
            val burst = GroundBurst(previous?.timestamp ?: 0L)
            val timeout = SystemClock.elapsedRealtime() + 4500
            var result: Double? = null
            while (active() && SystemClock.elapsedRealtime() < timeout && result == null) {
                delay(90)
                val live = a.latestLive ?: continue
                if (live === previous || live.id != run.lens) continue
                previous = live
                // Camera2 may expose an unspecified IMU time base. A new LiveCapture
                // still identifies a distinct preview packet even when its timestamp is zero.
                val timestamp = live.timestamp.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()
                result = burst.observe(timestamp, live.groundEv(a.cal?.responseFor(live.image.encoding) ?: defaultResponse))
            }
            status = when {
                !active() -> "已取消，镜头或应用状态已改变"
                result == null -> "未取得稳定有效的中心读数；请检查毛玻璃亮度、贴合与遮光后重试"
                run.reference != null -> {
                    calibration = run.store(a,result)
                    "校准完成 · 镜头 ${run.lens}"
                }
                else -> {
                    val c = requireNotNull(run.calibration)
                    val equivalentEv = c.equivalentSceneEv(result,run.aperture)
                    a.lastGroundMeasurement = GroundSpotMeasurement(run.lens,equivalentEv,run.aperture)
                    val t = c.seconds(result,run.iso)
                    val correction = reciprocity(run.film,t)
                    val calculated = correction.exposureSeconds
                    val corrected = if (calculated != null && correction.status == ReciprocityStatus.CORRECTED)
                        " · 倒易补偿 ${timeLabel(calculated)}" + (if (correction.networkEstimate) " · 数据来源网络" else "") else if(correction.status==ReciprocityStatus.UNPUBLISHED)
                        " · ${correction.label}" else ""
                    "ISO ${isoLabel(run.iso)} · 实拍光圈下测得 ${timeLabel(t)}$corrected\n毛玻璃 ${n(result)} EV100 · 拍摄光圈 f/${n(run.aperture)} 基准 ${n(equivalentEv)} EV100"
                }
            }
            if (active() && result!=null) a.playMeterSound()
        } finally {
            black.dismiss()
            request = null
        }
    }
}
