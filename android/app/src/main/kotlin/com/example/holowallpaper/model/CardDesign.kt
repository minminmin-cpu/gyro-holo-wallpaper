package com.example.holowallpaper.model

import org.json.JSONObject
import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader

/** 模型只能控制这些排版、配色与 RuiC 参数，不能提供资源路径或任意配置。 */
data class CardDesign(
    val title: String, val subtitle: String, val collection: String, val edition: String,
    val palette: JSONObject, val typography: JSONObject, val decoration: JSONObject,
    val parameters: JSONObject, val finish: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("subtitle", subtitle)
        .put("collection", collection).put("edition", edition).put("palette", palette)
        .put("typography", typography).put("decoration", decoration)
        .put("parameters", parameters).put("finish", finish)

    data class UserCopy(
        val title: String = "", val subtitle: String = "", val collection: String = "",
        val edition: String = "", val hideSubtitle: Boolean = false,
    ) {
        fun applyTo(design: CardDesign): CardDesign = design.copy(
            title = title.ifBlank { design.title },
            subtitle = if (hideSubtitle) "" else subtitle.ifBlank { design.subtitle },
            collection = collection.ifBlank { design.collection },
            edition = edition.ifBlank { design.edition },
        )

        fun modelPrompt(prompt: String): String = prompt + "\n卡片文案设置（用户优先；空字段由模型设计）：\n" +
            JSONObject().put("priority", "user").put("title", title).put("subtitle", subtitle)
                .put("collection", collection).put("edition", edition).put("hideSubtitle", hideSubtitle).toString()
    }

    companion object {
        fun parseUserCopy(request: JSONObject): UserCopy {
            if (!request.has("copy")) return UserCopy()
            val j = request.getJSONObject("copy")
            keys(j, setOf("priority", "title", "subtitle", "collection", "edition", "hideSubtitle"))
            require(j.get("priority") == "user") { "文案必须以用户输入优先" }
            require(j.get("hideSubtitle") is Boolean) { "隐藏小字开关必须为布尔值" }
            return UserCopy(label(j, "title", 12, allowEmpty = true),
                label(j, "subtitle", 32, allowEmpty = true),
                label(j, "collection", 24, allowEmpty = true),
                label(j, "edition", 24, allowEmpty = true), j.getBoolean("hideSubtitle"))
        }

        val INSTRUCTION = """
            你是照片典藏卡排版设计师。根据用户的照片（若提供）及描述输出唯一、完整、严格 JSON 对象。
            仅设计文字、配色、排版与 RuiC 视差参数；原始照片由客户端本地合成。
            不生成或修改图片，不输出图片、链接、路径、代码、Markdown 或额外字段。
            所有字段均必填，结构如下：
            {"title":"不超过12字标题","subtitle":"不超过32字副标题","collection":"不超过24字典藏名",
             "edition":"不超过24字编号","palette":{"background":"#10182C","accent":"#A8C8F0","text":"#F6F4ED"},
             "typography":{"align":"center","titleSize":72},
             "decoration":{"frame":"double","motif":"stars","density":36},
             "parameters":{"subjectScale":1.0,"subjectDepth":0.4,"backgroundDepth":-0.25,"effectsDepth":0.75,"foil":0.65},"finish":0}
            约束：颜色仅 #RRGGBB；align 为 left/center；titleSize 为48到88的整数；frame 为 single/double；
            motif 为 stars/orbits/diamonds；density 为12到64的整数；finish 为0到3的整数。
            subjectScale 为0.9到1.1；subjectDepth 为0.2到0.6；backgroundDepth 为-0.4到-0.1；
            effectsDepth 为0.65到0.85（须与主体不同层）；foil 为0到1。各层均为1024×1536。
            用户提供的 copy 文案设置优先于描述：非空字段逐字使用，空字段由你设计；hideSubtitle 为 true 时 subtitle 必须为空字符串。
            subtitle 允许空字符串，其余文案不能为空。精简文案，留足照片展示空间，以克制的边框和装饰形成精美典藏卡。
        """.trimIndent()

        fun parse(text: String): CardDesign {
            val j = JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                val value = readObject(reader, 0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "模型须返回唯一 JSON 对象" }
                value
            }
            keys(j, setOf("title", "subtitle", "collection", "edition", "palette", "typography", "decoration", "parameters", "finish"))
            val palette = j.getJSONObject("palette")
            keys(palette, setOf("background", "accent", "text"))
            listOf("background", "accent", "text").forEach {
                require(palette.get(it) is String && palette.getString(it).matches(Regex("#[0-9a-fA-F]{6}"))) {
                    "设计颜色 $it 必须为 #RRGGBB"
                }
            }
            val typography = j.getJSONObject("typography")
            keys(typography, setOf("align", "titleSize"))
            choice(typography, "align", setOf("left", "center"))
            integer(typography, "titleSize", 48, 88)
            val decoration = j.getJSONObject("decoration")
            keys(decoration, setOf("frame", "motif", "density"))
            choice(decoration, "frame", setOf("single", "double"))
            choice(decoration, "motif", setOf("stars", "orbits", "diamonds"))
            integer(decoration, "density", 12, 64)
            val p = j.getJSONObject("parameters")
            keys(p, setOf("subjectScale", "subjectDepth", "backgroundDepth", "effectsDepth", "foil"))
            number(p, "subjectScale", 0.9, 1.1)
            number(p, "subjectDepth", 0.2, 0.6)
            number(p, "backgroundDepth", -0.4, -0.1)
            number(p, "effectsDepth", 0.65, 0.85)
            number(p, "foil", 0.0, 1.0)
            return CardDesign(label(j, "title", 12), label(j, "subtitle", 32, allowEmpty = true),
                label(j, "collection", 24), label(j, "edition", 24),
                palette, typography, decoration, p, integer(j, "finish", 0, 3))
        }

        private fun readObject(reader: JsonReader, depth: Int): JSONObject {
            require(depth <= 2) { "设计 JSON 嵌套过深" }
            val result = JSONObject()
            reader.beginObject()
            var count = 0
            while (reader.hasNext()) {
                require(++count <= 16) { "设计 JSON 字段过多" }
                val name = reader.nextName()
                require(!result.has(name)) { "设计 JSON 含重复字段" }
                val value: Any = when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> readObject(reader, depth + 1)
                    JsonToken.STRING -> reader.nextString()
                    JsonToken.NUMBER -> reader.nextDouble()
                    else -> error("设计 JSON 只允许对象、字符串与数值")
                }
                result.put(name, value)
            }
            reader.endObject()
            return result
        }

        private fun keys(j: JSONObject, allowed: Set<String>) {
            require(j.keys().asSequence().toSet() == allowed) { "设计 JSON 字段缺失或包含未允许字段" }
        }

        private fun label(j: JSONObject, key: String, max: Int, allowEmpty: Boolean = false): String {
            val raw = j.get(key)
            require(raw is String && (allowEmpty || raw.isNotBlank()) && raw.length <= max && raw.none { it.isISOControl() } &&
                !raw.contains(Regex("(?i)(https?:|data:|file:|<|>)"))) { "设计文案 $key 无效或过长" }
            return raw.trim()
        }

        private fun choice(j: JSONObject, key: String, choices: Set<String>) {
            require(j.get(key) is String && j.getString(key) in choices) { "设计选项 $key 不受支持" }
        }

        private fun number(j: JSONObject, key: String, min: Double, max: Double): Double {
            val raw = j.get(key)
            require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() in min..max) {
                "设计参数 $key 超出允许范围"
            }
            return raw.toDouble()
        }

        private fun integer(j: JSONObject, key: String, min: Int, max: Int): Int {
            val value = number(j, key, min.toDouble(), max.toDouble())
            require(value == value.toInt().toDouble()) { "设计参数 $key 必须为整数" }
            return value.toInt()
        }
    }
}
