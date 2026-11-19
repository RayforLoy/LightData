package studio.lightdata

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class FullFinderPresetTest {
    @Test fun namesAndThreePositionsPersistPerOrientationAfterReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "fullFinderTest-" + System.nanoTime()
        val prefs = context.getSharedPreferences(key, Context.MODE_PRIVATE)
        try {
            val settings = FullFinderSettings(prefs)
            val portrait = GlassLayout(listOf(GlassAnchor(.1f,.9f),GlassAnchor(.2f,.8f),GlassAnchor(.3f,.7f)), .7f)
            val landscape = portrait.move(GlassPart.METER,1f,0f)
            settings.toggle()
            settings.save(false,"近摄",portrait)
            settings.save(true,"近摄",landscape)
            settings.use(false,portrait)
            val reopened = FullFinderSettings(prefs)
            assertTrue(reopened.enabled)
            assertEquals(portrait,reopened.current(false))
            assertNull(reopened.current(true))
            assertEquals(listOf("近摄"),reopened.names(false))
            assertEquals(portrait,reopened.load(false,"近摄"))
            assertEquals(landscape,reopened.load(true,"近摄"))
            reopened.use(false,null)
            assertNull(reopened.current(false))
            assertEquals(portrait,reopened.load(false,"近摄"))
        } finally { context.deleteSharedPreferences(key) }
    }
}
