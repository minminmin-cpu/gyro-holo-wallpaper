package com.example.holowallpaper.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import com.example.holowallpaper.model.CardConfig
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** EGL14 手动绑定 WallpaperService 的 SurfaceHolder，用 GLES20 画一个全屏 quad。
 *  shader 与 Web 版来自同一份源（tools/sync_shader.py 同步），GLSL ES 1.00 直接编译。 */
class EglRenderer(private val context: Context) {

    private var display = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private lateinit var eglConfig: EGLConfig
    private var program = 0
    private var quadBuffer = 0
    private val textures = HashMap<String, Int>()
    private val locations = HashMap<String, Int>()
    private var card = CardConfig()
    private var width = 1
    private var height = 1
    private val view = floatArrayOf(0f, 0f, 1f)
    private var timeSec = 0f
    private var trailX = 0f
    private var trailY = 0f
    private var trail = 0f
    private var hasFx = 1f
    private var hasLine = 1f
    private var frameLogged = false

    companion object {
        private const val TAG = "HoloGL"
        private val SAMPLERS = arrayOf("tSubject", "tBackground", "tText", "tLine", "tEffects")
        private val FILES = arrayOf("subject.png", "background.png", "text.png", "lineart.png", "effects.png")
        private val UNIFORMS = arrayOf(
            "uTime", "uFoil", "uScale", "uDepth", "uBgDepth", "uFinish",
            "uHasLine", "uHasFx", "uSafeScale", "uFxDepth",
            "uGlintGain", "uGlintSigma", "uGlintRange", "uGhost",
            "uCardMode", "uCardScale", "uTiltGain", "uRelief", "uLineDepth",
            "uTrail", "uTrailVec", "uFit", "uSafeOffset", "uAspect", "uView",
        ) + SAMPLERS
    }

    /** 必须等到拿到有效 Surface 且尺寸已知后调用（onSurfaceChanged 里）。 */
    fun initialize(surface: Surface, w: Int, h: Int) {
        initEgl(surface)
        initGl()
        resize(w, h)
        Log.d(TAG, "GL ready  viewport=${width}x${height}  program=$program  textures=${textures.size}")
    }

    private fun initEgl(surface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            // 壁纸不需要半透明；带 alpha 的 surface 在某些 ROM 上会被按预乘 alpha 处理，
            // 导致画面整体被乘暗到接近纯黑。
            EGL14.EGL_ALPHA_SIZE, 0, EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "eglChooseConfig failed"
        }
        eglConfig = configs[0]!!
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(display, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = createWindowSurface(surface)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun createWindowSurface(surface: Surface) =
        EGL14.eglCreateWindowSurface(display, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
            .also { check(it != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" } }

    /** Surface 尺寸变化时重建 window surface，GL context / program / 纹理保持复用。 */
    fun recreateSurface(surface: Surface) {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        eglSurface = createWindowSurface(surface)
        makeCurrent()
    }

    /** 多个 Engine（系统预览与桌面）共用主线程，各次 GL 操作必须重新绑定自己的上下文。 */
    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed: ${EGL14.eglGetError()}"
        }
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) {
            "shader compile failed: " + GLES20.glGetShaderInfoLog(shader)
        }
        return shader
    }

    private fun initGl() {
        val vs = compile(GLES20.GL_VERTEX_SHADER, readAsset("shader/holo.vert"))
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, readAsset("shader/holo.frag"))
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "shader link failed: " + GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        GLES20.glUseProgram(program)

        for (name in UNIFORMS) locations[name] = GLES20.glGetUniformLocation(program, name)
        SAMPLERS.forEachIndexed { index, name -> GLES20.glUniform1i(loc(name), index) }

        val coords = floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f,
            -1f, 1f, 1f, -1f, 1f, 1f,
        )
        val buffer = ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(coords)
        buffer.position(0)
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        quadBuffer = buffers[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, coords.size * 4, buffer, GLES20.GL_STATIC_DRAW)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** 首帧回读几个点，区分「管线没输出」「只有 clear」「画出来了但被遮挡」。 */
    private fun logPixels() {
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        val points = arrayOf(
            "center" to intArrayOf(width / 2, height / 2),
            "upper" to intArrayOf(width / 2, height * 3 / 4),
            "lower" to intArrayOf(width / 2, height / 4),
            "left" to intArrayOf(width / 8, height / 2),
        )
        val sb = StringBuilder("pixels:")
        for ((name, xy) in points) {
            buf.clear()
            GLES20.glReadPixels(xy[0], xy[1], 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            sb.append(' ').append(name).append("=(")
                .append(buf.get(0).toInt() and 0xFF).append(',')
                .append(buf.get(1).toInt() and 0xFF).append(',')
                .append(buf.get(2).toInt() and 0xFF).append(',')
                .append(buf.get(3).toInt() and 0xFF).append(')')
        }
        Log.d(TAG, sb.toString() + " readErr=" + GLES20.glGetError())
    }

    fun loadProfile(dir: File) {
        makeCurrent()
        Log.d(TAG, "loadProfile dir=${dir.absolutePath} exists=${dir.exists()}")
        loadTextures(dir)
        loadConfig(dir)
        Log.d(TAG, "loadProfile done textures=${textures.size} cardMode=${card.cardMode} cardScale=${card.cardScale} foil=${card.foil}")
    }

    /** 只重读参数、不重建纹理 —— 调滑块时用它，避免每次重新解码 5 张 PNG。 */
    fun loadConfig(dir: File) {
        val cfg = File(dir, "card-config.json")
        if (cfg.exists()) card = CardConfig.from(JSONObject(cfg.readText(Charsets.UTF_8)))
    }

    private fun loadTextures(dir: File) {
        // 热切换 Profile 时先释放旧纹理，避免反复切换导致 GPU 内存泄漏。
        if (textures.isNotEmpty()) {
            val old = textures.values.toIntArray()
            GLES20.glDeleteTextures(old.size, old, 0)
            textures.clear()
        }
        FILES.forEachIndexed { index, name ->
            textures[SAMPLERS[index]] = loadTexture(File(dir, name))
        }
        hasFx = if (File(dir, "effects.png").exists()) 1f else 0f
        hasLine = if (File(dir, "lineart.png").exists()) 1f else 0f
    }

    private fun loadTexture(file: File): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        if (bitmap != null) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            Log.d(TAG, "tex ${file.name} ${bitmap.width}x${bitmap.height} id=${ids[0]} err=${GLES20.glGetError()}")
            bitmap.recycle()
        } else {
            // 缺图时用品红/暗紫棋盘占位：壁纸会立刻显示明显花纹，
            // 能一眼区分「纹理没加载」和「着色器算成黑」。
            val placeholder = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    placeholder.setPixel(x, y, if ((x + y) % 2 == 0) 0xFFFF00FF.toInt() else 0xFF220044.toInt())
                }
            }
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, placeholder, 0)
            placeholder.recycle()
            Log.w(TAG, "tex ${file.name} MISSING -> placeholder  path=${file.absolutePath}")
        }
        return ids[0]
    }

    fun resize(w: Int, h: Int) {
        // 某些 ROM 的 onSurfaceChanged 会给出 0，view 就只有 1 像素 -> 整屏看起来全黑。
        width = max(w, 1)
        height = max(h, 1)
        GLES20.glViewport(0, 0, width, height)
        Log.d(TAG, "resize -> ${width}x${height}")
    }

    fun setView(v: FloatArray) {
        view[0] = v[0]; view[1] = v[1]; view[2] = v[2]
    }

    fun setTime(seconds: Float) { timeSec = seconds }

    fun setTrail(x: Float, y: Float, amount: Float) {
        trailX = x; trailY = y; trail = amount
    }

    private fun loc(name: String): Int = locations[name] ?: -1
    private fun f(name: String, v: Float) = GLES20.glUniform1f(loc(name), v)

    fun render() {
        if (program == 0 || eglSurface == EGL14.EGL_NO_SURFACE) return
        makeCurrent()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, 0)
        // 先清成深蓝而不是纯黑：万一纹理/着色器有问题，也能区分「没渲染」和「渲染成黑」。
        // 每次重建 surface 后 viewport 可能复位，显式重设避免只画到 1 像素。
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.03f, 0.04f, 0.09f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        SAMPLERS.forEachIndexed { index, name ->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[name] ?: 0)
        }
        val p = card
        f("uTime", timeSec)
        f("uFoil", p.foil)
        f("uScale", p.subjectScale)
        f("uDepth", p.subjectDepth)
        f("uBgDepth", p.backgroundDepth)
        f("uFinish", p.finish)
        f("uHasLine", hasLine)
        f("uHasFx", hasFx)
        f("uSafeScale", p.safeScale)
        f("uFxDepth", p.effectsDepth)
        f("uGlintGain", p.glintGain)
        f("uGlintSigma", p.glintSigma)
        f("uGlintRange", p.glintRange)
        f("uGhost", p.ghost)
        f("uCardMode", p.cardMode)
        // 壁纸比卡片查看器放大 12%，限制极端值避免边框在倾斜时大幅出屏。
        f("uCardScale", (p.cardScale * 1.12f).coerceIn(0.85f, 1.22f))
        f("uTiltGain", p.tiltGain)
        f("uRelief", p.relief)
        f("uLineDepth", p.lineDepth)
        f("uTrail", trail * p.trail)
        GLES20.glUniform2f(loc("uTrailVec"), trailX, trailY)
        GLES20.glUniform2f(loc("uFit"), p.fitX, p.fitY)
        GLES20.glUniform2f(loc("uSafeOffset"), p.safeOffsetX, p.safeOffsetY)
        val m = max(width, height).toFloat()
        GLES20.glUniform2f(loc("uAspect"), width / m, height / m)
        GLES20.glUniform3f(loc("uView"), view[0], view[1], view[2])
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        if (!frameLogged) {
            frameLogged = true
            Log.d(TAG, "first frame sw=${width}x$height view=${view[0]},${view[1]},${view[2]} glError=${GLES20.glGetError()}")
            logPixels()
        }
        check(EGL14.eglSwapBuffers(display, eglSurface)) {
            "eglSwapBuffers failed: ${EGL14.eglGetError()}"
        }
    }

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        val bound = EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)
        if (bound && textures.isNotEmpty()) {
            val ids = textures.values.toIntArray()
            GLES20.glDeleteTextures(ids.size, ids, 0)
            textures.clear()
        }
        if (bound && quadBuffer != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(quadBuffer), 0)
            quadBuffer = 0
        }
        if (bound && program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
        // EGL_DEFAULT_DISPLAY 为同进程多个 Engine 共享；销毁预览不能终止桌面的 display。
        // 仅释放本实例的 Surface/Context，display 随进程结束由驱动回收。
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }
}
