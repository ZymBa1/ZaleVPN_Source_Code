package com.zale.vpn

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View

object T {
    @Volatile private var app: Context? = null

    /** Нужен для загрузки шрифтов из assets. Вызывать до построения интерфейса. */
    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

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

    data class Palette(val name: String, val bg1: Int, val bg2: Int, val accent: Int)

    val palettes = listOf(
        Palette("Неон", Color.parseColor("#090D24"), Color.parseColor("#1B0A3C"), VIOLET),
        Palette("Полночь", Color.parseColor("#01030A"), Color.parseColor("#0A1233"), Color.parseColor("#1E3BFF")),
        Palette("Океан", Color.parseColor("#03141F"), Color.parseColor("#073B4C"), CYAN),
        Palette("Закат", Color.parseColor("#170518"), Color.parseColor("#3A0A24"), PINK)
    )

    @Volatile var palette = 0
        private set

    val BG1: Int get() = palettes[palette].bg1
    val BG2: Int get() = palettes[palette].bg2

    fun setPalette(i: Int) {
        palette = i.coerceIn(0, palettes.size - 1)
    }

    val fontNames = listOf(
        "Системный",
        "Montserrat",
        "Rubik",
        "Oswald",
        "Playfair Display",
        "JetBrains Mono",
        "Roboto Condensed",
        "Manrope",
        "Exo 2",
        "Comfortaa"
    )

    /** null — системный шрифт, иначе имя файла в assets/fonts. */
    private val fontAssets = listOf(
        null,
        "fonts/Montserrat.ttf",
        "fonts/Rubik.ttf",
        "fonts/Oswald.ttf",
        "fonts/PlayfairDisplay.ttf",
        "fonts/JetBrainsMono.ttf",
        "fonts/RobotoCondensed.ttf",
        "fonts/Manrope.ttf",
        "fonts/Exo2.ttf",
        "fonts/Comfortaa.ttf"
    )

    @Volatile var font = 0
        private set

    private val faceCache = HashMap<String, Typeface>()

    fun setFont(i: Int) {
        font = i.coerceIn(0, fontNames.size - 1)
        faceCache.clear()
    }

    @Synchronized
    fun faceOf(index: Int, bold: Boolean): Typeface {
        val idx = index.coerceIn(0, fontNames.size - 1)
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        val key = "$idx:$style"
        return faceCache.getOrPut(key) {
            val asset = fontAssets[idx]
            val ctx = app
            if (asset == null || ctx == null) {
                Typeface.create("sans-serif", style)
            } else {
                try {
                    Typeface.create(Typeface.createFromAsset(ctx.assets, asset), style)
                } catch (e: Exception) {
                    Typeface.create("sans-serif", style)
                }
            }
        }
    }

    fun face(bold: Boolean): Typeface = faceOf(font, bold)

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

    fun swatch(ctx: Context, i: Int): GradientDrawable {
        val p = palettes[i.coerceIn(0, palettes.size - 1)]
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(p.bg1, p.bg2, p.accent)
        ).apply {
            cornerRadius = dp(ctx, 10).toFloat()
            setStroke(dp(ctx, 2), if (i == palette) CYAN else CARD_STROKE)
        }
    }

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
