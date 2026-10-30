package com.zale.vpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class Mode { P2P, WG }
enum class Phase { IDLE, CONNECTING, CONNECTED, ERROR }

data class Slot(val index: Int, val ip: String, val config: String)

data class NetState(
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    val mode: Mode = Mode.P2P,
    val code: String = "",
    val myIp: String = "",
    val peers: List<String> = emptyList(),
    val slots: List<Slot> = emptyList()
)

object Net {
    const val PORT = 51820

    @Volatile private var running = false
    private var worker: Thread? = null
    private var backend: GoBackend? = null
    private var keys = KeyPair()
    private var lastSig: String? = null

    @Volatile var current = NetState()
        private set
    var onState: (NetState) -> Unit = {}

    private val tunnel = object : Tunnel {
        override fun getName() = "zale"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    @Synchronized
    fun init(ctx: Context) {
        if (backend == null) backend = GoBackend(ctx.applicationContext)
    }

    private fun set(s: NetState) {
        current = s
        onState(s)
    }

    // ---------- общий запуск ----------
    private fun begin(ctx: Context, body: () -> Unit) {
        if (running) return
        running = true
        lastSig = null
        keys = KeyPair()
        Chat.history.clear()
        init(ctx)
        worker = Thread {
            var failed = false
            try {
                body()
            } catch (e: InterruptedException) {
                // обычное отключение
            } catch (e: Exception) {
                failed = true
                set(NetState(Phase.ERROR, friendly(e), current.mode))
            } finally {
                Thread.interrupted()
                running = false
                Chat.stop()
                try { backend?.setState(tunnel, Tunnel.State.DOWN, null) } catch (e: Exception) {}
                if (!failed) set(NetState(mode = current.mode))
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
    }

    private fun friendly(e: Exception): String = when {
        e.message == "not found" -> "Сеть с таким кодом не найдена"
        e.message == "full" -> "Сеть заполнена"
        e is UnknownHostException -> "Нет интернета или неверный адрес Worker в Api.kt"
        e is SocketTimeoutException -> "Сервер не отвечает, попробуйте ещё раз"
        else -> e.message ?: "Неизвестная ошибка"
    }

    private fun stun(): String {
        for (attempt in 1..2) {
            val ep = try { Stun.mappedAddress(PORT) } catch (e: Exception) { null }
            if (ep != null) return ep
        }
        throw Exception("Не удалось определить адрес (STUN). Проверьте интернет.")
    }

    // ---------- управление туннелем ----------
    /** Поднять туннель (с повторами: служба VPN стартует не мгновенно). */
    private fun bringUp(cfg: Config) {
        var last: Exception? = null
        for (attempt in 1..4) {
            try {
                backend!!.setState(tunnel, Tunnel.State.UP, cfg)
                return
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                last = e
                Thread.sleep(1000)
            }
        }
        throw last ?: Exception("Не удалось поднять VPN")
    }

    /**
     * Перенастройка при появлении нового участника.
     * Раньше туннель перезапускался слишком быстро: служба VPN ещё не успевала
     * завершиться, и соединение «выкидывало». Теперь: выключаем, ждём, включаем с повторами.
     */
    private fun reconfigure(cfg: Config) {
        try {
            backend!!.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
        }
        Thread.sleep(1500)
        bringUp(cfg)
    }

    // ---------- режим P2P ----------
    fun startP2P(ctx: Context, joinCode: String?) = begin(ctx) {
        set(NetState(Phase.CONNECTING, "Определяю внешний адрес…", Mode.P2P))
        val ep = stun()
        val pub = keys.publicKey.toBase64()
        val body = JSONObject().put("pubkey", pub).put("endpoint", ep)
        set(NetState(Phase.CONNECTING, if (joinCode == null) "Создаю сеть…" else "Подключаюсь к сети…", Mode.P2P))
        val res = if (joinCode == null) Api.post("/create", body) else Api.post("/join/$joinCode", body)
        val myIp = res.getString("ip")
        val code = joinCode ?: res.getString("code")
        applyP2P(code, myIp, pub, res.optJSONArray("peers") ?: JSONArray())

        var fails = 0
        while (running) {
            Thread.sleep(4000)
            try {
                val peers = Api.get("/peers/$code").getJSONArray("peers")
                fails = 0
                applyP2P(code, myIp, pub, peers)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                if (e.message == "not found") throw Exception("Сеть закрыта (код истёк)")
                fails++
                if (fails >= 12) throw Exception("Потеряна связь с сервером")
                // кратковременные сбои сети больше не разрывают соединение
            }
        }
    }

    private fun applyP2P(code: String, myIp: String, pub: String, peers: JSONArray) {
        val others = (0 until peers.length()).map { peers.getJSONObject(it) }
            .filter { it.getString("pubkey") != pub }
        val sig = others.joinToString { it.getString("pubkey") + it.getString("endpoint") }
        if (sig != lastSig) {
            val iface = Interface.Builder()
                .setKeyPair(keys)
                .addAddress(InetNetwork.parse("$myIp/24"))
                .setListenPort(PORT)
                .build()
            val cb = Config.Builder().setInterface(iface)
            for (p in others) {
                cb.addPeer(
                    Peer.Builder()
                        .parsePublicKey(p.getString("pubkey"))
                        .parseEndpoint(p.getString("endpoint"))
                        .addAllowedIp(InetNetwork.parse(p.getString("ip") + "/32"))
                        .setPersistentKeepalive(15)
                        .build()
                )
            }
            val cfg = cb.build()
            if (lastSig == null) bringUp(cfg) else reconfigure(cfg)
            lastSig = sig
        }
        val ips = others.map { it.getString("ip") }
        Chat.peers = ips
        Chat.start()
        val ns = NetState(Phase.CONNECTED, "", Mode.P2P, code, myIp, ips)
        if (ns != current) set(ns)
    }

    // ---------- режим WireGuard (телефон = сервер, друзья через приложение WireGuard) ----------
    fun startWG(ctx: Context) = begin(ctx) {
        set(NetState(Phase.CONNECTING, "Определяю внешний адрес…", Mode.WG))
        val ep = stun()
        val serverPub = keys.publicKey.toBase64()
        val clients = (2..6).map { it to KeyPair() }

        val iface = Interface.Builder()
            .setKeyPair(keys)
            .addAddress(InetNetwork.parse("10.60.0.1/24"))
            .setListenPort(PORT)
            .build()
        val cb = Config.Builder().setInterface(iface)
        for ((i, kp) in clients) {
            cb.addPeer(
                Peer.Builder()
                    .parsePublicKey(kp.publicKey.toBase64())
                    .addAllowedIp(InetNetwork.parse("10.60.0.$i/32"))
                    .build()
            )
        }
        set(NetState(Phase.CONNECTING, "Запускаю WireGuard…", Mode.WG))
        bringUp(cb.build())

        val slots = clients.mapIndexed { n, (i, kp) ->
            Slot(n + 1, "10.60.0.$i", clientConf(kp, "10.60.0.$i", serverPub, ep))
        }
        set(NetState(Phase.CONNECTED, "", Mode.WG, "", "10.60.0.1", emptyList(), slots))
        while (running) Thread.sleep(1000)
    }

    private fun clientConf(kp: KeyPair, ip: String, serverPub: String, ep: String): String = """
[Interface]
PrivateKey = ${kp.privateKey.toBase64()}
Address = $ip/32

[Peer]
PublicKey = $serverPub
Endpoint = $ep
AllowedIPs = 10.60.0.0/24
PersistentKeepalive = 25
""".trimIndent()
}
