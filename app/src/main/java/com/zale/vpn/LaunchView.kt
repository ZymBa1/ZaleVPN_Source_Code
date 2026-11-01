package com.zale.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/** Полноэкранная анимация запуска сети: кольца, дуги, частицы и статус. */
class LaunchView(ctx: Context) : View(ctx) {
    var subtitle = ""
        set(v) {
            field = v
            invalidate()
        }

    private val d = ctx.resources.displayMetrics.density
    private var t = 0f

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = T.face(true)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = T.face(false)
    }
    private val scrim = Paint()

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    fun play() {
        if (!anim.isStarted) anim.start()
    }

    fun pause() {
        if (anim.isStarted) anim.cancel()
    }

    fun show(msg: String) {
        subtitle = msg
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(220).start()
        }
        play()
    }

    fun hide() {
        if (visibility != VISIBLE) return
        pause()
        animate().alpha(0f).setDuration(260).withEndAction {
            if (alpha == 0f) visibility = GONE
        }.start()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        scrim.color = T.alpha(T.BG1, 242)
        c.drawRect(0f, 0f, w, h, scrim)

        title.typeface = T.face(true)
        small.typeface = T.face(false)

        val cx = w / 2f
        val cy = h * 0.42f
        val base = w * 0.13f
        val maxR = w * 0.44f

        // расходящиеся кольца
        for (i in 0..4) {
            val q = (t + i / 5f) % 1f
            ring.strokeWidth = ((3.2f - i * 0.45f) * d).coerceAtLeast(1f)
            ring.color = T.alpha(T.CYAN, ((1f - q) * (1f - q) * 160f).toInt())
            c.drawCircle(cx, cy, base * 0.9f + q * maxR, ring)
        }
        for (i in 0..3) {
            val q = (t + 0.35f + i / 4f) % 1f
            ring.strokeWidth = 2f * d
            ring.color = T.alpha(T.VIOLET, ((1f - q) * 90f).toInt())
            c.drawCircle(cx, cy, base * 1.2f + q * maxR * 0.9f, ring)
        }

        // ядро
        fill.shader = RadialGradient(
            cx, cy, base * 1.7f,
            intArrayOf(
                T.alpha(T.CYAN, 170),
                T.alpha(T.VIOLET, 90),
                T.alpha(T.VIOLET, 0)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, base * 1.7f, fill)
        fill.shader = null

        // вращающиеся дуги
        val rect = RectF(cx - base, cy - base, cx + base, cy + base)
        arc.strokeWidth = 5f * d
        arc.color = T.CYAN
        c.drawArc(rect, t * 360f, 110f, false, arc)
        arc.strokeWidth = 3.5f * d
        arc.color = T.PINK
        c.drawArc(rect, -t * 360f - 40f, 70f, false, arc)
        arc.strokeWidth = 2f * d
        arc.color = T.alpha(T.CYAN, 140)
        c.drawArc(rect, t * 220f + 120f, 45f, false, arc)

        // орбитальные точки
        fill.color = T.alpha(T.CYAN, 190)
        for (i in 0 until 10) {
            val a = t * 2 * Math.PI + i * Math.PI / 5
            val rr = base * (1.9f + 0.12f * sin(a * 2.0).toFloat())
            val x = cx + (rr * cos(a)).toFloat()
            val y = cy + (rr * sin(a)).toFloat()
            c.drawCircle(x, y, (2.6f + (i % 3)) * d, fill)
        }

        // заголовок и статус
        title.textSize = 21f * d
        title.letterSpacing = 0.22f
        title.color = T.TEXT
        c.drawText("ЗАПУСК СЕТИ", cx, cy + base * 2.6f, title)

        small.textSize = 14f * d
        small.color = T.MUTED
        val dots = ".".repeat(((t * 4).toInt()) % 4)
        val msg = subtitle.ifBlank { "Подготовка…" }
        c.drawText(msg + dots, cx, cy + base * 2.6f + 30f * d, small)

        // подсказка отмены
        small.textSize = 12f * d
        small.color = T.alpha(T.MUTED, 150)
        c.drawText("Нажмите, чтобы отменить", cx, h * 0.86f, small)
    }
}
