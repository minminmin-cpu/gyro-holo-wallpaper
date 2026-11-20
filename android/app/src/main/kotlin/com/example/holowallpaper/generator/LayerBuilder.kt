package com.example.holowallpaper.generator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.holowallpaper.model.CardDesign
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 原始照片与受限设计 JSON 本地合成 RuiC 五层；不依赖任何图片生成服务。 */
object LayerBuilder {
    const val W = 1024
    const val H = 1536

    data class Layers(
        val subject: Bitmap, val background: Bitmap, val text: Bitmap,
        val lineart: Bitmap, val effects: Bitmap,
    )

    fun build(source: Bitmap, design: CardDesign): Layers {
        val subject = subject(source)
        return Layers(subject, background(source, design), typography(design), lineart(subject), effects(design))
    }

    private fun filterPaint() = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private fun color(design: CardDesign, key: String) = Color.parseColor(design.palette.getString(key))
    private fun canvasBitmap() = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)

    private fun background(source: Bitmap, design: CardDesign): Bitmap {
        val small = Bitmap.createScaledBitmap(source, 64, 96, true)
        val out = canvasBitmap()
        val canvas = Canvas(out)
        canvas.drawColor(color(design, "background"))
        canvas.drawBitmap(small, null, Rect(0, 0, W, H), filterPaint().apply { alpha = 68 })
        small.recycle()
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(),
                intArrayOf(Color.TRANSPARENT, color(design, "background")), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        return out
    }

    private fun subject(source: Bitmap): Bitmap {
        // 用统一 contain 比例保留照片宽高比，固定标题安全区独立于视差参数。
        val scale = min(848f / source.width, 1010f / source.height)
        val pw = source.width * scale
        val ph = source.height * scale
        val x = (W - pw) / 2f
        val y = 200f + (1010f - ph) / 2f
        val out = canvasBitmap()
        val canvas = Canvas(out)
        val rect = RectF(x, y, x + pw, y + ph)
        canvas.clipPath(Path().apply { addRoundRect(rect, 26f, 26f, Path.Direction.CW) })
        canvas.drawBitmap(source, null, rect, filterPaint())
        return out
    }

    /** 从完成布局的主体提取轮廓，避免线稿与照片位置错开。 */
    private fun lineart(subject: Bitmap): Bitmap {
        val sw = 256
        val sh = 384
        val small = Bitmap.createScaledBitmap(subject, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        small.recycle()
        val gray = IntArray(sw * sh) { i ->
            val c = pixels[i]
            (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
        }
        val edge = IntArray(sw * sh) { Color.WHITE }
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            val gx = abs(gray[i - 1] - gray[i + 1])
            val gy = abs(gray[i - sw] - gray[i + sw])
            if (Color.alpha(pixels[i]) > 128 && gx + gy > 60) edge[i] = Color.BLACK
        }
        val tiny = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        tiny.setPixels(edge, 0, sw, 0, 0, sw, sh)
        val out = Bitmap.createScaledBitmap(tiny, W, H, true)
        tiny.recycle()
        return out
    }

    private fun typography(design: CardDesign): Bitmap {
        val out = canvasBitmap()
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val accent = color(design, "accent")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = accent
        paint.alpha = 200
        canvas.drawRoundRect(RectF(46f, 46f, W - 46f, H - 46f), 30f, 30f, paint)
        if (design.decoration.getString("frame") == "double") {
            paint.strokeWidth = 1f
            paint.alpha = 95
            canvas.drawRoundRect(RectF(58f, 58f, W - 58f, H - 58f), 22f, 22f, paint)
        }
        canvas.drawLine(94f, 160f, W - 94f, 160f, paint)
        canvas.drawLine(94f, 1240f, W - 94f, 1240f, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        val left = design.typography.getString("align") == "left"
        paint.textAlign = if (left) Paint.Align.LEFT else Paint.Align.CENTER
        val x = if (left) 96f else W / 2f
        fun label(value: String, y: Float, size: Float, bold: Boolean, ink: Int) {
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.color = ink
            paint.textSize = size
            val width = paint.measureText(value)
            if (width > W - 192f) paint.textSize *= (W - 192f) / max(width, 1f)
            canvas.drawText(value, x, y, paint)
        }
        label(design.collection, 124f, 28f, false, accent)
        label(design.title, 1336f, design.typography.getInt("titleSize").toFloat(), true, color(design, "text"))
        label(design.subtitle, 1400f, 32f, false, color(design, "text"))
        label(design.edition, 1460f, 24f, false, accent)
        return out
    }

    private fun effects(design: CardDesign): Bitmap {
        val out = canvasBitmap()
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val random = Random(design.toJson().toString().hashCode().toLong())
        val motif = design.decoration.getString("motif")
        paint.color = color(design, "accent")
        repeat(design.decoration.getInt("density")) {
            // 装饰限制在照片外围，照片细节交给随视角变化的材质表现。
            val x = if (random.nextBoolean()) 62f + random.nextFloat() * 48f else W - 110f + random.nextFloat() * 48f
            val y = 200f + random.nextFloat() * 1000f
            val s = 3f + random.nextFloat() * 7f
            paint.alpha = 90 + random.nextInt(100)
            paint.strokeWidth = 1.5f
            paint.style = Paint.Style.STROKE
            when (motif) {
                "orbits" -> canvas.drawOval(RectF(x - s, y - s * 0.5f, x + s, y + s * 0.5f), paint)
                "diamonds" -> canvas.drawPath(Path().apply {
                    moveTo(x, y - s); lineTo(x + s, y); lineTo(x, y + s); lineTo(x - s, y); close()
                }, paint)
                else -> {
                    canvas.drawLine(x - s, y, x + s, y, paint)
                    canvas.drawLine(x, y - s, x, y + s, paint)
                }
            }
        }
        return out
    }
}
