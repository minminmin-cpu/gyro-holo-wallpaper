package com.example.holowallpaper.wallpaper

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** App 与 WallpaperService 同进程，直接共享 files/profiles/ 与 SharedPreferences。
 *  Profile 一旦落盘，壁纸服务完全离线可用。 */
object ProfileStore {
    private const val PREF = "holo_prefs"
    private const val KEY_ACTIVE = "active_profile_id"
    const val DEMO_ID = "demo"

    private val DEMO_ASSETS = arrayOf(
        "subject.png", "background.png", "text.png",
        "lineart.png", "effects.png", "card-config.json",
    )

    fun dir(context: Context): File = File(context.filesDir, "profiles")
    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 覆盖安装 / 升级后 SharedPreferences 可能被清空。
     *  此时不能直接回退到 demo，否则用户的作品会被「遗忘」——优先选最新的非 demo Profile。 */
    fun activeId(context: Context): String {
        val saved = prefs(context).getString(KEY_ACTIVE, null)
        if (!saved.isNullOrBlank() && File(dir(context), saved).exists()) return saved
        val latest = dir(context).listFiles()
            ?.filter { it.isDirectory && it.name != DEMO_ID && File(it, "card-config.json").exists() }
            ?.maxByOrNull { it.lastModified() }
        return latest?.name ?: DEMO_ID
    }
    fun activeDir(context: Context): File = File(dir(context), activeId(context))

    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    /** 首次启动把 assets/demo/ 下的 5 层素材与配置原子地装到私有目录。 */
    fun installDemoIfNeeded(context: Context) {
        val out = File(dir(context), DEMO_ID)
        if (File(out, "card-config.json").exists()) return
        val staging = File(dir(context), DEMO_ID + ".tmp")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        for (name in DEMO_ASSETS) {
            context.assets.open("demo/$name").use { input ->
                FileOutputStream(File(staging, name)).use { output -> input.copyTo(output) }
            }
        }
        if (out.exists()) out.deleteRecursively()
        staging.renameTo(out)
        setActive(context, DEMO_ID)
    }
}
