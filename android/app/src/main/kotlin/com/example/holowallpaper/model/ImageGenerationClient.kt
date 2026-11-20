package com.example.holowallpaper.model

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** 仅调用 Chat 获取设计 JSON；不解码模型图片、不下载任何响应资源。 */
class CardDesignClient {
    fun design(provider: ModelProvider, prompt: String, photo: Bitmap): CardDesign {
        provider.validate()
        require(prompt.isNotBlank()) { "请填写卡片设计描述" }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        if (provider.protocol == ModelProvider.PROTOCOL_CHAT) {
            val scale = minOf(1f, 1280f / maxOf(photo.width, photo.height))
            val image = if (scale < 1f) Bitmap.createScaledBitmap(photo,
                (photo.width * scale).roundToInt().coerceAtLeast(1),
                (photo.height * scale).roundToInt().coerceAtLeast(1), true) else photo
            val bytes = ByteArrayOutputStream()
            try {
                check(image.compress(Bitmap.CompressFormat.JPEG, 85, bytes)) { "照片编码失败" }
            } finally {
                if (image !== photo) image.recycle()
            }
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))))
        }
        val body = JSONObject().put("model", provider.modelId).put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", CardDesign.INSTRUCTION))
                .put(JSONObject().put("role", "user").put("content", content)))
        val response = JSONObject(request(provider, "/chat/completions", body.toString()))
        val message = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: error("设计响应缺少 choices[0].message")
        require(!message.has("images") && !message.has("tool_calls")) { "模型必须仅返回设计 JSON 文本" }
        val text = message.opt("content")
        require(text is String && text.isNotBlank()) { "模型未返回设计 JSON 文本" }
        return CardDesign.parse(text)
    }

    private fun request(provider: ModelProvider, suffix: String, body: String? = null): String {
        provider.validate()
        val base = provider.baseUrl.removeSuffix("/chat/completions")
        val connection = URL(base + suffix).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.connectTimeout = provider.timeoutSec * 1000
            connection.readTimeout = provider.timeoutSec * 1000
            connection.setRequestProperty("Accept", "application/json")
            val key = if (provider.apiKeyEncrypted.isEmpty()) "" else provider.plainKey
            if (key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            check(code in 200..299) { "模型服务 HTTP $code，请检查地址、鉴权和模型配置" }
            val limit = if (body == null) 1048576 else 65536
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val out = StringBuilder()
                val chunk = CharArray(4096)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    require(out.length + count <= limit) { "模型响应过大，请检查服务是否按约定返回 JSON" }
                    out.append(chunk, 0, count)
                }
                out.toString()
            }
            return text
        } catch (e: java.io.IOException) {
            throw IllegalStateException("模型服务连接失败，请检查网络、地址及超时配置")
        } finally {
            connection.disconnect()
        }
    }

    /** 仅探活模型列表；不会发起创作请求，也不宣称验证了设计能力。 */
    fun probe(provider: ModelProvider): Pair<Boolean, String> = try {
        request(provider, "/models")
        true to "模型列表可达，尚未验证设计 JSON 或视觉能力"
    } catch (e: Exception) {
        false to (e.message ?: "连接失败")
    }
}
