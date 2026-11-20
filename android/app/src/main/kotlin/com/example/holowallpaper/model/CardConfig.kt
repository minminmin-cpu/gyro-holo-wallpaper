package com.example.holowallpaper.model

import org.json.JSONObject

/** 与 web/src/config.js 的 DEFAULT_CONFIG.parameters / card-config.json 一一对应。 */
data class CardConfig(
    val finish: Float = 0f,
    val subjectScale: Float = 1.25f,
    val subjectDepth: Float = 0.40f,
    val backgroundDepth: Float = -0.25f,
    val effectsDepth: Float = 0.50f,
    val foil: Float = 0.65f,
    val glintGain: Float = 0.85f,
    val glintSigma: Float = 0.20f,
    val glintRange: Float = 0.42f,
    val ghost: Float = 0.28f,
    val cardMode: Float = 1f,
    val cardScale: Float = 1f,
    val tiltGain: Float = 0.34f,
    val relief: Float = 0.55f,
    val lineDepth: Float = 0.22f,
    val trail: Float = 0.85f,
    val safeScale: Float = 1f,
    val safeOffsetX: Float = 0f,
    val safeOffsetY: Float = 0f,
    val fitX: Float = 1f,
    val fitY: Float = 1f,
) {
    companion object {
        fun from(json: JSONObject): CardConfig {
            val p = json.optJSONObject("parameters") ?: JSONObject()
            val safe = json.optJSONObject("safeArea") ?: JSONObject()
            val offset = safe.optJSONArray("offset")
            val fit = json.optJSONArray("fit")
            return CardConfig(
                finish = json.optDouble("finish", 0.0).toFloat(),
                subjectScale = p.optDouble("subjectScale", 1.25).toFloat(),
                subjectDepth = p.optDouble("subjectDepth", 0.40).toFloat(),
                backgroundDepth = p.optDouble("backgroundDepth", -0.25).toFloat(),
                effectsDepth = p.optDouble("effectsDepth", 0.50).toFloat(),
                foil = p.optDouble("foil", 0.65).toFloat(),
                glintGain = p.optDouble("glintGain", 0.85).toFloat(),
                glintSigma = p.optDouble("glintSigma", 0.20).toFloat(),
                glintRange = p.optDouble("glintRange", 0.42).toFloat(),
                ghost = p.optDouble("ghost", 0.28).toFloat(),
                cardMode = p.optDouble("cardMode", 1.0).toFloat(),
                cardScale = p.optDouble("cardScale", 1.0).toFloat(),
                tiltGain = p.optDouble("tiltGain", 0.34).toFloat(),
                relief = p.optDouble("relief", 0.55).toFloat(),
                lineDepth = p.optDouble("lineDepth", 0.22).toFloat(),
                trail = p.optDouble("trail", 0.85).toFloat(),
                safeScale = safe.optDouble("scale", 1.0).toFloat(),
                safeOffsetX = offset?.optDouble(0, 0.0)?.toFloat() ?: 0f,
                safeOffsetY = offset?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                fitX = fit?.optDouble(0, 1.0)?.toFloat() ?: 1f,
                fitY = fit?.optDouble(1, 1.0)?.toFloat() ?: 1f,
            )
        }
    }
}
