package com.example.holowallpaper.bridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import com.example.holowallpaper.MainActivity
import com.example.holowallpaper.generator.LayerBuilder
import com.example.holowallpaper.generator.ProfileWriter
import com.example.holowallpaper.model.CardDesign
import com.example.holowallpaper.model.CardDesignClient
import com.example.holowallpaper.model.ModelProvider
import com.example.holowallpaper.model.ProviderRegistry
import com.example.holowallpaper.wallpaper.ProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** WebView <-> 原生桥。注入名 HoloAndroid；所有耗时操作在后台线程，
 *  结果通过 activity.runJs 回调 window.__onNative.*。 */
class NativeBridge(private val activity: MainActivity) {

    private val context = activity.applicationContext
    private val client = CardDesignClient()

    private fun ok(body: JSONObject = JSONObject()): String = body.put("ok", true).toString()
    private fun fail(reason: String?): String =
        JSONObject().put("ok", false).put("reason", reason ?: "未知错误").toString()

    @JavascriptInterface
    fun listProviders(): String = try {
        val items = JSONArray()
        ProviderRegistry.list(context).forEach { items.put(it.redacted()) }
        ok(JSONObject().put("items", items))
    } catch (e: Exception) {
        fail(e.message)
    }

    private fun providerFromInput(input: JSONObject): ModelProvider {
        val id = input.optString("id")
        val existing = if (id.isBlank()) null else ProviderRegistry.byId(context, id)
        return ModelProvider.fromInput(input, existing)
    }

    @JavascriptInterface
    fun saveProvider(json: String): String = try {
        val provider = providerFromInput(JSONObject(json))
        provider.validate()
        ProviderRegistry.save(context, provider)
        ok(JSONObject().put("id", provider.id))
    } catch (e: Exception) {
        fail(e.message)
    }

    @JavascriptInterface
    fun deleteProvider(id: String): String = try {
        ProviderRegistry.remove(context, id)
        ok()
    } catch (e: Exception) {
        fail(e.message)
    }

    /** 异步：立即返回 pending，结果回调 onTestResult，避免阻塞 WebView 线程。 */
    @JavascriptInterface
    fun testProvider(json: String): String {
        Thread {
            val payload = try {
                val provider = providerFromInput(JSONObject(json))
                val (success, detail) = client.probe(provider)
                if (success) ok(JSONObject().put("detail", detail))
                else fail(detail)
            } catch (e: Exception) {
                fail(e.message)
            }
            activity.runJs("window.__onNative.onTestResult(" + JSONObject.quote(payload) + ")")
        }.start()
        return ok(JSONObject().put("status", "pending"))
    }

    /** 照片 + 描述由 Chat 模型规划设计 JSON，再从原始照片本地合成；失败不落 Profile。 */
    @JavascriptInterface
    fun generate(json: String): String {
        val jobId = "job-" + System.currentTimeMillis()
        val request = try {
            JSONObject(json)
        } catch (e: Exception) {
            return fail("请求格式错误")
        }
        Thread {
            val payload = doGenerate(request, jobId)
            activity.runJs("window.__onNative.onGenerateResult(" + JSONObject.quote(payload) + ")")
        }.start()
        return ok(JSONObject().put("jobId", jobId).put("status", "pending"))
    }

    private fun doGenerate(request: JSONObject, jobId: String): String {
        return try {
            require(request.optString("entry", "photo") == "photo") { "仅支持上传照片并填写设计描述" }
            val prompt = request.optString("prompt").trim()
            require(prompt.isNotBlank() && prompt.length <= 2000) { "请填写不超过 2000 字的设计描述" }
            val userCopy = CardDesign.parseUserCopy(request)
            val providerId = request.optString("providerId")
            require(providerId.isNotBlank()) { "请先添加并选择文本或视觉 Chat 模型" }
            val provider = ProviderRegistry.byId(context, providerId)
                ?: error("所选模型不存在，请重新选择模型")
            provider.validate()
            val photoPath = request.optString("photoPath").ifBlank { PhotoHandoff.path ?: "" }
            require(photoPath.isNotBlank() && File(photoPath).exists()) { "没有拿到照片，请重新选择照片" }
            val photo = BitmapFactory.decodeFile(photoPath) ?: error("照片不可读取，请重新选择")
            val profileId = try {
                val design = userCopy.applyTo(client.design(provider, userCopy.modelPrompt(prompt), photo))
                val layers = LayerBuilder.build(photo, design)
                try {
                    ProfileWriter.write(context, layers, prompt, provider.name, design)
                } finally {
                    listOf(layers.subject, layers.background, layers.text, layers.lineart, layers.effects)
                        .forEach { it.recycle() }
                }
            } finally {
                photo.recycle()
            }
            ok(JSONObject().put("jobId", jobId).put("profileId", profileId)
                .put("source", "photo+design"))
        } catch (e: Exception) {
            fail(e.message)
        }
    }

    @JavascriptInterface
    fun listProfiles(): String = try {
        val items = JSONArray()
        ProfileStore.dir(context).listFiles()
            ?.filter { it.isDirectory && File(it, "card-config.json").exists() }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { folder ->
                val metaFile = File(folder, "meta.json")
                val meta = if (metaFile.exists()) {
                    JSONObject(metaFile.readText(Charsets.UTF_8))
                } else JSONObject()
                items.put(JSONObject().apply {
                    put("id", folder.name)
                    put("title", meta.optString("title").ifBlank { folder.name })
                    put("meta", meta.optString("source").ifBlank { "内置演示" })
                })
            }
        ok(JSONObject().put("items", items))
    } catch (e: Exception) {
        fail(e.message)
    }

    @JavascriptInterface
    fun setActiveProfile(id: String): String = try {
        ProfileStore.setActive(context, id)
        ok()
    } catch (e: Exception) {
        fail(e.message)
    }

    @JavascriptInterface
    fun setWallpaper(): String {
        activity.runOnUiThread { activity.openWallpaperChooser() }
        return ok()
    }

    @JavascriptInterface
    fun pickPhoto(): String {
        activity.runOnUiThread { activity.launchPhotoPicker() }
        return ok()
    }

    @JavascriptInterface
    fun exportFrame(dataUrl: String): String = try {
        val base64 = dataUrl.substringAfter(",")
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, "holo-" + System.currentTimeMillis() + ".png")
        file.writeBytes(Base64.decode(base64, Base64.DEFAULT))
        ok(JSONObject().put("path", file.absolutePath))
    } catch (e: Exception) {
        fail(e.message)
    }

    /** P0：把 Profile 的真实素材缩成预览用纹理回传（data URI），
     *  前端用同一份 holo.frag 的 WebGL 渲染，代替原来的示意画布。
     *  subject 用 PNG 保留 alpha；background 用 JPEG 压体积；总大小控制在 ~400KB。 */
    @JavascriptInterface
    fun previewTextures(profileId: String): String = try {
        val dir = if (profileId.isBlank()) {
            ProfileStore.activeDir(context)
        } else {
            File(ProfileStore.dir(context), profileId)
        }
        require(dir.exists()) { "Profile 不存在: $profileId" }

        val width = 384
        val height = 576

        fun encode(name: String, mime: String): String? {
            val file = File(dir, "$name.png")
            if (!file.exists()) return null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val buffer = ByteArrayOutputStream()
            if (mime == "image/jpeg") {
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, buffer)
            } else {
                scaled.compress(Bitmap.CompressFormat.PNG, 90, buffer)
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            val base64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
            return "data:$mime;base64,$base64"
        }

        val textures = JSONObject().apply {
            put("width", width)
            put("height", height)
            put("subject", encode("subject", "image/png") ?: JSONObject.NULL)
            put("background", encode("background", "image/jpeg") ?: JSONObject.NULL)
            put("text", encode("text", "image/png") ?: JSONObject.NULL)
            put("lineart", encode("lineart", "image/png") ?: JSONObject.NULL)
            put("effects", encode("effects", "image/png") ?: JSONObject.NULL)
        }
        val configFile = File(dir, "card-config.json")
        if (configFile.exists()) {
            textures.put("config", JSONObject(configFile.readText(Charsets.UTF_8)))
        }
        ok(JSONObject().put("textures", textures).put("profileId", dir.name))
    } catch (e: Exception) {
        fail(e.message)
    }

    /** 「我的壁纸」列表缩略图：把 subject.png 缩到 160×240 转 JPEG（约 10KB），
     *  避免列表里只显示装饰性渐变、看不到真实素材。 */
    @JavascriptInterface
    fun profileThumb(profileId: String): String = try {
        val dir = File(ProfileStore.dir(context), profileId)
        val file = File(dir, "subject.png")
        if (!file.exists()) {
            ok(JSONObject().put("thumb", ""))
        } else {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null) {
                ok(JSONObject().put("thumb", ""))
            } else {
                val scaled = Bitmap.createScaledBitmap(bitmap, 160, 240, true)
                val buffer = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 78, buffer)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                ok(JSONObject().put(
                    "thumb",
                    "data:image/jpeg;base64," + Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP),
                ))
            }
        }
    } catch (e: Exception) {
        fail(e.message)
    }

    /** P1：把调参写回当前 Profile 的 card-config.json（原子写）。
     *  finish 写顶层，其余写进 parameters；壁纸服务检测到 mtime 变化后热重载参数。 */
    @JavascriptInterface
    fun updateConfig(params: String): String = try {
        val dir = ProfileStore.activeDir(context)
        val file = File(dir, "card-config.json")
        require(file.exists()) { "当前 Profile 缺少 card-config.json" }
        val config = JSONObject(file.readText(Charsets.UTF_8))
        val parameters = config.optJSONObject("parameters") ?: JSONObject()
        val incoming = JSONObject(params)
        val keys = incoming.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "finish") config.put("finish", incoming.get(key))
            else parameters.put(key, incoming.get(key))
        }
        config.put("parameters", parameters)
        val staging = File(dir, "card-config.json.tmp")
        staging.writeText(config.toString(), Charsets.UTF_8)
        if (file.exists()) file.delete()
        staging.renameTo(file)
        ok(JSONObject().put("profileId", dir.name))
    } catch (e: Exception) {
        fail(e.message)
    }

    /** 无 adb 时的自查入口：把关键状态一次性返回给「我的 → 诊断信息」。 */
    @JavascriptInterface
    fun diag(): String = try {
        val profiles = ProfileStore.dir(context).listFiles()
            ?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        ok(JSONObject().apply {
            put("bridge", "m2.1")
            put("activeProfile", ProfileStore.activeId(context))
            put("profiles", JSONArray(profiles))
            put("providerCount", ProviderRegistry.list(context).size)
            put("sdkInt", android.os.Build.VERSION.SDK_INT)
            put("device", android.os.Build.MODEL)
            put("filesDir", context.filesDir.absolutePath)
            put("pendingPhoto", PhotoHandoff.path ?: "")
            put("pendingPhotoError", PhotoHandoff.lastError ?: "")
            put("pendingPhotoAt", PhotoHandoff.updatedAt)
        })
    } catch (e: Exception) {
        fail(e.message)
    }
}
