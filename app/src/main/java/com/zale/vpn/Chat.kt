package com.zale.vpn

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

data class ChatMsg(val name: String, val text: String, val mine: Boolean, val time: Long)

/** Чат идёт напрямую между участниками по UDP внутри виртуальной сети (сервер не нужен). */
object Chat {
    const val PORT = 4747

    val history = CopyOnWriteArrayList<ChatMsg>()
    var onMessage: (ChatMsg) -> Unit = {}
    @Volatile var nick = "Игрок"
    @Volatile var peers: List<String> = emptyList()

    private var sock: DatagramSocket? = null

    @Synchronized
    fun start() {
        if (sock != null) return
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(PORT))
            sock = s
            Thread {
                val buf = ByteArray(4096)
                while (!s.isClosed) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val j = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                        val m = ChatMsg(j.getString("n"), j.getString("t"), false, System.currentTimeMillis())
                        history.add(m)
                        onMessage(m)
                    } catch (e: Exception) {
                        if (s.isClosed) break
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            sock = null
        }
    }

    fun send(text: String) {
        val m = ChatMsg(nick, text, true, System.currentTimeMillis())
        history.add(m)
        onMessage(m)
        val data = JSONObject().put("n", nick).put("t", text).toString().toByteArray(Charsets.UTF_8)
        val targets = peers
        Thread {
            val s = sock
            if (s != null) {
                for (ip in targets) {
                    try {
                        s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), PORT))
                    } catch (e: Exception) {
                    }
                }
            }
        }.start()
    }

    @Synchronized
    fun stop() {
        try { sock?.close() } catch (e: Exception) {}
        sock = null
        peers = emptyList()
    }
}
