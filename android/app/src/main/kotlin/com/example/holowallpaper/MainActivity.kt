package com.example.holowallpaper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.example.holowallpaper.bridge.NativeBridge
import com.example.holowallpaper.bridge.PhotoHandoff
import com.example.holowallpaper.wallpaper.HoloWallpaperService
import com.example.holowallpaper.wallpaper.ProfileStore
import org.json.JSONObject
import java.io.File

/** M2：WebView 承载 app-ui/ 的完整界面，通过 HoloAndroid 桥调用原生能力。
 *  照片选择走 WebView 标准的 onShowFileChooser，不依赖 JS 桥。 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val TAG = "HoloWallpaper"
        private const val REQ_PHOTO = 1001
        private const val REQ_FILE = 1002
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 仅 debug 包开启 WebView 远程调试：可通过 adb forward + CDP 注入脚本，
        // 在没有触摸注入权限时也能验证滑块、导出等交互。
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        ProfileStore.installDemoIfNeeded(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            setBackgroundColor(Color.parseColor("#070A18"))
            addJavascriptInterface(NativeBridge(this@MainActivity), "HoloAndroid")
            webViewClient = WebViewClient()

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?,
                ): Boolean {
                    Log.d(TAG, "onShowFileChooser called")
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        startActivityForResult(pickImageIntent(), REQ_FILE)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "startActivityForResult failed", e)
                        fileCallback = null
                        false
                    }
                }
            }
            // 支持 `am start -e page preview` 直达某页，便于无点击能力时验证。
            val page = intent?.getStringExtra("page")
            val url = if (page.isNullOrBlank()) {
                "file:///android_asset/app-ui/index.html"
            } else {
                "file:///android_asset/app-ui/index.html?page=" + page
            }
            loadUrl(url)
        }
        setContentView(webView)
    }

    override fun onResume() {
        super.onResume()
        webView.resumeTimers()
    }

    private fun pickImageIntent(): Intent {
        val inner = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        return Intent.createChooser(inner, "选择照片")
    }

    fun runJs(script: String) {
        runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    /** 备用通道：JS 桥触发（个别 WebView 不回调 onShowFileChooser 时可用）。 */
    fun launchPhotoPicker() {
        try {
            startActivityForResult(pickImageIntent(), REQ_PHOTO)
        } catch (e: Exception) {
            Log.e(TAG, "launchPhotoPicker failed", e)
            val payload = JSONObject().put("ok", false)
                .put("reason", "无法打开相册：" + (e.message ?: "")).toString()
            runJs("window.__onNative.onPhotoPicked(" + JSONObject.quote(payload) + ")")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        Log.d(TAG, "onActivityResult req=$requestCode result=$resultCode uri=$uri")

        if (requestCode == REQ_FILE) {
            val callback = fileCallback
            fileCallback = null
            callback?.onReceiveValue(if (resultCode == RESULT_OK && uri != null) arrayOf(uri) else null)
            if (resultCode == RESULT_OK && uri != null) handOffPhoto(uri)
            return
        }
        if (requestCode == REQ_PHOTO && resultCode == RESULT_OK && uri != null) handOffPhoto(uri)
    }

    /** 复制到 cache，把绝对路径同时写进 PhotoHandoff（兜底）并回调前端。 */
    private fun handOffPhoto(uri: Uri) {
        val payload = try {
            val file = File(cacheDir, "pick-" + System.currentTimeMillis() + ".jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            if (!file.exists() || file.length() == 0L) {
                PhotoHandoff.lastError = "读取照片内容为空"
                Log.w(TAG, "handOffPhoto empty: $uri")
                JSONObject().put("ok", false).put("reason", "读取照片内容为空").toString()
            } else {
                PhotoHandoff.path = file.absolutePath
                PhotoHandoff.lastError = null
                PhotoHandoff.updatedAt = System.currentTimeMillis()
                Log.d(TAG, "handOffPhoto ok: ${file.absolutePath} (${file.length()} bytes)")
                JSONObject().put("ok", true)
                    .put("path", file.absolutePath)
                    .put("name", file.name)
                    .put("size", file.length())
                    .toString()
            }
        } catch (e: Exception) {
            PhotoHandoff.lastError = e.message
            Log.e(TAG, "handOffPhoto failed", e)
            JSONObject().put("ok", false).put("reason", e.message ?: "读取照片失败").toString()
        }

        // onActivityResult 阶段 Activity 还没 resume，直接 evaluateJavascript 可能被 WebView 丢弃，
        // 延后到窗口恢复后再执行；即使这次仍失败，PhotoHandoff 也能让生成流程继续。
        webView.postDelayed({
            webView.evaluateJavascript(
                "window.__onNative && window.__onNative.onPhotoPicked(" + JSONObject.quote(payload) + ")",
                null,
            )
        }, 200)
    }

    /** 部分 ROM（如这台 MIUI）没有为 ACTION_CHANGE_LIVE_WALLPAPER 注册组件：
     *  直达预览会拉起一个空壳 Activity 并立即 finish，用户看到的就是「点了没反应」。
     *  因此优先用通用的动态壁纸选择器（实测可正常打开 LiveWallpaperActivity 列表），
     *  只有它不可用时才回退到直达，最后给出手动路径提示。 */
    fun openWallpaperChooser() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            Log.d(TAG, "opened LIVE_WALLPAPER_CHOOSER")
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "LIVE_WALLPAPER_CHOOSER unavailable", e)
        }

        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, HoloWallpaperService::class.java),
                ),
            )
            Log.d(TAG, "opened CHANGE_LIVE_WALLPAPER")
        } catch (e: Exception) {
            Log.e(TAG, "no wallpaper activity", e)
            Toast.makeText(
                this,
                "未找到系统壁纸设置，请手动：设置 → 壁纸 → 动态壁纸",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
