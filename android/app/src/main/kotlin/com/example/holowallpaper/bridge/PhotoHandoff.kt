package com.example.holowallpaper.bridge

/** 照片交接的兜底通道。
 *
 *  onActivityResult 里调用 evaluateJavascript 时，Activity 尚未 resume，WebView 可能把这次
 *  JS 执行丢弃 —— 前端就会一直停在「正在读取路径…」。这里把复制成功的绝对路径记下来，
 *  即使 JS 回调丢失，doGenerate 仍能用它完成生成。 */
object PhotoHandoff {
    @Volatile
    var path: String? = null

    @Volatile
    var lastError: String? = null

    @Volatile
    var updatedAt: Long = 0L
}
