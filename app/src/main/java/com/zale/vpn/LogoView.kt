package com.zale.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/** Логотип ZaleVPN: реальный PNG, а при его отсутствии — векторный щит. */
class LogoView(ctx: Context) : View(ctx) {
    private val bmp = Art.logo(ctx)
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val b = bmp
        if (b != null && b.width > 0 && b.height > 0) {
            val k = minOf(w / b.width, h / b.height)
            val bw = b.width * k
            val bh = b.height * k
            dst.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
            canvas.drawBitmap(b, null, dst, img)
        } else {
            val size = minOf(w, h)
            Logo.draw(canvas, w / 2f, h / 2f, size)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val w = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.EXACTLY)
            View.MeasureSpec.getSize(widthMeasureSpec)
        else
            (240 * density).toInt()
        val h = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY)
            View.MeasureSpec.getSize(heightMeasureSpec)
        else
            w
        setMeasuredDimension(w, h)
    }
}

/** Векторная отрисовка щита — запасной вариант, если PNG недоступен. */
object Logo {
    private const val TXT = "ZaleVPN"

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC)
    }
    private val path = Path()

    fun draw(c: Canvas, cx: Float, cy: Float, width: Float) {
        if (width <= 0f) return
        val w = width
        val h = w * 0.84f
        val left = cx - w / 2f
        val right = cx + w / 2f
        val top = cy - h / 2f
        val bottom = cy + h / 2f

        path.reset()
        path.moveTo(cx, top)
        path.lineTo(right - w * 0.035f, top + h * 0.10f)
        path.lineTo(right, top + h * 0.18f)
        path.lineTo(right, top + h * 0.52f)
        path.cubicTo(right, top + h * 0.76f, cx + w * 0.27f, top + h * 0.93f, cx, bottom)
        path.cubicTo(cx - w * 0.27f, top + h * 0.93f, left, top + h * 0.76f, left, top + h * 0.52f)
        path.lineTo(left, top + h * 0.18f)
        path.lineTo(left + w * 0.035f, top + h * 0.10f)
        path.close()

        // Неоновое свечение вокруг щита.
        stroke.strokeWidth = w * 0.075f
        stroke.color = T.alpha(0xFF00E5FF.toInt(), 32)
        c.drawPath(path, stroke)
        stroke.strokeWidth = w * 0.038f
        stroke.color = T.alpha(0xFF00E5FF.toInt(), 90)
        c.drawPath(path, stroke)

        // Основной градиент: бирюза слева, синий справа.
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(
            left, top, right, bottom,
            intArrayOf(
                0xFF7DFBFF.toInt(), 0xFF29E6FF.toInt(), 0xFF00A8FF.toInt(),
                0xFF0A5CFF.toInt(), 0xFF1E2BFF.toInt()
            ),
            floatArrayOf(0f, 0.30f, 0.55f, 0.80f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, fill)

        // Блик слева и затемнение правой половины — объём.
        c.save()
        c.clipPath(path)
        fill.shader = LinearGradient(
            cx, top, right, bottom,
            intArrayOf(0x00001133, 0x88001133.toInt()),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(cx, top, right, bottom, fill)
        fill.shader = LinearGradient(
            left, top, left + w * 0.5f, top + h * 0.5f,
            intArrayOf(0x66FFFFFF, 0x00FFFFFF),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(left, top, cx, top + h * 0.46f, fill)
        c.restore()

        // Внутренняя тёмная окантовка и тонкий световой контур.
        stroke.strokeWidth = w * 0.022f
        stroke.color = T.alpha(0xFF04102B.toInt(), 230)
        c.drawPath(path, stroke)
        stroke.strokeWidth = w * 0.007f
        stroke.color = 0xAAFFFFFF.toInt()
        c.drawPath(path, stroke)

        // Надпись ZaleVPN с тенью, обводкой и градиентом.
        text.typeface = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC)
        text.textSize = w * 0.155f
        val ty = cy + text.textSize * 0.36f

        text.shader = null
        text.style = Paint.Style.FILL
        text.color = 0xCC020A1E.toInt()
        c.drawText(TXT, cx + w * 0.018f, ty + w * 0.020f, text)

        text.style = Paint.Style.STROKE
        text.strokeWidth = w * 0.020f
        text.strokeJoin = Paint.Join.ROUND
        text.color = 0xFF04102B.toInt()
        c.drawText(TXT, cx, ty, text)

        text.style = Paint.Style.FILL
        text.shader = LinearGradient(
            cx - w * 0.36f, ty - text.textSize,
            cx + w * 0.36f, ty + text.textSize,
            intArrayOf(
                0xFFFFFFFF.toInt(), 0xFFE8FEFF.toInt(),
                0xFF6FF0FF.toInt(), 0xFFB9F7FF.toInt()
            ),
            null, Shader.TileMode.CLAMP
        )
        c.drawText(TXT, cx, ty, text)
        text.shader = null
    }
}
