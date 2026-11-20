package studio.lightdata

import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform

private fun ColorSpaceTransform.rows() = DoubleArray(9) { getElement(it%3,it/3).toDouble() }

private fun referenceTemperature(code: Int?) = when (code) {
    17, 3 -> 2856.0 // Standard A or tungsten
    18 -> 4874.0
    19 -> 6774.0
    20 -> 5503.0
    21 -> 6504.0
    22 -> 7504.0
    23 -> 5003.0
    24 -> 3200.0
    else -> null
}

internal class CameraWhiteEstimator(c: C) {
    private fun profile(color: ColorSpaceTransform?, calibration: ColorSpaceTransform?, illuminant: Int?): SensorMatrix? {
        val base = color?.rows() ?: return null
        val correction = calibration?.rows()
        val actual = if (correction == null) base else DoubleArray(9) { index ->
            (0..2).sumOf { k -> correction[(index/3)*3+k]*base[k*3+index%3] }
        }
        return SensorMatrix(actual,referenceTemperature(illuminant))
    }
    private val first = profile(c[C.SENSOR_COLOR_TRANSFORM1],c[C.SENSOR_CALIBRATION_TRANSFORM1],c[C.SENSOR_REFERENCE_ILLUMINANT1])
    private val second = profile(c[C.SENSOR_COLOR_TRANSFORM2],c[C.SENSOR_CALIBRATION_TRANSFORM2],c[C.SENSOR_REFERENCE_ILLUMINANT2]?.toInt())

    fun estimate(result: CaptureResult): SceneWhite? {
        val matrix = first ?: return null
        val neutral = result[CaptureResult.SENSOR_NEUTRAL_COLOR_POINT]?.let { values ->
            DoubleArray(values.size) { values[it].toDouble() }
        } ?: result[CaptureResult.COLOR_CORRECTION_GAINS]?.let {
            doubleArrayOf(1.0/it.red,2.0/(it.greenEven+it.greenOdd),1.0/it.blue)
        } ?: return null
        return matrix.estimate(neutral,second)
    }
}
