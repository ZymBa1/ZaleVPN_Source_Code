package com.zale.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

/** Показ «Политики конфиденциальности» из assets. */
object Privacy {
    private const val ASSET = "privacy_policy.md"

    fun text(ctx: Context): String = try {
        ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "Не удалось загрузить политику конфиденциальности."
    }

    /** Убирает markdown-разметку, чтобы текст нормально читался. */
    private fun plain(src: String): String = src
        .replace("**", "")
        .replace("\\_", "_")
        .replace("\\", "")
        .lines()
        .joinToString("\n") { line ->
            when {
                line.startsWith("## ") -> line.removePrefix("## ").uppercase()
                line.startsWith("# ") -> line.removePrefix("# ").uppercase()
                else -> line
            }
        }

    fun show(activity: Activity, onAccept: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val tv = TextView(activity).apply {
            text = plain(text(activity))
            textSize = 13f
            setTextColor(T.TEXT)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16))
        }
        val scroll = ScrollView(activity).apply { addView(tv) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Политика конфиденциальности")
            .setView(scroll)
            .setPositiveButton("Принять") { _, _ -> onAccept() }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
