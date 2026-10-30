package com.zale.vpn

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View

object T {
    val BG1 = Color.parseColor("#090D24")
    val BG2 = Color.parseColor("#1B0A3C")
    val CARD = Color.parseColor("#1AFFFFFF")
    val CARD_STROKE = Color.parseColor("#2AFFFFFF")
    val CYAN = Color.parseColor("#00E5FF")
    val VIOLET = Color.parseColor("#7C4DFF")
    val PINK = Color.parseColor("#FF4DA6")
    val TEXT = Color.parseColor("#F4F6FF")
    val MUTED = Color.parseColor("#9AA3C7")
    val GREEN = Color.parseColor("#2EF2A0")
    val AMBER = Color.parseColor("#FFC857")
    val RED = Color.parseColor("#FF5470")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun mix(a: Int, b: Int, f: Float): Int {
        val r = (Color.red(a) * (1 - f) + Color.red(b) * f).toInt()
        val g = (Color.green(a) * (1 - f) + Color.green(b) * f).toInt()
        val bl = (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt()
        return Color.rgb(r, g, bl)
    }

    fun bgGradient(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG1, BG2))

    fun brand(ctx: Context, radius: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CYAN, VIOLET, PINK)).apply {
            cornerRadius = dp(ctx, radius).toFloat()
        }

    fun glass(ctx: Context, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dp(ctx, radius).toFloat()
        setStroke(dp(ctx, 1), CARD_STROKE)
    }

    fun outline(ctx: Context, radius: Int, color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(ctx, radius).toFloat()
        setStroke(dp(ctx, 2), color)
    }

    fun solid(ctx: Context, radius: Int, color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radius).toFloat()
    }

    fun ripple(d: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), d, null)

    /** Лёгкая анимация нажатия. */
    fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }
}
