package studio.lightdata

import android.media.*
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*

@Composable
internal fun FlashLabPanel(a: MainActivity) {
    val audio=remember(a) { a.getSystemService(AudioManager::class.java) }
    var outputs by remember { mutableStateOf(usbFlashOutputs(a)) }
    var deviceId by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val selected=outputs.firstOrNull { it.id==deviceId }
    DisposableEffect(a) {
        val callback=object:AudioDeviceCallback() {
            fun update() {
                outputs=usbFlashOutputs(a)
                if(outputs.none { it.id==deviceId }) deviceId=null
            }
            override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>)=update()
            override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>)=update()
        }
        audio.registerAudioDeviceCallback(callback,Handler(Looper.getMainLooper()))
        onDispose {
            audio.unregisterAudioDeviceCallback(callback)
            a.rig.cancelFlash()
        }
    }

    Column(Modifier.fillMaxWidth().padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        FlashText("反射式闪光测光",18)
        FlashText("用灰卡填满取景框，在主界面确认构图后固定手机。结果按胶片 ISO "+isoLabel(a.manual.iso)+" 换算光圈。",13)
        FlashText("连接 USB 小尾巴和兼容 1 kHz 音频脉冲的引闪适配器，设置闪光灯手动输出。选中已接引闪器的音频输出。",13)
        if(outputs.isEmpty()) FlashText("未发现 USB 音频输出",14,amber)
        for(out in outputs) {
            BasicText((if(out.id==deviceId) "✓ " else "")+out.productName.toString()+" · USB "+out.id,
                Modifier.fillMaxWidth().clickable(enabled=!busy){ deviceId=out.id }.padding(8.dp),
                style=TextStyle(color=if(out.id==deviceId) amber else Color.White,fontSize=14.sp))
        }
        FlashText("每组测两张环境光、一张闪光。信号过弱或过强时会等待 4 秒回电再调整手机曝光，最多触发三次。",12)
        val enabled=busy || selected!=null
        Box(Modifier.background(if(enabled) amber else Color.DarkGray).clickable(enabled=enabled) {
            if(busy) {
                a.rig.cancelFlash()
            } else if(selected!=null) {
                busy=true
                status="准备 USB 同步"
                val cal=a.cal
                a.rig.measureFlash(selected.id,a.manual.iso,{ enc -> cal?.responseFor(enc) ?: defaultResponse },
                    { msg -> a.runOnUiThread { status=msg } },
                    { msg -> a.runOnUiThread { busy=false; status=msg } })
            }
        }.padding(10.dp)) { FlashText(if(busy) "取消测量" else "触发并测量",14,if(enabled) Color.Black else Color.LightGray) }
        if(status.isNotEmpty()) FlashText(status,15,amber)
    }
}

@Composable
private fun FlashText(s:String,size:Int,color:Color=Color.White) {
    BasicText(s,style=TextStyle(color=color,fontSize=size.sp))
}
