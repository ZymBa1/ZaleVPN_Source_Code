package com.zale.vpn.api

import com.zale.vpn.BuildConfig

/**
 * Все адреса API, Worker'а и внешних сервисов — в одном месте.
 *
 * Рабочий адрес Worker'а лежит в папке SECRET/urls.properties (только в приватном
 * репозитории ZaleVPN). В публичный ZaleVPN_Source_Code папка SECRET не попадает,
 * поэтому там адреса нет. Без SECRET проект всё равно собирается, но подключение
 * покажет ошибку «Адрес Worker не задан».
 */
object Urls {
    /** Cloudflare Worker: координация P2P и WireGuard. Значение приходит из SECRET. */
    val WORKER: String = BuildConfig.WORKER_URL

    /** Базовый адрес API (без слэша в конце). */
    val BASE: String = WORKER

    /** Базовый адрес с проверкой: без него приложение не сможет подключиться. */
    fun base(): String {
        if (BASE.isBlank()) {
            throw Exception(
                "Адрес Worker не задан. Добавьте папку SECRET с файлом urls.properties " +
                    "(worker=https://ваш-worker.workers.dev) и пересоберите приложение."
            )
        }
        return BASE
    }

    /** Пути API Worker'а. */
    const val CREATE = "/create"
    fun join(code: String): String = "/join/$code"
    fun peers(code: String): String = "/peers/$code"

    /** STUN-сервер для определения внешнего IP:порта. */
    const val STUN_HOST = "stun.l.google.com"
    const val STUN_PORT = 19302

    /** Ссылки приложения. */
    const val TELEGRAM = "https://t.me/ZaleVPN"
    const val SUPPORT = "https://t.me/ZaleVPN_Support"
    const val GITHUB = "https://github.com/ZymBa1/ZaleVPN_Source_Code"
}
