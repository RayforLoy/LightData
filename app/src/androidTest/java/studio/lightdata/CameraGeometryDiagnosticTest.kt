package studio.lightdata

import android.hardware.camera2.CameraCharacteristics
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in real-device diagnostic; records metadata only, no camera images. */
class CameraGeometryDiagnosticTest {
    @Test fun inspectAvailableRearStreams() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val requested=InstrumentationRegistry.getArguments().getString("lensId")
        val lenses = cameras(ctx).filter { requested==null || it.id==requested }
        val report = JSONArray()
        for (lens in lenses) {
            val c = lens.c
            val entry = JSONObject().put("owner",lens.owner).put("id",lens.id)
                .put("size",lens.size.toString()).put("angle",lens.angle)
                .put("computedField",JSONArray(listOf(lens.view.x,lens.view.y)))
            val keys = listOf(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
                CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY,
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE,
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
                CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                CameraCharacteristics.LENS_INTRINSIC_CALIBRATION,
                CameraCharacteristics.LENS_DISTORTION,
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            for (key in keys) {
                val value=c.get(key)
                entry.put(key.name, when(value) {
                    is FloatArray -> JSONArray(value.toList())
                    is IntArray -> JSONArray(value.toList())
                    else -> value?.toString() ?: JSONObject.NULL
                })
            }
            val latch=CountDownLatch(1)
            val statuses=java.util.concurrent.CopyOnWriteArrayList<String>()
            val samples=java.util.concurrent.CopyOnWriteArrayList<String>()
            val rig=Rig(ctx,{ packet,_,_,active ->
                if (samples.size<10) packet.scanMetadata?.let {
                    samples.add(JSONObject(it).put("actualField",JSONArray(listOf(active.view.x,active.view.y))).toString())
                }
                if(samples.size>=10) latch.countDown()
            },{statuses.add(it)},{_,_->},{true})
            try {
                rig.setPanoramaLock(true)
                rig.open(lens)
                entry.put("received",latch.await(8,TimeUnit.SECONDS))
            } finally { rig.destroy() }
            entry.put("samples",JSONArray(samples.map { JSONObject(it) }))
                .put("status",JSONArray(statuses))
            report.put(entry)
            File(ctx.filesDir,"camera-geometry.json").writeText(report.toString(2))
        }
    }
}
