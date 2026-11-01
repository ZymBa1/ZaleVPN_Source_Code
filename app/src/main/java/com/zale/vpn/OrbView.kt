package com.zale.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/** Анимированный «шар» статуса: пульсирует и расходящиеся кольца при подключении. */
class OrbView(ctx: Context) : View(ctx) {
    var phase: Phase = Phase.IDLE
        set(v) {
            field = v
            invalidate()
        }

    private val d = ctx.resources.displayMetrics.density
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5f * d
        color = 0xFFFFFFFF.toInt()
    }
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = width * 0.24f
        val col = when (phase) {
            Phase.IDLE -> T.VIOLET
            Phase.CONNECTING -> T.AMBER
            Phase.CONNECTED -> T.GREEN
            Phase.ERROR -> T.RED
        }

        if (phase == Phase.CONNECTED || phase == Phase.CONNECTING) {
            for (i in 0..2) {
                val q = (t + i / 3f) % 1f
                ring.color = T.alpha(col, ((1f - q) * 130).toInt())
                c.drawCircle(cx, cy, base * 1.15f + q * width * 0.26f, ring)
            }
        }

        glow.shader = RadialGradient(
            cx, cy, base * 1.9f,
            intArrayOf(T.alpha(col, 120), T.alpha(col, 0)), null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, base * 1.9f, glow)

        val pulse = if (phase == Phase.IDLE) 1f else 1f + 0.04f * sin(t * 2 * Math.PI).toFloat()
        val r = base * pulse
        core.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            T.mix(col, 0xFFFFFFFF.toInt(), 0.4f), col, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, core)

        if (phase == Phase.CONNECTING) {
            val rect = RectF(cx - base * 1.3f, cy - base * 1.3f, cx + base * 1.3f, cy + base * 1.3f)
            c.drawArc(rect, t * 360f, 90f, false, arc)
        }
    }
}
