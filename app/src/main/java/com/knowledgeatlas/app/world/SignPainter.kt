package com.knowledgeatlas.app.world

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils

/**
 * Paints Arabic signage with correct shaping and right-to-left ordering (StaticLayout handles
 * bidi for mixed Arabic/Latin names). Output bitmaps are premultiplied, matching the transparent
 * sign material.
 */
class SignPainter(assets: AssetManager) {
    private val bold: Typeface = runCatching {
        Typeface.Builder(assets, WorldFiles.FONT).setFontVariationSettings("'wght' 800").build()
    }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val regular: Typeface = runCatching {
        Typeface.Builder(assets, WorldFiles.FONT).setFontVariationSettings("'wght' 500").build()
    }.getOrNull() ?: Typeface.DEFAULT

    fun doorSign(sourceNameAr: String, sourceNameEn: String, accent: Int): Bitmap {
        val w = 1024; val h = 300
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        panel(c, w, h, accent, radius = 40f)
        text(c, "أهلاً بك في موقع", 46f, regular, lighten(accent, 0.55f), RectF(40f, 22f, w - 40f, 92f), 1)
        text(c, sourceNameAr, 96f, bold, Color.WHITE, RectF(40f, 86f, w - 40f, 214f), 1, glow = accent)
        text(c, sourceNameEn, 38f, regular, Color.argb(220, 210, 222, 255), RectF(40f, 212f, w - 40f, 270f), 1, rtl = false)
        return bmp
    }

    fun citySign(cityName: String, subtitle: String, accent: Int): Bitmap {
        val w = 2048; val h = 420
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        panel(c, w, h, accent, radius = 70f)
        text(c, cityName, 190f, bold, Color.WHITE, RectF(60f, 30f, w - 60f, 290f), 1, glow = accent)
        text(c, subtitle, 64f, regular, lighten(accent, 0.6f), RectF(60f, 290f, w - 60f, 392f), 1)
        return bmp
    }

    /** Free-floating hologram lettering without a backing panel. */
    fun hologram(cityName: String, accent: Int): Bitmap {
        val w = 2048; val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        text(c, cityName, 230f, bold, Color.WHITE, RectF(20f, 20f, w - 20f, h - 20f), 1, glow = accent, glowRadius = 34f)
        return bmp
    }

    private fun panel(c: Canvas, w: Int, h: Int, accent: Int, radius: Float) {
        val rect = RectF(6f, 6f, w - 6f, h - 6f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.argb(236, 14, 20, 38), Color.argb(236, 6, 9, 20), Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(rect, radius, radius, fill)
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(RectF(rect.left, rect.top, rect.right, rect.top + h * 0.45f), radius, radius, sheen)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            color = accent
            maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(RectF(14f, 14f, w - 14f, h - 14f), radius - 6f, radius - 6f, glow)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = lighten(accent, 0.45f)
        }
        c.drawRoundRect(RectF(14f, 14f, w - 14f, h - 14f), radius - 6f, radius - 6f, border)
    }

    private fun text(
        c: Canvas, value: String, sizePx: Float, face: Typeface, color: Int, box: RectF, maxLines: Int,
        glow: Int? = null, glowRadius: Float = 18f, rtl: Boolean = true
    ) {
        if (value.isBlank()) return
        var size = sizePx
        var layout: StaticLayout
        val width = box.width().toInt()
        while (true) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = face; textSize = size; this.color = color
            }
            layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.LTR)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
            val fits = layout.height <= box.height() && (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 }
            if (fits || size < sizePx * 0.45f) break
            size *= 0.92f
        }
        val top = box.top + (box.height() - layout.height) / 2f
        if (glow != null) {
            val gp = TextPaint(layout.paint).apply {
                this.color = glow
                maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
            }
            val gl = StaticLayout.Builder.obtain(value, 0, value.length, gp, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.LTR)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
            c.save(); c.translate(box.left, top); gl.draw(c); c.restore()
        }
        c.save(); c.translate(box.left, top); layout.draw(c); c.restore()
    }

    companion object {
        fun lighten(color: Int, amount: Float): Int {
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            return Color.rgb(
                (r + (255 - r) * amount).toInt(), (g + (255 - g) * amount).toInt(), (b + (255 - b) * amount).toInt()
            )
        }
    }
}
