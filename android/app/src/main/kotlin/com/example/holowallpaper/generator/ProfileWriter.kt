package com.example.holowallpaper.generator

import android.content.Context
import android.graphics.Bitmap
import com.example.holowallpaper.model.CardDesign
import com.example.holowallpaper.wallpaper.ProfileStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** 写完全部五层和设计配置才发布目录，失败不切换当前壁纸。 */
object ProfileWriter {
    fun write(
        context: Context,
        layers: LayerBuilder.Layers,
        prompt: String,
        sourceName: String,
        design: CardDesign,
    ): String {
        val id = "p" + UUID.randomUUID().toString()
        val staging = File(ProfileStore.dir(context), "$id.tmp")
        check(staging.mkdirs()) { "无法建立卡片目录" }
        try {
            save(layers.subject, File(staging, "subject.png"))
            save(layers.background, File(staging, "background.png"))
            save(layers.text, File(staging, "text.png"))
            save(layers.lineart, File(staging, "lineart.png"))
            save(layers.effects, File(staging, "effects.png"))
            val assets = JSONObject()
            listOf("subject", "background", "text", "lineart", "effects").forEach { assets.put(it, "./$it.png") }
            val config = design.toJson().put("assets", assets)
                .put("safeArea", JSONObject().put("scale", 1).put("offset", org.json.JSONArray().put(0).put(0)))
            File(staging, "card-config.json").writeText(config.toString(), Charsets.UTF_8)
            val meta = JSONObject().put("title", design.title).put("prompt", prompt)
                .put("source", "本地照片 + $sourceName 设计").put("createdAt", System.currentTimeMillis())
            File(staging, "meta.json").writeText(meta.toString(), Charsets.UTF_8)
            check(staging.renameTo(File(ProfileStore.dir(context), id))) { "卡片保存失败" }
            ProfileStore.setActive(context, id)
            return id
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun save(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "卡片图层保存失败" }
        }
    }
}
