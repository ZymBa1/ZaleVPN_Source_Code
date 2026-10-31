package com.zale.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.IBinder

/**
 * Foreground-служба, которая держит процесс ZaleVPN живым при погашенном
 * экране и свёрнутом приложении. VPN-туннель поднимает GoBackend, а эта
 * служба даёт процессу foreground-приоритет и постоянное уведомление.
 */
class KeepAliveService : Service() {
    companion object {
        const val ACTION_STOP = "com.zale.vpn.action.STOP"
        private const val CHANNEL_ID = "zale_vpn_state"
        private const val NOTIF_ID = 23070

        @Volatile private var alive = false
        @Volatile private var pendingText: String? = null

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
            } catch (e: Exception) {
                // На Android 12+ запуск из фона может быть запрещён. Тогда просто
                // работаем без службы, приложение не должно падать.
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } catch (e: Exception) {
            }
        }

        fun update(ctx: Context, text: String) {
            pendingText = text
            if (!alive) return
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, build(ctx, text))
            } catch (e: Exception) {
            }
        }

        private fun build(ctx: Context, text: String): Notification {
            val open = PendingIntent.getActivity(
                ctx,
                0,
                Intent(ctx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val off = PendingIntent.getService(
                ctx,
                1,
                Intent(ctx, KeepAliveService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_zale)
                .setContentTitle(text)
                .setContentText("Локальная сеть работает в фоне")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(ctx, R.drawable.ic_stat_zale),
                        "Отключиться",
                        off
                    ).build()
                )
                .build()
        }
    }

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Состояние ZaleVPN",
            NotificationManager.IMPORTANCE_LOW
        )
        ch.description = "Постоянное уведомление о работе локальной сети"
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        nm.createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Net.stop()
            stopSelf()
            return Service.START_NOT_STICKY
        }
        alive = true
        startForeground(NOTIF_ID, build(this, pendingText ?: "ZaleVPN: подключение…"))
        acquireWifiLock()
        return Service.START_NOT_STICKY
    }

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ZaleVPN:wifi")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (e: Exception) {
            wifiLock = null
        }
    }

    override fun onDestroy() {
        alive = false
        pendingText = null
        try {
            wifiLock?.release()
        } catch (e: Exception) {
        }
        wifiLock = null
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
