package studio.lightdata

import android.opengl.EGL14.*
import android.opengl.EGLConfig
import android.opengl.GLES20.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class GlassCompositorTest {
    @Test fun centerAndReflectionChangeOnSameDraw() {
        val display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val configAttrs = intArrayOf(EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE)
        check(eglChooseConfig(display,configAttrs,0,configs,0,1,count,0) && count[0] == 1)
        val context = eglCreateContext(display,configs[0],EGL_NO_CONTEXT,intArrayOf(EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE),0)
        val surface = eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL_WIDTH,256,EGL_HEIGHT,128,EGL_NONE),0)
        try {
            check(eglMakeCurrent(display,surface,surface,context))
            val glass = GlassCompositor()
            val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)); position(0)
            }
            val pixel = ByteBuffer.allocateDirect(4)
            fun red(x: Int, y: Int): Int {
                glReadPixels(x,y,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel)
                return pixel.get(0).toInt() and 255
            }
            for (bright in listOf(true,false)) {
                val base = if (bright) 0f else 1f
                val stripe = 1f - base
                glass.draw(GlassFrame(.5f,0f,1f,1f),256,128,quad) {
                    glClearColor(base,0f,0f,1f)
                    glClear(GL_COLOR_BUFFER_BIT)
                    glEnable(GL_SCISSOR_TEST)
                    glScissor(32,64,64,64)
                    glClearColor(stripe,0f,0f,1f)
                    glClear(GL_COLOR_BUFFER_BIT)
                    glDisable(GL_SCISSOR_TEST)
                }
                val expected = if (bright) 255 else 0
                assertEquals(expected, red(176,96))  // Center of a sharp, bright stripe
                assertEquals(expected, red(79,96))   // The same stripe beyond the left edge
                assertEquals(255-expected, red(79,32))
                assertEquals(255-expected, red(159,96))
                assertEquals(expected, red(160,96))  // Sharp one-pixel transition
                assertTrue("Expected filtered transition beyond frame", red(95,96) in 1..254)
                assertEquals(GL_NO_ERROR,glGetError())
            }
        } finally {
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT)
            eglDestroySurface(display,surface)
            eglDestroyContext(display,context)
            eglTerminate(display)
        }
    }
}
