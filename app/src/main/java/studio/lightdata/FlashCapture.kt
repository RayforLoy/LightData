package studio.lightdata

import android.hardware.camera2.*
import android.os.Handler
import android.view.Surface
import java.util.Locale
import kotlin.math.*

/** All session state lives on the camera handler. Pair sample grids with physical results by timestamp. */
internal class FlashCapture(
    private val camera: CameraDevice, private val session: CameraCaptureSession,
    private val surface: Surface, private val lens: Lens, private val handler: Handler,
    private val pulse: UsbFlashPulse, private val filmIso: Double,
    private val curve: (YuvEncoding)->DoubleArray, private val progress: (String)->Unit,
    private val finish: (String)->Unit,
) {
    private val isoRange=requireNotNull(lens.c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE])
    private val times=requireNotNull(lens.c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE])
    private var time=times.clamp(250_000_000L)
    private var iso=isoRange.clamp(100)
    private var attempt=1
    private var active=true
    private val shots=mutableListOf<FlashFrame>()
    private val grids=linkedMapOf<Long,Pair<DoubleArray,YuvEncoding>>()
    private var expected=0L
    private var meta: CaptureResult?=null
    private var sequence=0
    private val tried=mutableSetOf<Pair<Int,Long>>()

    fun start() {
        check(time>=65_000_000L) { "镜头最长快门不足 65 ms，无法同步 USB 引闪" }
        session.stopRepeating()
        pulse.start { if(active) capture() }
        handler.postDelayed({ if(active && sequence==0) finish("USB 音频时钟准备超时") },4000)
    }

    fun close() { active=false; pulse.close(); grids.clear() }
    fun image(ts: Long, image: Rig.Packet) {
        if(!active) return
        grids[ts]=image.grid() to image.encoding
        while(grids.size>5) grids.remove(grids.keys.first())
        consume()
    }
    private fun capture() {
        if (!active) return
        val token=++sequence
        val illuminated=shots.size==1
        val phase=listOf("测环境光","同步引闪","复核环境光")[shots.size]
        progress("第 $attempt 组 · $phase · 手机 ISO $iso")
        expected=0L
        grids.clear()
        meta=null
        try {
            val req=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME,time)
                set(CaptureRequest.SENSOR_SENSITIVITY,iso)
                if(lens.c[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE]==true) set(CaptureRequest.CONTROL_AWB_LOCK,true)
                set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
                if(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST in lens.c.availableCaptureRequestKeys)
                    lens.c[CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE]?.let {
                        set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,it.clamp(100))
                    }
            }
            session.capture(req.build(),object:CameraCaptureSession.CaptureCallback(){
                override fun onCaptureStarted(s: CameraCaptureSession,r: CaptureRequest,timestamp: Long,frameNumber: Long) {
                    if(active && token==sequence && illuminated) pulse.arm(timestamp+(time*.72).toLong())
                }
                override fun onCaptureCompleted(s: CameraCaptureSession,r: CaptureRequest,total: TotalCaptureResult) {
                    if(!active || token!=sequence) return
                    meta=total.physicalCameraResults[lens.id] ?: total
                    expected=meta?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0
                    consume()
                }
                override fun onCaptureFailed(s: CameraCaptureSession,r: CaptureRequest,f:CaptureFailure) {
                    if(active && token==sequence) finish("镜头拒绝了此次手动曝光")
                }
            },handler)
            handler.postDelayed({ if(active && token==sequence && expected!=-1L) finish("未收到带实测元数据的同步画面") },2200)
        } catch(e:Exception) { finish("曝光请求失败 · "+(e.localizedMessage ?: "镜头不可用")) }
    }

    private fun consume() {
        val result=meta ?: return
        val (grid,encoding)=grids[expected] ?: return
        val stamp=expected
        val t=result[CaptureResult.SENSOR_EXPOSURE_TIME]
        val gain=result[CaptureResult.SENSOR_SENSITIVITY]
        val aperture=result[CaptureResult.LENS_APERTURE] ?: lens.c[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]?.firstOrNull()
        if(t==null || gain==null || aperture==null || t<=0 || gain<=0 || aperture<=0 ||
            abs(log2(t/time.toDouble()))>.25 || abs(log2(gain/iso.toDouble()))>.25) {
            finish("相机未按请求曝光，无法换算闪光读数")
            return
        }
        if(shots.size==1) {
            val skew=result[CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW]
            if(pulse.presentationNs()==0L && android.os.SystemClock.elapsedRealtimeNanos()<stamp+t+350_000_000L) {
                val token=sequence
                handler.postDelayed({ if(active && sequence==token) consume() },15)
                return
            }
            if(skew==null || !flashFitsAllRows(stamp,skew,t,pulse.presentationNs())) {
                finish("USB 脉冲未落在全画幅曝光窗口，无法测量")
                return
            }
        }
        val boost=(result[CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST] ?: 100)/100.0
        shots.add(FlashFrame(grid,curve(encoding),gain*boost,aperture.toDouble(),t))
        expected=-1
        meta=null
        if(shots.size<3) { capture(); return }
        val measured=flashReading(shots[0],shots[1],shots[2],filmIso)
        measured.aperture?.let {
            finish(String.format(Locale.US,"反射式闪光估测  f/%.1f · ISO %s",it,isoLabel(filmIso)))
            return
        }
        tried.add(iso to time)
        val next=when(measured.issue) {
            FlashIssue.CLIPPED -> isoRange.clamp(max(1,iso/4))
            FlashIssue.WEAK -> isoRange.clamp(min(Int.MAX_VALUE.toLong(),iso.toLong()*4).toInt())
            else -> iso
        }
        // Shortening shutter reduces ambient only; it cannot attenuate a brief flash.
        val ambientClipped=listOf(shots[0],shots[2]).any { frame ->
            frame.gray.count { it>=.975 }>.02*frame.gray.size
        }
        val nextTime=if (next==iso && measured.issue==FlashIssue.CLIPPED && ambientClipped)
            times.clamp(max(65_000_000L,time/2)) else time
        if(attempt>=3 || (next to nextTime) in tried) {
            finish(when(measured.issue) {
                FlashIssue.CLIPPED -> "画面饱和，请降低灯功率或拉远闪光灯"
                FlashIssue.WEAK -> "没有检测到足够的闪光增量，请检查引闪连接及回电"
                else -> "环境光或构图变化过大，请固定相机后重测"
            })
        } else {
            attempt++
            iso=next
            time=nextTime
            shots.clear()
            pulse.reset()
            progress("等待闪光灯回电 · 4 秒后重新测量")
            handler.postDelayed({ if(active) capture() },4000)
        }
    }
}
