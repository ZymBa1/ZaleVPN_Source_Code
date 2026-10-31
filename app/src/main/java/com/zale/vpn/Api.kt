package com.zale.vpn

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Api {
    // ВАЖНО: впишите адрес вашего Worker (без слэша в конце), например
    // "https://zalevpn.ваш-ник.workers.dev"
    const val BASE = "https://zalevpn.YOUR-NAME.workers.dev"

    private fun call(method: String, path: String, body: JSONObject?): JSONObject {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 10000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        if (code >= 400) {
            val msg = try { JSONObject(text).optString("error", "HTTP $code") } catch (e: Exception) { "HTTP $code" }
            throw Exception(msg)
        }
        return JSONObject(text)
    }

    fun post(path: String, body: JSONObject) = call("POST", path, body)
    fun get(path: String) = call("GET", path, null)
}
