package studio.lightdata

import android.app.AlertDialog
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.Executors

internal enum class FeedbackCue(val title: String, val defaultName: String, val raw: Int) {
    METER("测光确认音", "Sony α7 III 确认音", R.raw.sony_focus),
    ARCHIVE("留档快门音", "Sony α7 III 快门音", R.raw.sony_shutter),
    ADD("添加测光点", "上行双音（合成）", R.raw.spot_on),
    REMOVE("删除测光点", "下行双音（合成）", R.raw.spot_off),
}

internal fun feedbackAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

/** Keep decoding, permission checks and audio route queries out of the touch path. */
internal class SoundFeedback(private val a: MainActivity) {
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(feedbackAudioAttributes()).build()
    private val manager = a.getSystemService(AudioManager::class.java)
    private val usbTypes = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
    private fun usbConnected() = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in usbTypes }
    // A flash sync adapter can trigger when any sound is routed to USB.
    private var usb = usbConnected()
    private val route = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { usb = usbConnected(); if (usb) stop() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { usb = usbConnected() }
    }
    private val defaults = mutableMapOf<FeedbackCue, Int>()
    private val samples = mutableMapOf<FeedbackCue, Int>()
    private val ready = mutableSetOf<Int>()
    private val loaded = mutableMapOf<Int,(Boolean)->Unit>()
    private val revision = mutableMapOf<FeedbackCue,Int>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var stream = 0
    private fun key(cue: FeedbackCue) = "feedbackFile." + cue.name
    private val dir = File(a.filesDir, "feedback").apply { mkdirs() }
    private fun previousFile(cue: FeedbackCue) = a.prefs.getString(key(cue),null)?.let { File(dir,it) }
    private fun forgetLegacy() {
        val old = a.prefs.getString("customSoundUri", null) ?: return
        a.prefs.edit().remove("customSoundUri").apply()
        runCatching { a.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    init {
        manager.registerAudioDeviceCallback(route, main)
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) ready.add(id)
            loaded.remove(id)?.invoke(status == 0)
        }
        for (cue in FeedbackCue.entries) {
            defaults[cue] = pool.load(a,cue.raw,1)
            previousFile(cue)?.takeIf { it.exists() }?.let { file ->
                val id = pool.load(file.absolutePath,1)
                loaded[id] = { if (it && previousFile(cue) == file && samples[cue] == null) samples[cue] = id else unload(id) }
            }
        }
        a.prefs.getString("customSoundUri",null)?.let { legacy ->
            if (previousFile(FeedbackCue.METER) == null) import(FeedbackCue.METER,Uri.parse(legacy),preview=false)
        }
    }

    fun play(cue: FeedbackCue) {
        if (closed || !a.soundEnabled || usb) return
        val id = samples[cue] ?: defaults[cue] ?: return
        if (id in ready) stream = pool.play(id,1f,1f,Int.MAX_VALUE,0,1f)
    }
    fun stop() { if (stream != 0) { pool.stop(stream); stream = 0 } }
    private fun unload(id: Int) { ready.remove(id); pool.unload(id) }

    fun reset(cue: FeedbackCue) {
        revision[cue] = (revision[cue] ?: 0) + 1
        samples.remove(cue)?.let(::unload)
        val old = previousFile(cue)
        a.prefs.edit().remove(key(cue)).apply()
        old?.delete()
        if (cue == FeedbackCue.METER) forgetLegacy()
        play(cue)
    }

    fun import(cue: FeedbackCue, uri: Uri, preview: Boolean = true) {
        val token = (revision[cue] ?: 0) + 1
        revision[cue] = token
        worker.execute {
            val candidate = runCatching {
                val file = File.createTempFile(cue.name + "-", ".sound", dir)
                try {
                    requireNotNull(a.contentResolver.openInputStream(uri)).use { input ->
                        file.outputStream().use { output ->
                            val bytes = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val n = input.read(bytes)
                                if (n < 0) break
                                total += n
                                require(total <= 8 * 1024 * 1024) { "音频文件过大" }
                                output.write(bytes,0,n)
                            }
                        }
                    }
                    val reader = MediaMetadataRetriever()
                    val duration = try {
                        reader.setDataSource(file.absolutePath)
                        reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    } finally { reader.release() }
                    require(duration != null && duration in 1..5000) { "请选择 5 秒以内的音频" }
                    file
                } catch (e: Exception) { file.delete(); throw e }
            }
            main.post {
                val file = candidate.getOrNull()
                if (closed || token != revision[cue]) { file?.delete(); return@post }
                if (file == null) { if (preview) a.notifyUser(candidate.exceptionOrNull()?.localizedMessage ?: "无法读取音频"); return@post }
                val id = pool.load(file.absolutePath,1)
                if (id == 0) { file.delete(); if (preview) a.notifyUser("无法解码音频"); return@post }
                loaded[id] = { ok ->
                    if (ok && token == revision[cue]) {
                        val old = previousFile(cue)
                        samples.put(cue,id)?.let(::unload)
                        a.prefs.edit().putString(key(cue),file.name).apply()
                        old?.delete()
                        if (cue == FeedbackCue.METER) forgetLegacy()
                        if (preview) play(cue)
                    } else {
                        file.delete(); unload(id)
                        if (!ok && preview && !closed && token == revision[cue]) a.notifyUser("无法解码音频")
                    }
                }
            }
        }
    }

    fun close() {
        closed = true
        manager.unregisterAudioDeviceCallback(route)
        worker.shutdown()
        pool.setOnLoadCompleteListener(null)
        // Finish pending imports as cancelled to clean up temporary files.
        val pending = loaded.values.toList()
        loaded.clear()
        for (finish in pending) finish(false)
        pool.release()
    }
}

@Composable
internal fun SoundSettingsPanel(a: MainActivity) {
    for (cue in FeedbackCue.entries) {
        InstrumentText(cue.title, Modifier.padding(top=12.dp,bottom=3.dp), style=TextStyle(color=Color.White,fontSize=16.sp))
        Row(Modifier.fillMaxWidth()) {
            InstrumentText("导入音频",Modifier.clickable { a.selectSound(cue) }.padding(8.dp),
                style=TextStyle(color=amber,fontSize=14.sp))
            InstrumentText("恢复" + cue.defaultName, Modifier.clickable {
                a.soundFeedback.reset(cue)
                val source = when(cue) {
                    FeedbackCue.METER -> "613889"
                    FeedbackCue.ARCHIVE -> "613888"
                    else -> null
                }
                if (source != null) MeterDialog(a).setTitle("已恢复 " + cue.defaultName)
                    .setMessage("录音：iainmccurdy · Freesound\n许可：CC BY 4.0" +
                        if (cue == FeedbackCue.ARCHIVE) "\n截取一次快门并调整音量。" else "")
                    .setNeutralButton("录音来源") { _,_ -> a.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://freesound.org/people/iainmccurdy/sounds/$source/"))) }
                    .setPositiveButton("完成",null).show()
            }.padding(8.dp), style=TextStyle(color=amber,fontSize=14.sp))
        }
    }
}
