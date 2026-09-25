package com.eworldq8.soor.scan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.eworldq8.soor.engine.Observation
import java.io.BufferedInputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// The scan itself, on the device and on the home network only. It finds the
// devices, knocks on their common ports, and reads what each service announces
// about itself. It never sends a password and never tries a login.
//
// Every socket below is opened only after LocalOnly.check approves the address,
// so the scanner cannot reach anything outside the home network.

data class LocalNet(val ip: String, val prefix: String, val gateway: String?)

data class HostScan(val ip: String, val openPorts: List<Int>, val observations: List<Observation>)

data class ScanProgress(val phase: String, val done: Int, val total: Int, val portsChecked: Int)

class NetworkScanner(private val context: Context) {

    enum class Knock { OPEN, CLOSED, SILENT }

    companion object {
        // the ports the knowledge base judges, plus a few that only tell what a device is
        val COMMON_PORTS = listOf(
            21, 22, 23, 80, 81, 443, 139, 445, 548, 554, 631, 1900, 2020, 2323, 3389, 5555,
            7000, 8000, 8008, 8009, 8080, 8081, 8443, 8554, 8899, 9000, 9100, 34567, 62078
        )
        // A device is present if it answers on any of these, even to refuse:
        // a refusal comes from a device that is there, silence from one that is not.
        val LIVENESS_PORTS = listOf(80, 443, 22, 445, 62078, 8080, 554, 5555, 8008, 9100, 7000, 139)

        private const val CONNECT_TIMEOUT_MS = 900
        private const val LIVENESS_TIMEOUT_MS = 400
        private const val READ_TIMEOUT_MS = 1500
        private val HTTP_PORTS = setOf(80, 81, 8080, 8081, 8000)
        private val TLS_PORTS = setOf(443, 8443)
        private val RTSP_PORTS = setOf(554, 8554)

        /** The phone's address, its /24 and the router, or null when not on Wi-Fi or ethernet. */
        fun localNet(context: Context): LocalNet? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            val onLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!onLan) return null
            val props = cm.getLinkProperties(network) ?: return null
            val addr = props.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?: return null
            val ip = addr.address.hostAddress ?: return null
            // a phone holding a public address is not on a home network; do not scan
            if (!LocalOnly.isAllowed(ip)) return null
            val parts = ip.split(".")
            if (parts.size != 4) return null
            val gateway = props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress?.takeIf { LocalOnly.isAllowed(it) }
            return LocalNet(ip, "${parts[0]}.${parts[1]}.${parts[2]}", gateway)
        }
    }

    @Volatile var onProgress: ((ScanProgress) -> Unit)? = null
    @Volatile var onHostFound: ((String) -> Unit)? = null
    private val portsChecked = AtomicInteger(0)

    /**
     * Sweeps the /24, then probes every device found. [seeds] are devices already
     * known to be present (they answered UPnP, or are the router); [exposed] holds
     * "ip:port" for every port the router forwards to the internet.
     */
    fun scan(net: LocalNet, seeds: Collection<String>, exposed: Set<String>): List<HostScan> {
        val hosts = discoverHosts(net, seeds)
        val pool = Executors.newFixedThreadPool(24)
        val results = Collections.synchronizedList(mutableListOf<HostScan>())
        val done = AtomicInteger(0)
        for (host in hosts) {
            pool.submit {
                results.add(probeHost(host, exposed))
                onProgress?.invoke(ScanProgress("probing", done.incrementAndGet(), hosts.size, portsChecked.get()))
            }
        }
        pool.shutdown()
        pool.awaitTermination(4, TimeUnit.MINUTES)
        return results.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    private fun discoverHosts(net: LocalNet, seeds: Collection<String>): List<String> {
        val live = Collections.synchronizedSet(LinkedHashSet<String>())
        fun found(ip: String) { if (ip != net.ip && live.add(ip)) onHostFound?.invoke(ip) }
        seeds.filter { it.startsWith(net.prefix + ".") }.forEach(::found)

        val pool = Executors.newFixedThreadPool(64)
        val checked = AtomicInteger(0)
        for (i in 1..254) {
            val ip = "${net.prefix}.$i"
            pool.submit {
                if (ip != net.ip && !live.contains(ip) && isHostUp(ip)) found(ip)
                onProgress?.invoke(ScanProgress("discovering", checked.incrementAndGet(), 254, portsChecked.get()))
            }
        }
        pool.shutdown()
        pool.awaitTermination(3, TimeUnit.MINUTES)
        return live.toList()
    }

    private fun isHostUp(ip: String): Boolean =
        LIVENESS_PORTS.any { knock(ip, it, LIVENESS_TIMEOUT_MS) != Knock.SILENT }

    private fun knock(ip: String, port: Int, timeoutMs: Int): Knock {
        portsChecked.incrementAndGet()
        return try {
            LocalOnly.check(ip)
            Socket().use { s -> s.connect(InetSocketAddress(ip, port), timeoutMs) }
            Knock.OPEN
        } catch (e: ConnectException) {
            val m = e.message ?: ""
            if (m.contains("ECONNREFUSED") || m.contains("refused", ignoreCase = true)) Knock.CLOSED else Knock.SILENT
        } catch (e: Exception) {
            Knock.SILENT
        }
    }

    private fun probeHost(ip: String, exposed: Set<String>): HostScan {
        val open = mutableListOf<Int>()
        val obs = mutableListOf<Observation>()
        for (port in COMMON_PORTS) {
            if (knock(ip, port, CONNECT_TIMEOUT_MS) != Knock.OPEN) continue
            open.add(port)
            val banner = if (port in TLS_PORTS) {
                when (tlsUntrusted(ip, port)) {
                    true -> "self-signed certificate"
                    false -> "trusted certificate"
                    null -> ""
                }
            } else readBanner(ip, port)
            obs.add(buildObservation(ip, port, banner, "$ip:$port" in exposed))
        }
        return HostScan(ip, open, obs)
    }

    private fun buildObservation(ip: String, port: Int, banner: String, exposed: Boolean): Observation {
        val noAuth = looksUnauthenticated(banner, port)
        return when (port) {
            in RTSP_PORTS -> Observation(
                host = ip, port = port, internetExposed = exposed, noAuth = noAuth,
                rtspServer = header(banner, "Server") ?: banner.take(200).ifEmpty { null }
            )
            in HTTP_PORTS -> Observation(
                host = ip, port = port, internetExposed = exposed, noAuth = noAuth,
                httpServer = header(banner, "Server"), httpTitle = title(banner),
                raw = banner.take(400).ifEmpty { null }
            )
            else -> Observation(
                host = ip, port = port, internetExposed = exposed, noAuth = noAuth,
                banner = banner.take(200).ifEmpty { null }
            )
        }
    }

    /**
     * Reads what the service volunteers. A web port gets a bare GET, a stream
     * port a DESCRIBE, which a camera answers with the stream itself when it
     * asks for no password. No credentials are ever sent.
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
                        "DESCRIBE rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\nUser-Agent: Soor\r\n\r\n".toByteArray())
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
     * Whether a TLS service's certificate fails to verify, read from the
     * handshake with the phone's own trust store. Nothing is sent after it.
     * true: no trusted authority vouches for it, almost always self-signed.
     * null: the handshake failed for another reason, so nothing is claimed.
     */
    private fun tlsUntrusted(ip: String, port: Int): Boolean? {
        val raw = Socket()
        return try {
            LocalOnly.check(ip)
            raw.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(raw, ip, port, true) as SSLSocket).use { it.startHandshake() }
            false
        } catch (e: SSLHandshakeException) {
            val certIssue = generateSequence<Throwable>(e) { it.cause }
                .any { it is CertPathValidatorException || it is CertificateException }
            if (certIssue) true else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { raw.close() }
        }
    }

    /**
     * Only a stream that hands over its description to an anonymous DESCRIBE is
     * called open. A web page answering 200 proves nothing: most admin panels
     * answer 200 with a login form, so the status code alone is never taken as
     * "no password".
     */
    private fun looksUnauthenticated(text: String, port: Int): Boolean {
        if (text.isEmpty() || port !in RTSP_PORTS) return false
        val t = text.lowercase()
        return t.startsWith("rtsp/1.0 200") && (t.contains("application/sdp") || t.contains("\nv=0"))
    }

    private fun header(text: String, name: String): String? {
        for (line in text.split("\n")) {
            val l = line.trim()
            if (l.lowercase().startsWith("${name.lowercase()}:")) return l.substring(name.length + 1).trim().ifEmpty { null }
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
