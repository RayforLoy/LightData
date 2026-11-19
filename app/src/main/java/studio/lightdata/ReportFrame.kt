package studio.lightdata

import android.graphics.Bitmap
import android.opengl.GLES20.*
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Render the metered sample offscreen with the same shader, independently of arriving live frames. */
internal fun Preview.readReportFrame(frame: Frame, draw: () -> Unit, complete: (Result<Bitmap>) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    val finished = AtomicBoolean()
    val timeout = Runnable {
        if (finished.compareAndSet(false,true)) complete(Result.failure(IllegalStateException("取景器暂不可用")))
    }
    main.postDelayed(timeout, 8000)
    queueEvent {
        if (finished.get()) return@queueEvent
        val result = runCatching { reportBitmap(frame,draw) }
        main.post {
            main.removeCallbacks(timeout)
            if (finished.compareAndSet(false,true)) complete(result)
            else result.getOrNull()?.recycle()
        }
    }
    requestRender()
}

private fun reportBitmap(frame: Frame, draw: () -> Unit): Bitmap {
    val ratio = minOf(1.0,1600.0 / max(frame.w,frame.h))
    val w = max(1,(frame.w*ratio).roundToInt())
    val h = max(1,(frame.h*ratio).roundToInt())
    val viewport = IntArray(4)
    glGetIntegerv(GL_VIEWPORT,viewport,0)
    val previous = IntArray(1)
    glGetIntegerv(GL_FRAMEBUFFER_BINDING,previous,0)
    val fbo = IntArray(1)
    val texture = IntArray(1)
    glGenTextures(1,texture,0)
    glGenFramebuffers(1,fbo,0)
    try {
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D,texture[0])
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,null)
        glBindFramebuffer(GL_FRAMEBUFFER,fbo[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture[0],0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "无法建立取景图像缓冲区" }
        glViewport(0,0,w,h)
        draw()
        val pixels = ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.LITTLE_ENDIAN)
        glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,pixels)
        check(glGetError()==GL_NO_ERROR) { "取景图像读取失败" }
        val buffer = pixels.asIntBuffer()
        val argb = IntArray(w*h)
        for (y in 0 until h) {
            buffer.position((h-1-y)*w)
            buffer.get(argb,y*w,w)
            for (i in y*w until (y+1)*w) {
                val abgr = argb[i]
                argb[i] = ((abgr and 255) shl 16) or ((abgr ushr 16) and 255) or (abgr and 0xff00ff00.toInt())
            }
        }
        return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply { setPixels(argb,0,w,0,0,w,h) }
    } finally {
        glBindFramebuffer(GL_FRAMEBUFFER,previous[0])
        glViewport(viewport[0],viewport[1],viewport[2],viewport[3])
        glDeleteFramebuffers(1,fbo,0)
        glDeleteTextures(1,texture,0)
    }
}
