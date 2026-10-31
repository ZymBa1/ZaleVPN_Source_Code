package com.zale.vpn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Реальный логотип приложения (PNG с прозрачным фоном). */
object Art {
    @Volatile
    private var cached: Bitmap? = null

    @Volatile
    private var failed = false

    fun logo(ctx: Context): Bitmap? {
        cached?.let { return it }
        if (failed) return null
        return try {
            val b = BitmapFactory.decodeResource(ctx.resources, R.drawable.zale_logo)
            if (b == null) {
                failed = true
                null
            } else {
                cached = b
                b
            }
        } catch (e: Exception) {
            failed = true
            null
        }
    }
}
