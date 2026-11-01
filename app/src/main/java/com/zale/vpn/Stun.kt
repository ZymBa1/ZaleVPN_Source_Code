package com.zale.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

import com.zale.vpn.api.Urls

object Stun {
    /** Возвращает внешний "ip:port" для UDP-порта localPort. Вызывать ДО запуска VPN. */
    fun mappedAddress(localPort: Int): String? {
        val sock = DatagramSocket(null)
        try {
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(localPort))
            sock.soTimeout = 3000
            val req = ByteArray(20)
            req[1] = 0x01
            req[4] = 0x21; req[5] = 0x12; req[6] = 0xA4.toByte(); req[7] = 0x42
            val tx = ByteArray(12); SecureRandom().nextBytes(tx)
            System.arraycopy(tx, 0, req, 8, 12)
            val server = InetAddress.getAllByName(Urls.STUN_HOST).first { it is Inet4Address }
            sock.send(DatagramPacket(req, 20, server, Urls.STUN_PORT))
            val buf = ByteArray(512)
            val p = DatagramPacket(buf, buf.size)
            sock.receive(p)
            var i = 20
            while (i + 4 <= p.length) {
                val type = ((buf[i].toInt() and 0xff) shl 8) or (buf[i + 1].toInt() and 0xff)
                val len = ((buf[i + 2].toInt() and 0xff) shl 8) or (buf[i + 3].toInt() and 0xff)
                if (type == 0x0020 && buf[i + 5].toInt() == 1) {
                    val port = (((buf[i + 6].toInt() and 0xff) shl 8) or (buf[i + 7].toInt() and 0xff)) xor 0x2112
                    val ip = (0..3).map { (buf[i + 8 + it].toInt() and 0xff) xor (buf[4 + it].toInt() and 0xff) }
                    return ip.joinToString(".") + ":" + port
                }
                i += 4 + ((len + 3) / 4) * 4
            }
            return null
        } finally {
            sock.close()
        }
    }
}
