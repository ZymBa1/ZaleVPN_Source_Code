package com.zale.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import com.zale.vpn.api.Urls
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
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

private data class Remote(val pub: String, val ip: String, val endpoint: String)

object Net {
    const val PORT = 51820

    @Volatile private var running = false
    private var worker: Thread? = null
    private var backend: GoBackend? = null
    private var keys = KeyPair()
    private var lastSig: String? = null
    private var gen = 0

    @Volatile private var appCtx: Context? = null

    @Volatile var current = NetState()
        private set
    var onState: (NetState) -> Unit = {}

    private val tunnel = object : Tunnel {
        override fun getName() = "zale"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    @Synchronized
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        Chat.app = app
        if (backend == null) {
            try {
                backend = GoBackend(app)
            } catch (e: Exception) {
                backend = null
            }
        }
    }

    private fun backendOrThrow(): GoBackend =
        backend ?: throw Exception("VPN-ядро недоступно. Перезапустите приложение.")

    private fun set(s: NetState) {
        current = s
        val ctx = appCtx
        if (ctx != null) {
            when (s.phase) {
                Phase.CONNECTING -> KeepAliveService.update(ctx, "ZaleVPN: подключение…")
                Phase.CONNECTED -> KeepAliveService.update(ctx, "ZaleVPN: подключено")
                Phase.IDLE, Phase.ERROR -> {}
            }
        }
        onState(s)
    }

    private fun active(myGen: Int) = running && myGen == gen

    // ---------- общий запуск ----------
    @Synchronized
    private fun begin(ctx: Context, body: (Int) -> Unit) {
        if (running) {
            // Даём предыдущему рабочему потоку корректно выключить туннель.
            val w = worker
            if (w != null) {
                try {
                    w.join(3000L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (running) return
        }
        val app = ctx.applicationContext
        appCtx = app
        init(app)
        running = true
        lastSig = null
        keys = KeyPair()
        Chat.history.clear()
        val myGen = ++gen
        KeepAliveService.start(app)
        val t = Thread {
            var failed = false
            try {
                body(myGen)
            } catch (e: InterruptedException) {
                // обычное отключение
                Thread.interrupted()
            } catch (e: Exception) {
                if (myGen == gen) {
                    failed = true
                    set(NetState(Phase.ERROR, friendly(e), current.mode))
                }
            } finally {
                Chat.stop()
                try {
                    backend?.setState(tunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                }
                KeepAliveService.stop(app)
                if (!failed || myGen != gen) set(NetState(mode = current.mode))
                running = false
                worker = null
            }
        }
        worker = t
        t.start()
    }

    fun stop() {
        gen++
        val ctx = appCtx
        if (ctx != null) KeepAliveService.stop(ctx)
        Chat.stop()
        set(NetState(mode = current.mode))
        worker?.interrupt()
    }

    private fun friendly(e: Exception): String = when {
        e.message == "not found" -> "Сеть с таким кодом не найдена"
        e.message == "full" -> "Сеть заполнена"
        e.message == "bad request" -> "Сервер отклонил данные. Проверьте приложение и Worker."
        e.message == "server error" -> "Ошибка на сервере. Попробуйте позже."
        e is UnknownHostException -> "Нет интернета или неверный адрес Worker в api/Urls.kt"
        e is SocketTimeoutException -> "Сервер не отвечает, попробуйте ещё раз"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Неизвестная ошибка"
    }

    private fun stun(): String {
        for (attempt in 1..2) {
            val ep = try {
                Stun.mappedAddress(PORT)
            } catch (e: Exception) {
                null
            }
            if (ep != null) return ep
        }
        throw Exception("Не удалось определить адрес (STUN). Проверьте интернет.")
    }

    /** Локальный адрес в Wi-Fi (ip:51820) или "" если активна не Wi-Fi. */
    private fun lanEndpoint(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            if (net == null) {
                ""
            } else {
                val caps = cm.getNetworkCapabilities(net)
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    ""
                } else {
                    val ip = cm.getLinkProperties(net)?.linkAddresses
                        ?.map { it.address }
                        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        ?.hostAddress
                    if (ip.isNullOrEmpty()) "" else "$ip:$PORT"
                }
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun publicIp(ep: String): String {
        val i = ep.lastIndexOf(':')
        return if (i > 0) ep.substring(0, i) else ep
    }

    /**
     * Если внешний IP участника совпадает с нашим (один роутер), берём его
     * локальный адрес: внешний адрес в таком случае часто не работает из-за NAT.
     */
    private fun endpointFor(p: JSONObject, myPublic: String): String {
        val ext = p.optString("endpoint", "")
        val lan = p.optString("lan", "")
        if (lan.isNotEmpty() && myPublic.isNotEmpty() && publicIp(ext) == myPublic) return lan
        return ext
    }

    // ---------- управление туннелем ----------
    /** Поднять туннель (с повторами: служба VPN стартует не мгновенно). */
    private fun bringUp(cfg: Config) {
        val be = backendOrThrow()
        var last: Exception? = null
        for (attempt in 1..4) {
            try {
                be.setState(tunnel, Tunnel.State.UP, cfg)
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
        val be = backendOrThrow()
        try {
            be.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
        }
        Thread.sleep(1500)
        bringUp(cfg)
    }

    // ---------- режим P2P ----------
    fun startP2P(ctx: Context, joinCode: String?) = begin(ctx) { myGen ->
        set(NetState(Phase.CONNECTING, "Определяю внешний адрес…", Mode.P2P))
        val ep = stun()
        val lan = lanEndpoint(ctx)
        val pub = keys.publicKey.toBase64()
        val body = JSONObject()
            .put("pubkey", pub)
            .put("endpoint", ep)
            .put("lan", lan)
        set(NetState(Phase.CONNECTING, if (joinCode == null) "Создаю сеть…" else "Подключаюсь к сети…", Mode.P2P))
        val res = if (joinCode == null) Api.post(Urls.CREATE, body) else Api.post(Urls.join(joinCode), body)
        val myIp = res.getString("ip")
        val code = joinCode ?: res.getString("code")
        val myPublic = publicIp(ep)
        applyP2P(code, myIp, pub, myPublic, res.optJSONArray("peers") ?: JSONArray())

        var fails = 0
        while (active(myGen)) {
            Thread.sleep(4000)
            if (!active(myGen)) break
            try {
                val peers = Api.get(Urls.peers(code)).getJSONArray("peers")
                fails = 0
                applyP2P(code, myIp, pub, myPublic, peers)
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

    private fun applyP2P(code: String, myIp: String, pub: String, myPublic: String, peers: JSONArray) {
        val remotes = ArrayList<Remote>()
        for (i in 0 until peers.length()) {
            val p = peers.optJSONObject(i) ?: continue
            val pk = p.optString("pubkey", "")
            val ip = p.optString("ip", "")
            if (pk.isEmpty() || pk == pub || ip.isEmpty()) continue
            val ep = endpointFor(p, myPublic)
            if (ep.isEmpty()) continue
            remotes.add(Remote(pk, ip, ep))
        }

        val sig = remotes.joinToString(";") { it.pub + "|" + it.ip + "|" + it.endpoint }
        if (sig != lastSig) {
            val iface = Interface.Builder()
                .setKeyPair(keys)
                .addAddress(InetNetwork.parse("$myIp/24"))
                .setListenPort(PORT)
                .build()
            val cb = Config.Builder().setInterface(iface)
            for (r in remotes) {
                cb.addPeer(
                    Peer.Builder()
                        .parsePublicKey(r.pub)
                        .parseEndpoint(r.endpoint)
                        .addAllowedIp(InetNetwork.parse(r.ip + "/32"))
                        .setPersistentKeepalive(15)
                        .build()
                )
            }
            val cfg = cb.build()
            if (lastSig == null) bringUp(cfg) else reconfigure(cfg)
            lastSig = sig
        }
        val ips = remotes.map { it.ip }
        Chat.peers = ips
        Chat.start()
        val ns = NetState(Phase.CONNECTED, "", Mode.P2P, code, myIp, ips)
        if (ns != current) set(ns)
    }

    // ---------- режим WireGuard (телефон = сервер, друзья через приложение WireGuard) ----------
    fun startWG(ctx: Context) = begin(ctx) { myGen ->
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
        while (active(myGen)) Thread.sleep(1000)
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
