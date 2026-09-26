package com.eworldq8.soor.scan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The names devices give themselves on the home network, so the list reads
 * "Ali's iPhone" and "DESKTOP-7K2" rather than bare addresses. Two sources:
 * Bonjour, which Apple devices, printers, TVs and speakers announce, read
 * through Android's own discovery service; and NetBIOS, which Windows PCs
 * answer on port 137. Both are asked, and neither is told anything.
 */
class Names(private val context: Context) {

    data class Found(val name: String, val service: String)

    companion object {
        private val TYPES = listOf(
            "_companion-link._tcp", "_rdlink._tcp", "_airplay._tcp", "_raop._tcp", "_googlecast._tcp",
            "_smb._tcp", "_ssh._tcp", "_workstation._tcp", "_ipp._tcp", "_printer._tcp",
            "_http._tcp", "_hap._tcp", "_spotify-connect._tcp", "_sonos._tcp",
        )

        /** a NetBIOS node-status query for "*", the question every Windows PC answers */
        private fun nbstatQuery(): ByteArray {
            val q = ByteArray(50)
            q[0] = 0x13; q[1] = 0x37; q[5] = 1; q[12] = 0x20
            q[13] = 'C'.code.toByte(); q[14] = 'K'.code.toByte()
            for (k in 15 until 45) q[k] = 'A'.code.toByte()
            q[47] = 0x21; q[49] = 1
            return q
        }

        fun clean(raw: String?): String =
            (raw ?: "").replace("\\032", " ").replace("\\.", ".").replace(Regex("\\s*\\(\\d+\\)$"), "").trim().take(40)
    }

    /** Names announced over Bonjour, by address, listened for a few seconds. */
    fun bonjour(seconds: Int = 4): Map<String, Found> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyMap()
        val out = ConcurrentHashMap<String, Found>()
        val queue = LinkedBlockingQueue<NsdServiceInfo>()
        val listeners = TYPES.mapNotNull { type ->
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(t: String?, e: Int) {}
                override fun onStopDiscoveryFailed(t: String?, e: Int) {}
                override fun onDiscoveryStarted(t: String?) {}
                override fun onDiscoveryStopped(t: String?) {}
                override fun onServiceFound(info: NsdServiceInfo) { queue.offer(info) }
                override fun onServiceLost(info: NsdServiceInfo) {}
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }.map { l }.getOrNull()
        }
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            val info = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
            val latch = CountDownLatch(1)
            val ok = runCatching {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo?, e: Int) { latch.countDown() }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val ip = (i.host as? Inet4Address)?.hostAddress
                        if (ip != null && LocalOnly.isAllowed(ip)) {
                            // a Chromecast keeps its real name in the "fn" record
                            val fn = i.attributes?.get("fn")?.let { String(it, Charsets.UTF_8) }
                            val name = clean(fn ?: i.serviceName)
                            if (name.isNotEmpty()) out.putIfAbsent(ip, Found(name, i.serviceType ?: ""))
                        }
                        latch.countDown()
                    }
                })
            }.isSuccess
            if (ok) latch.await(1500, TimeUnit.MILLISECONDS)
        }
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        return out
    }

    /** The computer name a Windows PC gives itself, or null when it is not one. */
    fun netbios(ip: String): String? {
        val q = nbstatQuery()
        return try {
            LocalOnly.check(ip)
            DatagramSocket().use { s ->
                s.soTimeout = 700
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
                val buf = ByteArray(1024)
                val r = DatagramPacket(buf, buf.size)
                s.receive(r)
                val d = r.data
                val n = r.length
                var p = 12
                p += if (n > p && (d[p].toInt() and 0xC0) == 0xC0) 2 else 34
                p += 8
                p += 2
                val count = if (p < n) d[p].toInt() and 0xff else 0
                p += 1
                var best: String? = null
                for (i in 0 until count) {
                    val at = p + i * 18
                    if (at + 18 > n) break
                    val suffix = d[at + 15].toInt() and 0xff
                    val group = (d[at + 16].toInt() and 0x80) != 0
                    val name = String(d, at, 15, Charsets.ISO_8859_1).trim()
                    if (!group && suffix == 0 && name.isNotEmpty() && name.all { it.code in 33..126 }) { best = name; break }
                }
                best
            }
        } catch (e: Exception) {
            null
        }
    }
}
