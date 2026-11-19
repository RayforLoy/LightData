package studio.lightdata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Location
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ReportStoreTest {
    @Test fun reportRoundTripUsesTargetSettingsAndOneGalleryImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReportStore(context)
        val photo = Bitmap.createBitmap(640,320,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val location = Location("test").apply { latitude = -22.123456; longitude = 113.654321; altitude = -16.5 }
        fun make(location:Location?) = MeterReport(1_786_000_123_456L,photo,
            Exposure(1.0 / 8000, 5.6, 321.5),127.5,-2.0 / 3,"A",
            listOf("胶片" to "PORTRA 400","测光点" to "#1  +2.0 EV"), location)
        try {
            for (gps in listOf(location,null)) {
                val item = store.save(make(gps))
                try {
                    assertTrue(store.list().any { it.uri == item.uri && it.timeMs == item.timeMs })
                    val image = context.contentResolver.openInputStream(item.uri)!!.use { BitmapFactory.decodeStream(it) }
                    assertEquals(1200,image.width)
                    assertTrue(Color.blue(image.getPixel(600,360)) > 230)
                    image.recycle()
                    context.contentResolver.openInputStream(item.uri)!!.use { input ->
                        val exif=ExifInterface(input)
                        assertEquals(1.0/8000,exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME,0.0),1e-10)
                        assertEquals(5.6,exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER,0.0),1e-8)
                        assertEquals(322,exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS,0))
                        assertEquals(321.5,exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_INDEX,0.0),1e-6)
                        assertEquals(127.5,exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH,0.0),1e-6)
                        assertEquals(-2.0/3,exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE,0.0),1e-6)
                        assertEquals(3,exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM,0))
                        val latLong=FloatArray(2)
                        assertEquals(gps!=null,exif.getLatLong(latLong))
                        if(gps!=null) {
                            assertEquals(gps.latitude,latLong[0].toDouble(),.00002)
                            assertEquals(gps.longitude,latLong[1].toDouble(),.00002)
                        }
                    }
                } finally {
                    if(gps==null) context.contentResolver.delete(item.uri,null,null)
                    else assertTrue(store.delete(item))
                }
                assertFalse(store.list().any { it.uri == item.uri })
            }
        } finally { photo.recycle() }
    }
}
