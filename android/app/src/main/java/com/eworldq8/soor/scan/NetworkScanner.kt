package com.eworldq8.soor.scan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import com.eworldq8.soor.engine.Observation
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// The real scanner. It runs entirely on the device and only ever touches the
// local network the phone is on. It does three honest things:
//   1. Finds the phone's own IPv4 address and subnet, then sweeps that range for
//      hosts that answer a TCP connection on any common port.
//   2. For each live host, probes common service ports and reads the banner the
//      service volunteers. It never sends a password and never tries a login.
//   3. Takes the router's forwarded-port list from UPnP, so a device exposed to
//      the internet can be told apart from a safe local one.
// It exploits nothing. It knocks on doors and reads what is announced.
//
// Every socket below is opened only after LocalOnly.check approves the address,
// so the scanner cannot reach anything outside the home network.

data class ScanProgress(val phase: String, val scanned: Int, val total: Int, val found: Int)

class NetworkScanner(private val context: Context) {

    companion object {
        // Ports worth probing on a home network, lined up with the knowledge base.
        val COMMON_PORTS = listOf(
            21, 22, 23, 80, 81, 443, 139, 445, 554, 1900,
            2020, 2323, 3389, 5555, 8000, 8080, 8081, 8443,
            8554, 8899, 9000, 34567
        )
        // A quick liveness set: if a host answers any of these, it is up.
        val LIVENESS_PORTS = listOf(80, 443, 8080, 554, 22, 445, 5555)

        private const val CONNECT_TIMEOUT_MS = 1200
        private const val LIVENESS_TIMEOUT_MS = 700
        private const val READ_TIMEOUT_MS = 1500

        private val HTTP_PORTS = setOf(80, 81, 8080, 8081, 8000)
        private val TLS_PORTS = setOf(443, 8443)
        private val RTSP_PORTS = setOf(554, 8554)

        /** The phone's IPv4 address and its /24 prefix, or null when off Wi-Fi. */
        fun localIPv4(context: Context): Pair<String, String>? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            // Only scan when on Wi-Fi or ethernet, never over mobile data.
            val onLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!onLan) return null
            val props = cm.getLinkProperties(network) ?: return null
            val addr: LinkAddress = props.linkAddresses.firstOrNull {
                it.address is Inet4Address && !it.address.isLoopbackAddress
            } ?: return null
            val ip = addr.address.hostAddress ?: return null
            // a phone holding a public address is not on a home network; do not scan
            if (!LocalOnly.isAllowed(ip)) return null
            val parts = ip.split(".")
            if (parts.size != 4) return null
            return ip to "${parts[0]}.${parts[1]}.${parts[2]}"
        }
    }

    @Volatile var onProgress: ((ScanProgress) -> Unit)? = null

    /** Runs a full scan and returns observations for the engine. */
    fun scan(exposedPorts: Set<Int>): List<Observation> {
        val local = localIPv4(context) ?: return emptyList()
        val (selfIp, prefix) = local

        val hosts = discoverHosts(prefix, selfIp)
        val pool = Executors.newFixedThreadPool(24)
        val results = java.util.Collections.synchronizedList(mutableListOf<Observation>())
        val done = AtomicInteger(0)

        for (host in hosts) {
            pool.submit {
                val obs = probeHost(host, exposedPorts)
                results.addAll(obs)
                val n = done.incrementAndGet()
                onProgress?.invoke(ScanProgress("probing", n, hosts.size, hosts.size))
            }
        }
        pool.shutdown()
        pool.awaitTermination(4, TimeUnit.MINUTES)
        return results.toList()
    }

    // MARK: host discovery

    private fun discoverHosts(prefix: String, selfIp: String): List<String> {
        val pool = Executors.newFixedThreadPool(64)
        val live = java.util.Collections.synchronizedSet(mutableSetOf(selfIp))
        val checked = AtomicInteger(0)

        for (i in 1..254) {
            val ip = "$prefix.$i"
            pool.submit {
                if (ip != selfIp && isHostUp(ip)) live.add(ip)
                val n = checked.incrementAndGet()
                onProgress?.invoke(ScanProgress("discovering", n, 254, live.size))
            }
        }
        pool.shutdown()
        pool.awaitTermination(3, TimeUnit.MINUTES)
        return live.sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    private fun isHostUp(ip: String): Boolean =
        LIVENESS_PORTS.any { tryConnect(ip, it, LIVENESS_TIMEOUT_MS) }

    private fun tryConnect(ip: String, port: Int, timeoutMs: Int): Boolean =
        try {
            LocalOnly.check(ip)
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }

    // MARK: port probing

    private fun probeHost(ip: String, exposedPorts: Set<Int>): List<Observation> {
        val out = mutableListOf<Observation>()
        for (port in COMMON_PORTS) {
            if (!tryConnect(ip, port, CONNECT_TIMEOUT_MS)) continue
            val banner = readBanner(ip, port)
            out.add(buildObservation(ip, port, banner, exposedPorts.contains(port)))
        }
        return out
    }

    /** Route the banner into the field the engine expects for that port. */
    private fun buildObservation(
        ip: String, port: Int, banner: String, exposed: Boolean
    ): Observation {
        val noAuth = looksUnauthenticated(banner, port)
        return when {
            port in RTSP_PORTS -> Observation(
                host = ip, port = port, internetExposed = exposed, noAuth = noAuth,
                rtspServer = header(banner, "Server") ?: banner.take(200).ifEmpty { null }
            )
            port in HTTP_PORTS || port in TLS_PORTS -> Observation(
                host = ip, port = port, internetExposed = exposed, noAuth = noAuth,
                httpServer = header(banner, "Server"),
                httpTitle = title(banner),
                raw = banner.take(400).ifEmpty { null }
            )
            else -> Observation(
                host = ip, port = port, internetExposed = exposed, noAuth = noAuth,
                banner = banner.take(200).ifEmpty { null }
            )
        }
    }

    /**
     * Reads whatever the service volunteers. For HTTP-ish and RTSP ports it sends
     * a bare request so the server replies. No credentials are ever sent.
     */
    private fun readBanner(ip: String, port: Int): String =
        try {
            LocalOnly.check(ip)
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = READ_TIMEOUT_MS
                when (port) {
                    in HTTP_PORTS -> s.getOutputStream().write(
                        "GET / HTTP/1.0\r\nHost: $ip\r\nUser-Agent: Soor\r\n\r\n".toByteArray())
                    in RTSP_PORTS -> s.getOutputStream().write(
                        "OPTIONS rtsp://$ip RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
                }
                s.getOutputStream().flush()
                val buf = ByteArray(4096)
                val n = BufferedInputStream(s.getInputStream()).read(buf)
                if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else ""
            }
        } catch (e: Exception) {
            ""
        }

    /**
     * A service "answers with no password" when it returns content without a 401
     * and without an auth challenge. Read from the reply, never from a login try.
     */
    private fun looksUnauthenticated(text: String, port: Int): Boolean {
        if (text.isEmpty()) return false
        val t = text.lowercase()
        if (port in RTSP_PORTS) {
            return t.contains("rtsp/1.0 200") && !t.contains("www-authenticate")
        }
        if (t.startsWith("http/")) {
            val unauthorized = t.contains(" 401") || t.contains("www-authenticate")
            val ok = t.contains(" 200") || t.contains(" 302") || t.contains(" 301")
            return ok && !unauthorized
        }
        return !t.contains("password") && (port == 5555 || port == 445)
    }

    private fun header(text: String, name: String): String? {
        for (line in text.split("\n")) {
            val l = line.trim()
            if (l.lowercase().startsWith("${name.lowercase()}:")) {
                return l.substring(name.length + 1).trim().ifEmpty { null }
            }
        }
        return null
    }

    private fun title(text: String): String? {
        val lower = text.lowercase()
        val a = lower.indexOf("<title>")
        if (a < 0) return null
        val b = lower.indexOf("</title>", a)
        if (b < 0) return null
        return text.substring(a + 7, b).trim().ifEmpty { null }
    }
}
