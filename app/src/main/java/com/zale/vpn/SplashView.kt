package com.zale.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/** Экран загрузки: вращающиеся кольца, «Xake Team» и полоса загрузки модулей. */
class SplashView(ctx: Context) : View(ctx) {
    var progress = 0f
    var stage = ""

    private val d = ctx.resources.displayMetrics.density
    private var angle = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = T.face(true)
    }
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spin.start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // фон
        p.shader = LinearGradient(0f, 0f, 0f, h, T.BG1, T.BG2, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        val cx = w / 2f
        val cy = h * 0.34f
        val r = w * 0.15f

        // вращающиеся дуги
        val rings = listOf(
            Triple(1.55f, T.CYAN, 1f),
            Triple(1.95f, T.VIOLET, -1.4f),
            Triple(2.35f, T.PINK, 0.7f)
        )
        for ((i, ring) in rings.withIndex()) {
            val rr = r * ring.first
            arc.color = ring.second
            arc.strokeWidth = (5 - i) * d
            val rect = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(rect, angle * ring.third + i * 60f, 110f - i * 20f, false, arc)
            c.drawArc(rect, angle * ring.third + i * 60f + 180f, 50f, false, arc)
        }

        // логотип
        val pulse = 1f + 0.04f * sin(Math.toRadians(angle * 2.0)).toFloat()
        val logoW = r * 4.0f * pulse
        val b = Art.logo(context)
        if (b != null && b.width > 0) {
            val half = logoW / 2f
            c.drawBitmap(b, null, RectF(cx - half, cy - half, cx + half, cy + half), img)
        } else {
            Logo.draw(c, cx, cy, r * 3.1f * pulse)
        }
        txt.shader = null
        txt.color = 0xFFFFFFFF.toInt()
        txt.letterSpacing = 0f

        // «Xake Team» — проявляется слева направо
        val title = "Xake Team"
        txt.textSize = w * 0.105f
        txt.letterSpacing = 0.08f
        val tw = txt.measureText(title)
        val ty = cy + r * 2.35f + w * 0.2f
        val reveal = ((progress - 0.05f) / 0.45f).coerceIn(0f, 1f)
        txt.shader = LinearGradient(
            cx - tw / 2, 0f, cx + tw / 2, 0f,
            intArrayOf(T.CYAN, T.VIOLET, T.PINK), null, Shader.TileMode.CLAMP
        )
        c.save()
        c.clipRect(cx - tw / 2 - 8 * d, ty - txt.textSize, cx - tw / 2 - 8 * d + (tw + 16 * d) * reveal, ty + txt.textSize * 0.4f)
        c.drawText(title, cx, ty, txt)
        c.restore()
        txt.shader = null

        // подпись
        txt.letterSpacing = 0.25f
        txt.textSize = w * 0.036f
        txt.color = T.alpha(T.MUTED, (reveal * 255).toInt())
        c.drawText("ZALEVPN", cx, ty + w * 0.09f, txt)

        // полоса загрузки
        val left = w * 0.12f
        val right = w * 0.88f
        val by = h * 0.86f
        val bh = 8f * d
        p.color = T.alpha(0xFFFFFFFF.toInt(), 34)
        c.drawRoundRect(RectF(left, by, right, by + bh), bh, bh, p)
        val fillTo = left + (right - left) * progress
        if (fillTo > left + bh) {
            p.shader = LinearGradient(left, 0f, right, 0f, intArrayOf(T.CYAN, T.VIOLET, T.PINK), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(left, by, fillTo, by + bh), bh, bh, p)
            p.shader = null
        }
        txt.letterSpacing = 0f
        txt.textSize = 13f * d
        txt.typeface = T.face(false)
        txt.color = T.MUTED
        c.drawText("Загрузка: ${(progress * 100).toInt()}% · $stage", cx, by - 14f * d, txt)
        txt.typeface = T.face(true)
    }
}
