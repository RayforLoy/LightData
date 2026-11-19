package studio.lightdata

import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

data class Look(
    val frame: Frame,
    val lut: DoubleArray,
    val delta: Double,
    val film: Film,
    val preview: Boolean,
    val manual: Boolean,
    val style: Int,
    val atlas: LutAtlas? = null,
    val live: LiveCapture? = null,
)

class Preview(ctx: Context) : GLSurfaceView(ctx), GLSurfaceView.Renderer {
    var onCubeUnavailable: ((String) -> Unit)? = null
    var onRendered: (() -> Unit)? = null
    @Volatile private var look: Look? = null
    @Volatile var renderedLive: LiveCapture? = null
        private set
    @Volatile private var glassFrame: GlassFrame? = null
    private var compositor: GlassCompositor? = null
    private var surfaceW = 1
    private var surfaceH = 1
    private var scale = 1.0
    private var bufferWidth = 0
    private var bufferHeight = 0
    private val textures = IntArray(6)
    private var lastLive: LiveCapture? = null
    private var reported: Any? = null
    private var lastAtlas: LutAtlas? = null
    private var rejectedAtlas: LutAtlas? = null
    private var program = 0
    private var tableLook: Look? = null
    private val locations = HashMap<String,Int>()
    private fun uniform(name: String) = locations.getOrPut(name) { glGetUniformLocation(program,name) }
    private var last: Frame? = null
    private var bytes = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
    private val table = ByteBuffer.allocateDirect(256 * 4 * 3)
    private val quad =
        ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setGlassFrame(left: Float, top: Float, right: Float, bottom: Float) {
        if (right <= left || bottom <= top) return
        val next = GlassFrame(left, top, right, bottom)
        if (glassFrame == next) return
        glassFrame = next
        resizeBuffer()
        requestRender()
    }

    fun show(next: Look, resolution: Double) {
        look = next
        scale = resolution
        resizeBuffer()
        requestRender()
    }

    fun advance(live: LiveCapture, exposureEv: Double) {
        val previous = look ?: return
        if (previous.live == null) return
        look = previous.copy(live = live, delta = live.ev - exposureEv)
        requestRender()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizeBuffer()
    }

    private fun resizeBuffer() {
        val current = look ?: return
        val fw = current.live?.width ?: current.frame.w
        val fh = current.live?.height ?: current.frame.h
        if (width == 0 || height == 0) return
        val glass = glassFrame
        val gateWidth = glass?.width?.toDouble() ?: 1.0
        val gateHeight = glass?.height?.toDouble() ?: 1.0
        val factor = minOf(1.0, width*gateWidth/fw, height*gateHeight/fh) * scale
        val w = (fw * factor / gateWidth).roundToInt().coerceIn(1, width)
        val h = (fh * factor / gateHeight).roundToInt().coerceIn(1, height)
        if (w == bufferWidth && h == bufferHeight) return
        bufferWidth = w
        bufferHeight = h
        holder.setFixedSize(w, h)
    }

    override fun onSurfaceCreated(g: GL10?, config: EGLConfig?) {
        fun compile(type: Int, src: String): Int =
            glCreateShader(type).also {
                glShaderSource(it, src)
                glCompileShader(it)
            }
        program = glCreateProgram()
        glAttachShader(
            program,
            compile(
                GL_VERTEX_SHADER,
                "attribute vec2 p; varying vec2 uv; void main(){uv=vec2((p.x+1.)*.5,(1.-p.y)*.5); gl_Position=vec4(p,0.,1.);}",
            ),
        )
        glAttachShader(
            program,
            compile(
                GL_FRAGMENT_SHADER,
                """
                    precision highp float;
                    uniform sampler2D frame; uniform sampler2D curve; uniform sampler2D cube;
                    uniform sampler2D planeY; uniform sampler2D planeU; uniform sampler2D planeV;
                    uniform int live; uniform int rotation; uniform int videoTransfer;
                    uniform vec4 gate; uniform vec2 captureSize;
                    uniform vec3 planeStride; uniform vec3 pixelStride;
                    uniform vec4 yuvA; uniform vec2 yuvB;
                    uniform int style; uniform int tone; uniform int colorFilm; uniform int useCube; uniform mat3 spectral;
                    uniform vec2 zoneBounds;
                    varying vec2 uv;
                    vec3 decodeRGB(vec3 c){
                        return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));
                    }
                    vec3 encodeRGB(vec3 c){
                        return mix(12.92*c,1.055*pow(c,vec3(1./2.4))-.055,step(vec3(.0031308),c));
                    }
                    vec4 cameraPixel(){
                        vec2 p = gate.xy + uv*gate.zw;
                        if(any(lessThan(p,vec2(0.))) || any(greaterThanEqual(p,vec2(1.)))) return vec4(0.);
                        if(rotation==1) p=vec2(p.y,1.-p.x);
                        else if(rotation==2) p=1.-p;
                        else if(rotation==3) p=vec2(1.-p.y,p.x);
                        vec2 loc=clamp(floor(p*captureSize),vec2(0.),captureSize-1.);
                        vec2 chroma=floor(loc*.5);
                        float cy=(chroma.y+.5)/ceil(captureSize.y*.5);
                        float y=floor(texture2D(planeY,vec2((loc.x*pixelStride.x+.5)/planeStride.x,(loc.y+.5)/captureSize.y)).r*255.+.5);
                        float u=floor(texture2D(planeU,vec2((chroma.x*pixelStride.y+.5)/planeStride.y,cy)).r*255.+.5)-128.;
                        float v=floor(texture2D(planeV,vec2((chroma.x*pixelStride.z+.5)/planeStride.z,cy)).r*255.+.5)-128.;
                        vec3 codes=clamp(floor(((y-yuvA.x)*yuvA.y+128.+vec3(yuvA.z*v,yuvB.x*u+yuvB.y*v,yuvA.w*u))/256.),0.,255.)/255.;
                        if(videoTransfer!=0) {
                            vec3 lin=mix(codes/4.5,pow((codes+.099)/1.099,vec3(1./.45)),step(vec3(.081),codes));
                            codes=floor(encodeRGB(lin)*255.+.5)/255.;
                        }
                        return vec4(codes,1.);
                    }
                    vec3 cubeColor(vec3 linear) {
                        vec3 grid = clamp(log2(1. + max(linear, vec3(0.))/.18)/9.,0.,1.)*32.;
                        float b = floor(grid.b);
                        vec2 a = vec2((33.*b + grid.r + .5)/1089., (grid.g + .5)/33.);
                        vec2 next = vec2((33.*min(32., b+1.) + grid.r + .5)/1089., a.y);
                        return mix(texture2D(cube, a).rgb, texture2D(cube, next).rgb, fract(grid.b));
                    }
                    vec3 applyStyle(vec3 c) {
                        if(style==2) return vec3(max(c.g,c.r*.65),c.r*.8,c.b*.72);
                        if(style==3) return vec3(max(c.r,c.g*.86),c.b*.72,max(c.b,c.g*.93));
                        return c;
                    }
                    vec3 atLuminance(vec3 color, float y) {
                        float original = dot(color,vec3(.2126,.7152,.0722));
                        if(original < .00001) return vec3(y);
                        vec3 chroma = color * (y / original) - y;
                        float high = max(chroma.r,max(chroma.g,chroma.b));
                        float low = -min(chroma.r,min(chroma.g,chroma.b));
                        float strength = min(1.,min((1.-y)/max(.000001,high), y/max(.000001,low)));
                        return clamp(vec3(y) + strength*chroma,0.,1.);
                    }
                    void main(){
                        vec4 src=live!=0 ? cameraPixel() : texture2D(frame,uv).bgra;
                        if(src.a<0.5){gl_FragColor=vec4(0.,0.,0.,1.);return;}
                        vec3 rgb=src.rgb;
                        const vec3 weights=vec3(.2126,.7152,.0722);
                        float y=dot(decodeRGB(rgb),weights);
                        float gray=encodeRGB(vec3(y)).r;
                        float ix=(floor(gray*255.+.5)+.5)/256.;
                        vec3 map=texture2D(curve,vec2(ix,1./6.)).rgb;
                        if(style==1)rgb=vec3(gray);
                        rgb=applyStyle(rgb);
                        if(tone!=0) {
                            vec3 linear=decodeRGB(rgb);
                            float targetY=decodeRGB(map.rrr).r;
                            if(useCube!=0) {
                                vec2 packedEV=texture2D(curve,vec2(ix,5./6.)).rg;
                                float ev=(dot(packedEV,vec2(65280.,255.))/65535.)*160.-80.;
                                float scene=.18*exp2(ev);
                                vec3 inputRGB=y>0.000001 ? decodeRGB(src.rgb)*(scene/y) : vec3(scene);
                                vec3 lutColor=cubeColor(inputRGB);
                                if(max(src.r,max(src.g,src.b)) > min(src.r,min(src.g,src.b))) {
                                    float neutralY=dot(decodeRGB(cubeColor(vec3(scene))),weights);
                                    float colorY=dot(decodeRGB(lutColor),weights);
                                    vec2 bounds=map.g>.5 ? vec2(zoneBounds.y,1.) :
                                        (map.b>.5 ? vec2(0.,zoneBounds.x) : zoneBounds);
                                    targetY=clamp(targetY*(colorY+.0001)/(neutralY+.0001),bounds.x,bounds.y);
                                }
                                linear=decodeRGB(applyStyle(lutColor));
                                if(style==1) linear=vec3(dot(linear,weights));
                                rgb=encodeRGB(atLuminance(linear,targetY));
                            } else if(colorFilm!=0 && style==0) {
                                vec3 codes=encodeRGB(clamp(spectral*linear,0.,1.));
                                rgb=vec3(texture2D(curve,vec2(codes.r,.5)).r,texture2D(curve,vec2(codes.g,.5)).g,texture2D(curve,vec2(codes.b,.5)).b);
                            } else {
                                float sourceY=dot(linear,weights);
                                rgb=encodeRGB(clamp(sourceY>0.000001 ? linear*(targetY/sourceY) : vec3(targetY),0.,1.));
                            }
                        }
                        if(map.g>.5 && mod(gl_FragCoord.x+gl_FragCoord.y,12.)<3.)rgb=mix(rgb,vec3(1.,.22,.2),.82);
                        if(map.b>.5 && mod(gl_FragCoord.x-gl_FragCoord.y,12.)<3.)rgb=mix(rgb,vec3(.12,.86,.52),.82);
                        gl_FragColor=vec4(rgb,1.);
                    }
                """
                    .trimIndent(),
            ),
        )
        glLinkProgram(program)
        compositor = null
        locations.clear()
        tableLook = null
        glGenTextures(textures.size, textures, 0)
        lastAtlas = null
        rejectedAtlas = null
        last = null
        lastLive = null
        textures.forEachIndexed { i, t ->
            glActiveTexture(GL_TEXTURE0 + i)
            glBindTexture(GL_TEXTURE_2D, t)
            for (p in listOf(GL_TEXTURE_MIN_FILTER, GL_TEXTURE_MAG_FILTER)) glTexParameteri(
                GL_TEXTURE_2D,
                p,
                if (i == 0 || i == 2) GL_LINEAR else GL_NEAREST,
            )
            for (p in listOf(GL_TEXTURE_WRAP_S, GL_TEXTURE_WRAP_T)) glTexParameteri(
                GL_TEXTURE_2D,
                p,
                GL_CLAMP_TO_EDGE,
            )
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(4))
        }
    }

    override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) {
        surfaceW = w
        surfaceH = h
        glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(g: GL10?) {
        val l = look ?: return
        val frame = glassFrame
        if (frame == null) drawLook(l)
        else {
            val glass = compositor ?: GlassCompositor().also { compositor = it }
            glass.draw(frame, surfaceW, surfaceH, quad) { drawLook(l) }
        }
        renderedLive = l.live
        val source = l.live ?: l.frame
        if (source !== reported) {
            reported = source
            onRendered?.invoke()
        }
    }

    internal fun renderReportFrame(l: Look, complete: (Result<android.graphics.Bitmap>) -> Unit) =
        readReportFrame(l.frame, { drawLook(l) }, complete)

    private fun drawLook(l: Look) {
        val f = l.frame
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, textures[0])
        val live = l.live
        if (live == null && last !== f) {
            val size = f.w * f.h * 4
            if (bytes.capacity() < size)
                bytes = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
            bytes.clear()
            // Bulk upload ARGB ints. The shader swaps the little-endian B/R bytes.
            bytes.asIntBuffer().put(f.pixels)
            bytes.limit(size)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, f.w, f.h, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
            last = f
        }
        glUniform1i(uniform("live"),if (live == null) 0 else 1)
        if (live != null) {
            val image = live.image
            glUniform1i(uniform("rotation"),live.turn/90)
            glUniform4fv(uniform("gate"),1,live.gate,0)
            glUniform2f(uniform("captureSize"),image.w.toFloat(),image.h.toFloat())
            val (y,u,v) = image.planes
            glUniform3f(uniform("planeStride"),y.rowStride.toFloat(),u.rowStride.toFloat(),v.rowStride.toFloat())
            glUniform3f(uniform("pixelStride"),y.pixelStride.toFloat(),u.pixelStride.toFloat(),v.pixelStride.toFloat())
            val coeff = image.encoding.gpuYuv
            glUniform4fv(uniform("yuvA"),1,coeff,0)
            glUniform2fv(uniform("yuvB"),1,coeff,4)
            glUniform1i(uniform("videoTransfer"),if (image.encoding.videoTransfer) 1 else 0)
            if (live !== lastLive) {
                glPixelStorei(GL_UNPACK_ALIGNMENT,1)
                for ((i,p) in image.planes.withIndex()) {
                    glActiveTexture(GL_TEXTURE3+i)
                    glBindTexture(GL_TEXTURE_2D,textures[3+i])
                    val h = if (i==0) image.h else (image.h+1)/2
                    val storage = lastLive?.image
                    val data=ByteBuffer.wrap(p.data)
                    if (storage?.h == image.h && storage.planes[i].rowStride == p.rowStride)
                        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,p.rowStride,h,GL_LUMINANCE,GL_UNSIGNED_BYTE,data)
                    else glTexImage2D(GL_TEXTURE_2D,0,GL_LUMINANCE,p.rowStride,h,0,GL_LUMINANCE,GL_UNSIGNED_BYTE,data)
                }
                lastLive=live
            }
        }
        val color = if (l.preview && l.style == 0 && l.atlas == null) colorCurves[l.film.name] else null
        val prev = tableLook
        if (prev == null || prev.lut !== l.lut || prev.film !== l.film || prev.delta != l.delta ||
            prev.manual != l.manual || prev.preview != l.preview || prev.style != l.style || prev.atlas !== l.atlas) {
            table.clear()
            for (i in 0..255) {
                val ev = l.lut[i] + l.delta
                val y =
                    when {
                        l.preview -> linearToSrgb(l.film.positive(ev))
                        l.manual -> linearToSrgb(.18 * 2.0.pow(ev))
                        else -> i / 255.0
                    }
                table.put((y.coerceIn(0.0, 1.0) * 255).roundToInt().toByte())
                val limit = l.film.limit(ev)
                table.put(if (limit == FilmLimit.HIGHLIGHT) (-1).toByte() else 0)
                table.put(if (limit == FilmLimit.SHADOW) (-1).toByte() else 0)
                table.put((-1).toByte())
            }
            if (color != null) {
                for (i in 0..255) {
                    val ev = l.lut[i] + l.delta
                    for (channel in listOf("red", "green", "blue")) {
                        val value = color.positive(channel, ev)
                        table.put((linearToSrgb(value).coerceIn(0.0, 1.0)*255).roundToInt().toByte())
                    }
                    table.put((-1).toByte())
                }
            } else table.position(256 * 4 * 2)
            // A calibrated relative exposure travels in two bytes, independently of the
            // display curve. LUT input therefore receives the target exposure exactly once.
            for (i in 0..255) {
                val code = (((l.lut[i] + l.delta).coerceIn(-80.0, 80.0) + 80) * 65535 / 160).roundToInt()
                table.put((code ushr 8).toByte()).put(code.toByte()).put(0).put(0)
            }
            table.flip()
            glActiveTexture(GL_TEXTURE1)
            glBindTexture(GL_TEXTURE_2D, textures[1])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 256, 3, 0, GL_RGBA, GL_UNSIGNED_BYTE, table)
            tableLook = l
        }
        glActiveTexture(GL_TEXTURE2)
        glBindTexture(GL_TEXTURE_2D, textures[2])
        val atlas = l.atlas
        if (atlas == null) rejectedAtlas = null
        if (atlas != null && atlas !== lastAtlas && atlas !== rejectedAtlas) {
            glGetError()
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, CUBE_SIZE*CUBE_SIZE, CUBE_SIZE, 0,
                GL_RGB, GL_UNSIGNED_BYTE, atlas.pixels.duplicate())
            if (glGetError() == GL_NO_ERROR) lastAtlas = atlas else {
                rejectedAtlas = atlas
                lastAtlas = null
                post { onCubeUnavailable?.invoke(atlas.entry.file) }
            }
        }
        // The full-screen compositor also uses units 0 and 1. Restore the curve
        // binding even on frames whose exposure did not require a table upload.
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, textures[1])
        glUniform1i(uniform("cube"), 2)
        glUniform2f(uniform("zoneBounds"),
            l.film.positive(l.film.low).toFloat(), l.film.positive(l.film.high).toFloat())
        glUniform1i(uniform("useCube"), if (atlas != null && atlas === lastAtlas) 1 else 0)
        glUniform1i(uniform("frame"), 0)
        glUniform1i(uniform("planeY"), 3)
        glUniform1i(uniform("planeU"), 4)
        glUniform1i(uniform("planeV"), 5)
        glUniform1i(uniform("curve"), 1)
        glUniform1i(uniform("style"), l.style)
        glUniform1i(uniform("tone"), if (l.manual || l.preview) 1 else 0)
        glUniform1i(uniform("colorFilm"), if (l.preview && color != null) 1 else 0)
        glUniformMatrix3fv(uniform("spectral"),1,false,color?.matrix ?: floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f),0)
        val a = glGetAttribLocation(program, "p")
        glEnableVertexAttribArray(a)
        glVertexAttribPointer(a, 2, GL_FLOAT, false, 0, quad)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }
}
