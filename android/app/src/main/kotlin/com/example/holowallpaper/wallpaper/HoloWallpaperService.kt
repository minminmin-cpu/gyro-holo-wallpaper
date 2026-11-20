package com.example.holowallpaper.wallpaper

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder

/** 真·系统动态壁纸：EGL14 + GLES20 实时渲染，TYPE_GAME_ROTATION_VECTOR 驱动 uView。
 *  App 与 Service 同进程，Profile 由 ProfileStore 共享；Service 全程不联网。 */
class HoloWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "HoloWallpaper"
    }

    override fun onCreateEngine(): Engine = HoloEngine()

    inner class HoloEngine : Engine(), Choreographer.FrameCallback {

        private val sensorManager by lazy {
            getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }
        private val rotationSensor by lazy {
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        }
        private val attitude = AttitudeSource()
        private var renderer: EglRenderer? = null
        private var visible = false
        private var lastFrameNanos = 0L
        private var lastRenderNanos = 0L
        private var startNanos = 0L
        private var elapsed = 0f
        private var loadedProfileId: String? = null
        private var loadedConfigAt = 0L
        private var profileCheckCounter = 0
        private var frameCount = 0
        private val handler = Handler(Looper.getMainLooper())
        private var lastPresentedAt = 0L
        private val watchdog = object : Runnable {
            override fun run() {
                if (!visible) return
                if (surfaceHolder.surface.isValid && SystemClock.uptimeMillis() - lastPresentedAt > 2500L) {
                    Log.w(TAG, "visible frame stall: requeue engine=${this@HoloEngine.hashCode()} preview=$isPreview")
                    if (renderer == null) {
                        onSurfaceChanged(surfaceHolder, 0, surfaceHolder.surfaceFrame.width(), surfaceHolder.surfaceFrame.height())
                    }
                    Choreographer.getInstance().removeFrameCallback(this@HoloEngine)
                    Choreographer.getInstance().postFrameCallback(this@HoloEngine)
                }
                handler.postDelayed(this, 1000L)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            ProfileStore.installDemoIfNeeded(this@HoloWallpaperService)
            // 不在此时建 EGL：部分 ROM 在 onSurfaceCreated 阶段 surface 尚未就绪、尺寸也为 0。
            Log.d(TAG, "onSurfaceCreated")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val frame = holder.surfaceFrame
            val w = if (width > 0) width else frame.width()
            val h = if (height > 0) height else frame.height()
            Log.d(TAG, "onSurfaceChanged ${width}x${height} frame=${frame.width()}x${frame.height()}")
            try {
                val current = renderer
                if (current == null) {
                    val created = EglRenderer(this@HoloWallpaperService)
                    created.initialize(holder.surface, w, h)
                    created.loadProfile(ProfileStore.activeDir(this@HoloWallpaperService))
                    renderer = created
                    loadedProfileId = ProfileStore.activeId(this@HoloWallpaperService)
                } else {
                    current.recreateSurface(holder.surface)
                    current.resize(w, h)
                }
                if (visible) {
                    startSensor()
                    if (startNanos == 0L) startNanos = System.nanoTime()
                    lastFrameNanos = 0L
                    lastRenderNanos = 0L
                    Choreographer.getInstance().removeFrameCallback(this)
                    Choreographer.getInstance().postFrameCallback(this)
                }
            } catch (e: Exception) {
                Log.e(TAG, "renderer init failed", e)
                // 兜底：初始化失败时在壁纸上画一条可见提示，避免用户只看到纯黑无法判断。
                try {
                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        canvas.drawColor(Color.parseColor("#0A0E22"))
                        val paint = Paint().apply {
                            color = Color.WHITE
                            textSize = 40f
                            isAntiAlias = true
                        }
                        canvas.drawText("渲染初始化失败", 60f, 180f, paint)
                        canvas.drawText(e.message ?: e.javaClass.simpleName, 60f, 240f, paint)
                        holder.unlockCanvasAndPost(canvas)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "fallback draw failed", t)
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopSensor()
            Choreographer.getInstance().removeFrameCallback(this)
            renderer?.release()
            renderer = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            Log.d(TAG, "onVisibilityChanged visible=$isVisible renderer=${renderer != null}")
            visible = isVisible
            handler.removeCallbacks(watchdog)
            if (isVisible) {
                lastPresentedAt = SystemClock.uptimeMillis()
                handler.postDelayed(watchdog, 1000L)
                startSensor()
                lastFrameNanos = 0L
                lastRenderNanos = 0L
                Choreographer.getInstance().removeFrameCallback(this)
                // 只在首次可见时设定时间基准。MIUI 桌面会反复回调 visible=true，
                // 每次都重置 startNanos 会让 elapsed 反复归零，自动巡游的 sin(elapsed)
                // 永远停在起点 —— 表现就是「壁纸是静态的」。
                if (startNanos == 0L) startNanos = System.nanoTime()
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                stopSensor()
                Choreographer.getInstance().removeFrameCallback(this)
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!visible) return
            try {
                renderFrame(frameTimeNanos)
            } catch (e: Exception) {
                Log.e(TAG, "frame failed; keep scheduler alive engine=${hashCode()}", e)
            } finally {
                if (visible) {
                    Choreographer.getInstance().removeFrameCallback(this)
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        private fun renderFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.06f)
            }
            lastFrameNanos = frameTimeNanos
            elapsed = (frameTimeNanos - startNanos) / 1_000_000_000f
            // App 里切换 Profile 后，壁纸服务无需重启：每秒检查一次 active id 并热加载。
            if (++profileCheckCounter % 60 == 0) {
                val dir = ProfileStore.activeDir(this@HoloWallpaperService)
                val currentId = ProfileStore.activeId(this@HoloWallpaperService)
                val cfgFile = java.io.File(dir, "card-config.json")
                val cfgAt = if (cfgFile.exists()) cfgFile.lastModified() else 0L
                if (currentId != loadedProfileId) {
                    renderer?.loadProfile(dir)
                    loadedProfileId = currentId
                    loadedConfigAt = cfgAt
                } else if (cfgAt != loadedConfigAt) {
                    // P1：只重读参数，不重建纹理，滑块调完壁纸立即跟随。
                    renderer?.loadConfig(dir)
                    loadedConfigAt = cfgAt
                }
            }
            val moving = attitude.step(dt, elapsed)

            // 静止 ~12fps、预览态 ~30fps、跟随时跟 vsync；壁纸不可见时整个循环已停止。
            val minInterval = when {
                isPreview() -> 33_000_000L
                !moving -> 50_000_000L   // 静止时 20fps，保证缓慢巡游看着连续
                else -> 0L
            }
            if (frameTimeNanos - lastRenderNanos < minInterval) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            lastRenderNanos = frameTimeNanos

            try {
                renderer?.let {
                    it.setView(attitude.view)
                    it.setTime(elapsed)
                    it.setTrail(attitude.trailX, attitude.trailY, attitude.trailAmount)
                    it.render()
                    lastPresentedAt = SystemClock.uptimeMillis()
                }
            } catch (e: Exception) {
                Log.e(TAG, "frame submission failed; rebuild EGL engine=${hashCode()}", e)
                try { renderer?.release() } catch (_: Exception) { }
                renderer = null
                if (surfaceHolder.surface.isValid) {
                    onSurfaceChanged(surfaceHolder, 0, surfaceHolder.surfaceFrame.width(), surfaceHolder.surfaceFrame.height())
                }
            }
            frameCount++
            // 心跳日志：约每秒一条，用来确认帧循环还在跑、elapsed 在增长、view 在变。
            if (frameCount % 60 == 1) {
                Log.d(TAG, "frame=$frameCount elapsed=${"%.1f".format(elapsed)}s moving=$moving " +
                    "view=(${"%.2f".format(attitude.view[0])},${"%.2f".format(attitude.view[1])}) " +
                    "renderer=${renderer != null}")
            }
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun startSensor() {
            rotationSensor?.let {
                sensorManager.registerListener(attitude, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        private fun stopSensor() {
            sensorManager.unregisterListener(attitude)
        }

        override fun onDestroy() {
            visible = false
            handler.removeCallbacks(watchdog)
            Choreographer.getInstance().removeFrameCallback(this)
            stopSensor()
            super.onDestroy()
        }
    }
}
