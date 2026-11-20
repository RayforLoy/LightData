package studio.lightdata

import android.opengl.GLES20.*
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class GlassFrame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** All passes run on the preview's GL thread. Both displayed regions use the same exposure and frame. */
internal class GlassCompositor {
    private val blurScales = floatArrayOf(.004f, .010f, .024f)
    private val targets = IntArray(3)
    private val images = IntArray(3)
    private var sourceW = 0
    private var sourceH = 0
    private var hasBlurTargets = false
    private val program: Int
    private val position: Int
    private val step: Int
    private val compose: Int
    private val gate: Int

    init {
        fun shader(type: Int, source: String) = glCreateShader(type).also {
            glShaderSource(it, source)
            glCompileShader(it)
            val ok = IntArray(1)
            glGetShaderiv(it, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GL_TRUE) { glGetShaderInfoLog(it) }
        }
        val vertex = shader(GL_VERTEX_SHADER, """
            attribute vec2 p;
            varying vec2 uv;
            void main() { uv = p * .5 + .5; gl_Position = vec4(p, 0., 1.); }
        """.trimIndent())
        val fragment = shader(GL_FRAGMENT_SHADER, """
            precision highp float;
            varying vec2 uv;
            uniform sampler2D scene;
            uniform sampler2D frost;
            uniform int composite;
            uniform vec2 sampleStep;
            uniform vec4 frame;
            vec2 reflectUV(vec2 p) { return 1. - abs(mod(p, 2.) - 1.); }
            void main() {
                if (composite == 2) {
                    gl_FragColor = texture2D(scene, uv);
                } else if (composite == 1) {
                    vec2 p = (uv - frame.xy) / frame.zw;
                    if (all(greaterThanEqual(p, vec2(0.))) && all(lessThanEqual(p, vec2(1.))))
                        gl_FragColor = texture2D(scene, p);
                    else
                        gl_FragColor = texture2D(frost, reflectUV(p));
                } else {
                    vec2 d = sampleStep * 1.384615;
                    vec2 e = sampleStep * 3.230769;
                    gl_FragColor = texture2D(scene, uv) * .227027
                        + (texture2D(scene, reflectUV(uv-d)) + texture2D(scene, reflectUV(uv+d))) * .316216
                        + (texture2D(scene, reflectUV(uv-e)) + texture2D(scene, reflectUV(uv+e))) * .070270;
                }
            }
        """.trimIndent())
        program = glCreateProgram()
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        val ok = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, ok, 0)
        check(ok[0] == GL_TRUE) { glGetProgramInfoLog(program) }
        position = glGetAttribLocation(program, "p")
        step = glGetUniformLocation(program, "sampleStep")
        compose = glGetUniformLocation(program, "composite")
        gate = glGetUniformLocation(program, "frame")
        glUseProgram(program)
        glUniform1i(glGetUniformLocation(program, "scene"), 0)
        glUniform1i(glGetUniformLocation(program, "frost"), 1)
        glGenFramebuffers(targets.size, targets, 0)
        glGenTextures(images.size, images, 0)
    }

    fun draw(frame: GlassFrame?, w: Int, h: Int, quad: FloatBuffer,
             sw: Int = max(1, ((frame?.width ?: 1f) * w).roundToInt()),
             sh: Int = max(1, ((frame?.height ?: 1f) * h).roundToInt()), scene: () -> Unit) {
        val bw = max(1, sw / 2)
        val bh = max(1, sh / 2)
        if (sw != sourceW || sh != sourceH || (frame != null && !hasBlurTargets)) {
            glActiveTexture(GL_TEXTURE0)
            for (i in 0 until (if (frame == null) 1 else images.size)) {
                glBindTexture(GL_TEXTURE_2D, images[i])
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, if (i == 0) sw else bw, if (i == 0) sh else bh,
                    0, GL_RGBA, GL_UNSIGNED_BYTE, null)
                glBindFramebuffer(GL_FRAMEBUFFER, targets[i])
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, images[i], 0)
                check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
            }
            hasBlurTargets = frame != null
            sourceW = sw
            sourceH = sh
        }
        glBindFramebuffer(GL_FRAMEBUFFER, targets[0])
        glViewport(0, 0, sw, sh)
        scene()

        glUseProgram(program)
        glEnableVertexAttribArray(position)
        glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, quad)
        if (frame == null) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glViewport(0, 0, w, h)
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, images[0])
            glUniform1i(compose, 2)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            glDisableVertexAttribArray(position)
            return
        }
        glUniform1i(compose, 0)
        // Clear the previous composite's texture on unit 1 to avoid rendering to a bound input.
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, 0)
        glActiveTexture(GL_TEXTURE0)
        glViewport(0, 0, bw, bh)
        // Accumulate progressively wider kernels so fine texture disappears smoothly.
        // Reuse the same two smaller targets; the sharp frame remains in images[0].
        var input = images[0]
        for (scale in blurScales) {
            val radius = max(1f, min(sw, sh) * scale)
            for (axis in 0..1) {
                val output = axis + 1
                glBindFramebuffer(GL_FRAMEBUFFER, targets[output])
                glBindTexture(GL_TEXTURE_2D, input)
                glUniform2f(step, if (axis == 0) radius/sw else 0f, if (axis == 1) radius/sh else 0f)
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
                input = images[output]
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, w, h)
        glBindTexture(GL_TEXTURE_2D, images[0])
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, images[2])
        glUniform1i(compose, 1)
        glUniform4f(gate, frame.left, 1 - frame.bottom, frame.width, frame.height)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(position)
    }
}
