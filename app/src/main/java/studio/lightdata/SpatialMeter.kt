package studio.lightdata

import android.content.Context
import android.hardware.*
import android.os.SystemClock
import androidx.compose.runtime.*
import java.util.concurrent.Executors
import kotlin.math.*

/** Short-term motion cue only; image matches remain authoritative. */
private class TranslationCue(private val a: MainActivity): SensorEventListener {
    private val sm = a.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private var active = false
    private var last = 0L
    private var quiet = 0.0
    private val velocity = DoubleArray(3)
    private val position = DoubleArray(3)
    fun enable(on: Boolean) {
        if (active == on) return
        active = on
        last=0; quiet=0.0; velocity.fill(0.0); position.fill(0.0)
        if (on && sensor != null) sm.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME)
        else sm.unregisterListener(this)
    }
    fun location() = position.copyOf()
    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    override fun onSensorChanged(e: SensorEvent) {
        val dt = (e.timestamp-last)*1e-9
        last = e.timestamp
        if (dt !in .001..0.1) { velocity.fill(0.0); return }
        val rot = a.pose.matrix ?: return
        val acc = e.values
        val norm = sqrt((acc[0]*acc[0]+acc[1]*acc[1]+acc[2]*acc[2]).toDouble())
        quiet = if (norm < .25) quiet+dt else 0.0
        if (quiet > .16) { velocity.fill(0.0); return }
        for (i in 0..2) {
            val k = 3*i
            val v = velocity[i]
            val accel = if (norm < .12) 0.0 else rot[k]*acc[0]+rot[k+1]*acc[1]+rot[k+2]*acc[2].toDouble()
            velocity[i] = (v+accel*dt)*exp(-dt*.45)
            position[i] += .5*(v+velocity[i])*dt
        }
    }
}

internal class SpatialMeter(private val a: MainActivity) {
    private val motion by lazy { TranslationCue(a) }
    private val worker = Executors.newSingleThreadExecutor()
    private class Entry(val anchor: SpatialAnchor, val location: DoubleArray, var view: TrackedSpot)
    private val entries = mutableMapOf<Int,Entry>()
    var views by mutableStateOf(emptyList<TrackedSpot>())
        private set
    private var displayPose by mutableStateOf<GatePose?>(null)
    fun advance(pose: GatePose?) { if (entries.isNotEmpty()) displayPose=pose }
    fun onScreen(): List<TrackedSpot> {
        val pose = displayPose ?: return views.filter { it.inView }
        return views.mapNotNull { v ->
            val ray = v.worldRay ?: return@mapNotNull null
            val p = pose.project(ray) ?: return@mapNotNull null
            if (p.x !in 0.0..1.0 || p.y !in 0.0..1.0) null
            else v.copy(spot=v.spot.copy(x=p.x,y=p.y))
        }
    }
    private var nextId = 1
    private var pending = false
    private var lastFrame: Frame? = null
    private fun publish() {
        views = entries.values.map { it.view }
        motion.enable(entries.isNotEmpty())
        if (entries.isEmpty()) { nextId=1; lastFrame=null }
    }
    fun reset() { entries.clear(); publish() }
    fun clear(reference: Boolean) {
        entries.keys.removeAll { (it==0) == reference }
        publish()
    }
    fun close() { reset(); worker.shutdownNow() }
    fun place(x: Double, y: Double, reference: Boolean) {
        if (a.frozen || a.scanActive || a.runningCal) { a.notifyUser("请在实时取景中放置"); return }
        if (!reference) {
            val old = onScreen().firstOrNull { it.spot.id != 0 &&
                hypot(it.spot.x-x,it.spot.y-y)<.042 }
            if (old != null) {
                a.soundFeedback.play(FeedbackCue.REMOVE)
                entries.remove(old.spot.id); publish(); return
            }
        }
        val live = a.previewView.get()?.renderedLive ?: a.latestLive ?: return
        val pose = live.trackingPose ?: run { a.notifyUser("等待姿态传感器"); return }
        val frame = live.fullFrame()
        val id = if(reference) 0 else nextId
        val anchor = SpatialAnchor.create(id,frame,pose,x,y) ?: return
        val ev=frame.spot(x,y,a.response)
        if (!ev.isFinite()) return
        if (!reference) { nextId++; a.soundFeedback.play(FeedbackCue.ADD) }
        motion.enable(true)
        entries[id] = Entry(anchor,motion.location(),TrackedSpot(Spot(id,x,y,ev),
            worldRay=pose.ray(x,y),kelvin=if (reference) frame.localKelvin(x,y) else null))
        publish()
    }
    fun measure(f: Frame, pose: GatePose?, response: DoubleArray) {
        if (entries.isEmpty() || pending || lastFrame === f || worker.isShutdown) return
        lastFrame=f
        if (pose == null) {
            entries.values.forEach { it.view = it.view.copy(stale=true,inView=false) }
            publish(); return
        }
        val location = motion.location()
        val now = SystemClock.elapsedRealtime()
        val batch = entries.values.map { it to it.view }
        pending=true
        worker.execute {
            val result = batch.map { (entry,previous) ->
                val moved = (0..2).sumOf { (entry.location[it]-location[it]).pow(2) } > .25*.25
                entry to entry.anchor.update(f,pose,previous,response,now,moved,f::localKelvin)
            }
            a.runOnUiThread {
                pending=false
                if (a.isDestroyed) return@runOnUiThread
                var changed=false
                for ((entry,view) in result) {
                    if (entries[entry.anchor.id] !== entry) continue
                    if (view.alpha <= 0f) entries.remove(entry.anchor.id) else entry.view=view
                    changed=true
                }
                if (changed) publish()
            }
        }
    }
}
