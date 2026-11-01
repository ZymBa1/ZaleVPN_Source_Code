package com.zale.vpn

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class ChatMsg(
    val id: String,
    val name: String,
    var text: String,
    val mine: Boolean,
    val time: Long,
    val kind: Int = 0,
    var path: String = "",
    var size: Long = 0L,
    var mime: String = "",
    var progress: Int = -1,
    var state: Int = 0
)

/**
 * Чат идёт напрямую между участниками: текст — по UDP внутри виртуальной сети,
 * фото и файлы — по TCP (UDP только сообщает о новом файле).
 */
object Chat {
    const val PORT = 4747

    const val KIND_TEXT = 0
    const val KIND_IMAGE = 1
    const val KIND_FILE = 2

    const val STATE_DONE = 0
    const val STATE_ACTIVE = 1
    const val STATE_FAILED = 2

    val history = CopyOnWriteArrayList<ChatMsg>()
    @Volatile var onMessage: (ChatMsg) -> Unit = {}
    @Volatile var onUpdate: (ChatMsg) -> Unit = {}
    @Volatile var nick = "Игрок"
    @Volatile var peers: List<String> = emptyList()
    @Volatile var app: Context? = null

    @Volatile private var sock: DatagramSocket? = null
    private val servers = CopyOnWriteArrayList<ServerSocket>()

    @Synchronized
    fun start() {
        if (sock != null) return
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(PORT))
            sock = s
            Thread {
                val buf = ByteArray(8192)
                while (!s.isClosed) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val j = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                        if (j.optInt("f", 0) == 1) onAnnounce(j, p.address) else onText(j)
                    } catch (e: Exception) {
                        if (s.isClosed) break
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            sock = null
        }
    }

    private fun onText(j: JSONObject) {
        val m = ChatMsg(
            UUID.randomUUID().toString(),
            j.optString("n", "Друг"),
            j.optString("t", ""),
            false,
            System.currentTimeMillis()
        )
        history.add(m)
        onMessage(m)
    }

    fun send(text: String) {
        val m = ChatMsg(UUID.randomUUID().toString(), nick, text, true, System.currentTimeMillis())
        history.add(m)
        onMessage(m)
        val data = JSONObject().put("n", nick).put("t", text).toString().toByteArray(Charsets.UTF_8)
        broadcast(data)
    }

    /** Отправка фото или файла: копируем к себе, сообщаем пирам и раздаём по TCP. */
    fun sendFile(uri: Uri) {
        val ctx = app ?: return
        val cr = ctx.contentResolver
        val name = queryName(ctx, uri)
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val size = querySize(ctx, uri)
        val kind = if (isImage(mime, name)) KIND_IMAGE else KIND_FILE
        val m = ChatMsg(
            UUID.randomUUID().toString(),
            nick,
            name,
            true,
            System.currentTimeMillis(),
            kind,
            size = size,
            mime = mime,
            progress = 0,
            state = STATE_ACTIVE
        )
        history.add(m)
        onMessage(m)

        var part: File? = null
        Thread {
            try {
                val dir = chatDir() ?: throw Exception("Нет доступа к папке чата")
                val dst = uniqueFile(dir, safeName(name))
                part = dst
                cr.openInputStream(uri).use { input ->
                    if (input == null) throw Exception("Не удалось прочитать файл")
                    FileOutputStream(dst).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val p = if (size > 0) ((done * 100) / size).toInt().coerceIn(0, 99) else 0
                            if (p != last) {
                                last = p
                                m.progress = p
                                onUpdate(m)
                            }
                        }
                    }
                }
                m.path = dst.absolutePath
                m.size = dst.length()
                m.progress = 100
                m.state = STATE_DONE
                onUpdate(m)
                share(m, dst)
            } catch (e: Exception) {
                try { part?.delete() } catch (e2: Exception) {}
                m.state = STATE_FAILED
                m.progress = 0
                onUpdate(m)
            }
        }.start()
    }

    private fun onAnnounce(j: JSONObject, from: InetAddress) {
        val host = from.hostAddress ?: return
        val port = j.optInt("port", 0)
        if (port <= 0) return
        val id = j.optString("id", UUID.randomUUID().toString())
        if (history.any { it.id == id }) return
        val mime = j.optString("mime", "application/octet-stream")
        val name = j.optString("name", "файл")
        val kind = if (isImage(mime, name)) KIND_IMAGE else KIND_FILE
        val m = ChatMsg(
            id,
            j.optString("n", "Друг"),
            name,
            false,
            System.currentTimeMillis(),
            kind,
            size = j.optLong("size", 0L),
            mime = mime,
            progress = 0,
            state = STATE_ACTIVE
        )
        history.add(m)
        onMessage(m)
        Thread { download(m, host, port) }.start()
    }

    private fun download(m: ChatMsg, host: String, port: Int) {
        var tmp: File? = null
        try {
            val dir = chatDir() ?: throw Exception("Нет доступа к папке чата")
            val part = File(dir, "recv_${m.id}.part")
            tmp = part
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 10000)
                s.soTimeout = 20000
                s.getInputStream().use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            val p = if (m.size > 0) ((total * 100) / m.size).toInt().coerceIn(0, 99) else 0
                            if (p != last) {
                                last = p
                                m.progress = p
                                onUpdate(m)
                            }
                        }
                    }
                }
            }
            if (m.size > 0 && part.length() != m.size) throw Exception("Размер не совпал")
            val dst = uniqueFile(dir, safeName(m.text))
            if (!part.renameTo(dst)) {
                part.copyTo(dst, overwrite = true)
                part.delete()
            }
            m.path = dst.absolutePath
            m.size = dst.length()
            m.progress = 100
            m.state = STATE_DONE
            onUpdate(m)
        } catch (e: Exception) {
            try { tmp?.delete() } catch (e2: Exception) {}
            m.state = STATE_FAILED
            m.progress = 0
            onUpdate(m)
        }
    }

    private fun share(m: ChatMsg, file: File) {
        try {
            val server = ServerSocket(0)
            servers.add(server)
            announce(m, server.localPort)
            Thread {
                try {
                    server.soTimeout = 30000
                    val deadline = System.currentTimeMillis() + 5 * 60 * 1000
                    while (System.currentTimeMillis() < deadline && !server.isClosed) {
                        val client = try {
                            server.accept()
                        } catch (e: SocketTimeoutException) {
                            break
                        }
                        Thread {
                            try {
                                client.getOutputStream().use { out ->
                                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                                }
                            } catch (e: Exception) {
                            } finally {
                                try { client.close() } catch (e: Exception) {}
                            }
                        }.start()
                    }
                } finally {
                    servers.remove(server)
                    try { server.close() } catch (e: Exception) {}
                }
            }.start()
        } catch (e: Exception) {
            m.state = STATE_FAILED
            onUpdate(m)
        }
    }

    private fun announce(m: ChatMsg, port: Int) {
        val data = JSONObject()
            .put("f", 1)
            .put("id", m.id)
            .put("n", nick)
            .put("name", m.text)
            .put("size", m.size)
            .put("mime", m.mime)
            .put("port", port)
            .toString()
            .toByteArray(Charsets.UTF_8)
        broadcast(data)
        // UDP не гарантирует доставку — повторяем анонс один раз.
        Thread {
            try { Thread.sleep(600) } catch (e: Exception) {}
            broadcast(data)
        }.start()
    }

    private fun broadcast(data: ByteArray) {
        val targets = peers
        Thread {
            val s = sock ?: return@Thread
            for (ip in targets) {
                try {
                    s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), PORT))
                } catch (e: Exception) {
                }
            }
        }.start()
    }

    @Synchronized
    fun stop() {
        try { sock?.close() } catch (e: Exception) {}
        sock = null
        peers = emptyList()
        for (s in servers) {
            try { s.close() } catch (e: Exception) {}
        }
        servers.clear()
    }

    // ---------- файлы ----------

    private fun isImage(mime: String, name: String): Boolean {
        if (mime.startsWith("image/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "gif" ||
            ext == "webp" || ext == "bmp" || ext == "heic" || ext == "heif"
    }

    private fun chatDir(): File? {
        val ctx = app ?: return null
        return File(ctx.filesDir, "chat").apply { mkdirs() }
    }

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().take(80)
        return cleaned.ifBlank { "file" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base($i)$ext")
            i++
        }
        return f
    }

    private fun queryName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (n != null && n.isNotBlank()) return n
                }
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun querySize(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (e: Exception) {
        }
        return 0L
    }
}
