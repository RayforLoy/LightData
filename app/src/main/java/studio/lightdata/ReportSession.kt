package studio.lightdata

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.TimeUnit

internal class ReportSession(private val activity: MainActivity) {
    var enabled by mutableStateOf(activity.prefs.getBoolean("reportEnabled", true))
        private set
    var locationEnabled by mutableStateOf(activity.prefs.getBoolean("reportLocation", false))
        private set

    private var foreground = false
    private var busy = false
    private var enableAfterStorage = false
    private val locations = activity.getSystemService(LocationManager::class.java)
    private var fix: Location? = null
    private fun granted(permission: String) =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun storageAllowed() = Build.VERSION.SDK_INT >= 29 ||
        (granted(Manifest.permission.WRITE_EXTERNAL_STORAGE) && granted(Manifest.permission.READ_EXTERNAL_STORAGE))
    private fun locationAllowed() =
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private val storage = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (storageAllowed()) {
            if (enableAfterStorage) enable(true)
        } else activity.notifyUser("Android 9 需要存储权限才能在系统相册保存和管理报告")
        enableAfterStorage = false
    }
    private val position = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        setLocation(locationAllowed())
        if (!locationAllowed()) activity.notifyUser("未获得位置权限，报告仍可保存")
    }

    fun requestStorage() {
        if (!storageAllowed()) storage.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
    fun enable(value: Boolean) {
        if (value && !storageAllowed()) {
            enableAfterStorage = true
            requestStorage()
            return
        }
        enabled = value
        activity.prefs.edit().putBoolean("reportEnabled", value).apply()
        listen()
    }
    fun enableLocation(value: Boolean) {
        if (value && !locationAllowed())
            position.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        else setLocation(value)
    }
    private fun setLocation(value: Boolean) {
        locationEnabled = value
        activity.prefs.edit().putBoolean("reportLocation", value).apply()
        listen()
    }

    fun resume() {
        foreground = true
        if (locationEnabled && !locationAllowed()) setLocation(false) else listen()
    }
    fun pause() {
        foreground = false
        listen()
    }
    private fun ageMs(location: Location): Long {
        val now = SystemClock.elapsedRealtimeNanos()
        // Some GNSS providers report epoch time in the elapsed-realtime field.
        return if (location.elapsedRealtimeNanos in 1..now)
            TimeUnit.NANOSECONDS.toMillis(now - location.elapsedRealtimeNanos)
        else System.currentTimeMillis() - location.time
    }
    private fun consider(next: Location) {
        if (ageMs(next) !in 0L..120_000L) return
        val old = fix
        if (old == null || ageMs(old) > 120_000 || next.time > old.time + 15_000 ||
            (next.time >= old.time - 15_000 && next.accuracy <= old.accuracy)) fix = Location(next)
    }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = consider(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Android 9 compatibility")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    private fun listen() {
        runCatching { locations.removeUpdates(listener) }
        fix = null
        if (!enabled || !foreground || !locationEnabled || !locationAllowed()) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) runCatching {
            locations.getLastKnownLocation(provider)?.let(::consider)
            locations.requestLocationUpdates(provider, 5000L, 5f, listener, Looper.getMainLooper())
        }
    }

    fun capture() {
        if (!enabled || busy) return
        if (!storageAllowed()) { requestStorage(); return }
        if (activity.sample == null) {
            activity.notifyUser("等待读取画面和测光值")
            return
        }
        activity.soundFeedback.play(FeedbackCue.ARCHIVE)
        val location = fix?.takeIf { locationEnabled && locationAllowed() && ageMs(it) in 0L..120_000L }?.let(::Location)
        busy = true
        activity.captureMeterReport(location) { result ->
            result.onFailure {
                busy = false
                activity.notifyUser("留档失败：" + (it.localizedMessage ?: "无法读取画面"))
            }.onSuccess { report ->
                Thread {
                    val saved = runCatching { ReportStore(activity.applicationContext).save(report) }
                    report.image.recycle()
                    activity.runOnUiThread {
                        busy = false
                        if (!activity.isDestroyed) {
                            saved.onSuccess {
                                activity.notifyUser(if (locationEnabled && location == null) "报告已存入相册 · 暂无位置" else "报告已存入相册")
                            }.onFailure { activity.notifyUser("报告保存失败：" + (it.localizedMessage ?: "无法写入相册")) }
                        }
                    }
                }.start()
            }
        }
    }
}
