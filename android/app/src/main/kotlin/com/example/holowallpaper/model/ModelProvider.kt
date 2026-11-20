package com.example.holowallpaper.model

import com.example.holowallpaper.security.KeyVault
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID

/** 用户自配 Chat 服务；密钥只在设备私有目录中以密文保存。 */
data class ModelProvider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: String = PROTOCOL_TEXT,
    val baseUrl: String = "",
    val modelId: String = "",
    val apiKeyEncrypted: String = "",
    val caps: Set<String> = setOf(CAP_DESIGN),
    val timeoutSec: Int = 60,
) {
    val plainKey: String
        get() = KeyVault.decrypt(apiKeyEncrypted)

    val needsMigration: Boolean
        get() = protocol !in setOf(PROTOCOL_TEXT, PROTOCOL_CHAT) || CAP_DESIGN !in caps

    fun validate() {
        require(!needsMigration) { "旧模型协议已停用，请重新选择文本或视觉 Chat 协议并保存" }
        require(modelId.isNotBlank()) { "请填写模型 ID" }
        val url = URL(baseUrl)
        require(url.protocol in setOf("https", "http") && url.host.isNotBlank() &&
            url.userInfo == null && url.query == null && url.ref == null &&
            !url.path.split('/').any { it.equals("images", ignoreCase = true) }) {
            "Base URL 须为文本/视觉 Chat 服务地址，不能包含图片接口、凭据、查询参数或片段"
        }
        require(timeoutSec in 10..180) { "超时须为 10–180 秒" }
    }

    fun toJson(): JSONObject = redacted().apply {
        remove("hasKey"); remove("needsMigration")
        put("apiKeyEncrypted", apiKeyEncrypted)
    }

    /** 返回给界面时不包含密文或明文密钥。 */
    fun redacted(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("protocol", protocol)
        put("baseUrl", baseUrl); put("modelId", modelId)
        put("caps", JSONArray(caps.sorted()))
        put("hasKey", apiKeyEncrypted.isNotEmpty())
        put("timeoutSec", timeoutSec)
        put("needsMigration", needsMigration)
    }

    companion object {
        const val PROTOCOL_TEXT = "OPENAI_CHAT_TEXT"
        const val PROTOCOL_CHAT = "OPENAI_CHAT_VISION"
        const val CAP_DESIGN = "DESIGN_JSON"
        const val CAP_VISION = "VISION"

        fun from(j: JSONObject): ModelProvider {
            val arr = j.optJSONArray("caps")
            return ModelProvider(
                id = j.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = j.optString("name", "未命名模型"),
                protocol = j.optString("protocol"),
                baseUrl = j.optString("baseUrl"),
                modelId = j.optString("modelId"),
                apiKeyEncrypted = j.optString("apiKeyEncrypted"),
                caps = if (arr == null) emptySet() else (0 until arr.length()).map { arr.optString(it) }.toSet(),
                timeoutSec = j.optInt("timeoutSec", 60),
            )
        }

        /** 空密钥保留已有密文；明确保存新协议后才完成迁移。 */
        fun fromInput(j: JSONObject, existing: ModelProvider? = null): ModelProvider {
            val id = j.optString("id").ifBlank { UUID.randomUUID().toString() }
            val previous = existing?.takeIf { it.id == id }
            val inputKey = j.optString("apiKey")
            val protocol = j.optString("protocol")
            return ModelProvider(
                id = id,
                name = j.optString("name").ifBlank { "未命名模型" },
                protocol = protocol,
                baseUrl = j.optString("baseUrl").trim().trimEnd('/'),
                modelId = j.optString("modelId").trim(),
                apiKeyEncrypted = if (inputKey.isBlank()) previous?.apiKeyEncrypted.orEmpty()
                    else KeyVault.encrypt(inputKey),
                caps = if (protocol == PROTOCOL_CHAT) setOf(CAP_DESIGN, CAP_VISION) else setOf(CAP_DESIGN),
                timeoutSec = j.optInt("timeoutSec", 60),
            ).also { it.validate() }
        }
    }
}
