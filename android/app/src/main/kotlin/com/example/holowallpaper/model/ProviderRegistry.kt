package com.example.holowallpaper.model

import android.content.Context
import org.json.JSONArray
import java.io.File

/** 只保存用户配置，不预置模型；旧配置保留待用户在界面重新确认协议。 */
object ProviderRegistry {
    private const val FILE = "providers.json"
    private fun file(context: Context) = File(context.filesDir, FILE)

    fun list(context: Context): List<ModelProvider> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText(Charsets.UTF_8))
        return (0 until arr.length()).map { ModelProvider.from(arr.getJSONObject(it)) }
            .filter { it.id != "builtin-demo" }
    }

    fun byId(context: Context, id: String): ModelProvider? =
        list(context).firstOrNull { it.id == id }

    fun save(context: Context, provider: ModelProvider) {
        provider.validate()
        write(context, list(context).filter { it.id != provider.id } + provider)
    }

    fun remove(context: Context, id: String) {
        write(context, list(context).filter { it.id != id })
    }

    private fun write(context: Context, items: List<ModelProvider>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val target = file(context)
        val staging = File(target.parentFile, target.name + ".tmp")
        staging.writeText(arr.toString(), Charsets.UTF_8)
        check(staging.renameTo(target)) { "模型配置保存失败" }
    }
}
