package studio.lightdata

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

internal fun usbFlashOutputs(ctx: Context): List<AudioDeviceInfo> =
    ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
        it.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET)
    }

/** Stream silence first. Queue the pulse only after confirming the DAC route and presentation clock. */
internal class UsbFlashPulse(ctx: Context, deviceId: Int, private val handler: Handler,
                             private val onError: (String)->Unit) : Closeable {
    private val device = usbFlashOutputs(ctx).firstOrNull { it.id == deviceId }
        ?: error("未连接选定的 USB 音频输出")
    private val rate = 48000
    private val running = AtomicBoolean(true)
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(max(4800,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)))
        .build()
    private val silent = ShortArray(240)
    @Volatile private var plannedNs = 0L
    @Volatile private var pulseFrame = -1L
    @Volatile private var confirmedNs = 0L
    @Volatile private var pulseUnderruns = 0
    private var worker: Thread? = null

    init {
        if (!track.setPreferredDevice(device)) {
            track.release()
            error("系统拒绝路由至选定 USB 小尾巴")
        }
    }

    fun start(ready: ()->Unit) {
        track.play()
        worker=Thread({
            val timestamp=AudioTimestamp()
            var written=0L
            var notified=false
            val deadline=SystemClock.elapsedRealtime()+3000
            try {
                while(running.get()) {
                    val clock=track.getTimestamp(timestamp) && timestamp.framePosition>0 &&
                        System.nanoTime()-timestamp.nanoTime in -100_000_000L..200_000_000L
                    val routed=track.routedDevice?.id==device.id
                    if (!notified && clock && routed) {
                        notified=true
                        handler.post { if(running.get()) ready() }
                    } else if ((!notified && SystemClock.elapsedRealtime()>deadline) || (notified && !routed)) {
                        error("USB 输出路由或音频时钟不可用")
                    }
                    val plan=plannedNs
                    if (plan!=0L && pulseFrame<0 && clock && routed) {
                        // Camera REALTIME uses elapsedRealtimeNanos; AudioTimestamp uses MONOTONIC.
                        val mono=plan-SystemClock.elapsedRealtimeNanos()+System.nanoTime()
                        val frame=timestamp.framePosition+((mono-timestamp.nanoTime)*rate/1e9).roundToLong()
                        check(frame>=written) { "USB 音频延迟超过当前快门窗口" }
                        pulseUnderruns=track.underrunCount
                        pulseFrame=frame
                    }
                    val start=pulseFrame
                    val buffer=if (start>=0 && written<start+240 && written+240>start) {
                        check(routed && running.get()) { "USB 输出已断开" }
                        ShortArray(240) { i ->
                            val j=written+i-start
                            if(j in 0L until 240L) (24000*sin(2*PI*1000*j/rate)).toInt().toShort() else 0
                        }
                    } else silent
                    if (start>=0 && clock && timestamp.framePosition>=start+240 && confirmedNs==0L) {
                        check(track.underrunCount==pulseUnderruns) { "音频缓冲中断，触发时间无效" }
                        confirmedNs=timestamp.nanoTime+((start-timestamp.framePosition)*1e9/rate).roundToLong()+
                            SystemClock.elapsedRealtimeNanos()-System.nanoTime()
                    }
                    val count=track.write(buffer,0,buffer.size,AudioTrack.WRITE_NON_BLOCKING)
                    check(count>=0) { "USB 输出写入失败" }
                    written+=count
                    if (count==0) Thread.sleep(2)
                }
            } catch (e: Exception) {
                if(running.get()) handler.post { if(running.get()) onError(e.message ?: "引闪失败") }
            } finally {
                runCatching { track.pause() }
                runCatching { track.flush() }
                track.release()
            }
        },"LightData USB sync").also { it.start() }
    }

    fun arm(timeNs: Long) { plannedNs=timeNs }
    fun presentationNs(): Long = confirmedNs
    fun reset() { plannedNs=0; pulseFrame=-1; confirmedNs=0 }
    override fun close() {
        if(running.getAndSet(false)) {
            if(worker==null) track.release() else worker?.interrupt()
        }
    }
}
