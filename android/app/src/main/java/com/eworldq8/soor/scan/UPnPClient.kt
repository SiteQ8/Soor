package com.eworldq8.soor.scan

import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

// Reads the router's UPnP Internet Gateway Device port-forward table, so Soor can
// tell a device forwarded to the internet from a safe local one. This is the core
// of the exposure check: a device is at risk when its port is in this table.
//
// Two steps: SSDP multicast discovery to find the IGD control URL on the local
// network, then SOAP calls to GetGenericPortMappingEntry to walk the forwarded
// ports. Every request goes through LocalOnly.check, which matters most here:
// a device could answer discovery with an address on the internet, and the
// check refuses to follow it.

data class PortMapping(
    val externalPort: Int,
    val internalPort: Int,
    val internalClient: String,
    val proto: String
)

data class UPnPResult(
    val exposedPorts: Set<Int>,
    val upnpEnabled: Boolean,
    val mappings: List<PortMapping>
)

class UPnPClient {

    companion object {
        private const val SSDP_ADDR = LocalOnly.SSDP_GROUP
        private const val SSDP_PORT = 1900
        private const val DISCOVERY_TIMEOUT_MS = 3000
        private const val HTTP_TIMEOUT_MS = 4000
        private const val MAX_MAPPINGS = 60
        private val SERVICE_TYPES = listOf(
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1"
        )
    }

    /**
     * Returns the internal ports the router has forwarded to the internet. Empty
     * when UPnP is off or unreachable, which is itself a good sign.
     */
    fun exposedPorts(): UPnPResult {
        val location = discover() ?: return UPnPResult(emptySet(), false, emptyList())
        val (controlUrl, serviceType) = describe(location)
            ?: return UPnPResult(emptySet(), true, emptyList())
        val mappings = walkMappings(controlUrl, serviceType)
        return UPnPResult(mappings.map { it.internalPort }.toSet(), true, mappings)
    }

    // MARK: SSDP discovery

    private fun discover(): String? {
        val search = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n")
        }.toByteArray()

        return try {
            LocalOnly.check(SSDP_ADDR)
            DatagramSocket().use { sock ->
                sock.soTimeout = DISCOVERY_TIMEOUT_MS
                sock.send(DatagramPacket(search, search.size, InetAddress.getByName(SSDP_ADDR), SSDP_PORT))
                val buf = ByteArray(2048)
                val reply = DatagramPacket(buf, buf.size)
                sock.receive(reply)
                val text = String(reply.data, 0, reply.length, Charsets.ISO_8859_1)
                headerValue(text, "LOCATION")
            }
        } catch (e: Exception) {
            null
        }
    }

    // MARK: device description

    /** Fetches the device description and finds the control URL for a WAN service. */
    private fun describe(location: String): Pair<String, String>? {
        val xml = httpGet(location) ?: return null
        for (st in SERVICE_TYPES) {
            val path = controlUrlForService(xml, st) ?: continue
            val abs = try {
                URI(location).resolve(path).toString()
            } catch (e: Exception) {
                continue
            }
            return abs to st
        }
        return null
    }

    /** Pulls the controlURL that follows the given serviceType in the description. */
    private fun controlUrlForService(xml: String, serviceType: String): String? {
        val at = xml.indexOf(serviceType)
        if (at < 0) return null
        val after = xml.substring(at)
        val o = after.indexOf("<controlURL>")
        if (o < 0) return null
        val c = after.indexOf("</controlURL>", o)
        if (c < 0) return null
        return after.substring(o + 12, c).trim()
    }

    // MARK: port mappings

    private fun walkMappings(controlUrl: String, serviceType: String): List<PortMapping> {
        val out = mutableListOf<PortMapping>()
        for (index in 0 until MAX_MAPPINGS) {
            val m = getMapping(controlUrl, serviceType, index) ?: break
            out.add(m)
        }
        return out
    }

    private fun getMapping(controlUrl: String, serviceType: String, index: Int): PortMapping? {
        val body = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:GetGenericPortMappingEntry xmlns:u="$serviceType">
<NewPortMappingIndex>$index</NewPortMappingIndex>
</u:GetGenericPortMappingEntry></s:Body></s:Envelope>"""

        val headers = mapOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "SOAPAction" to "\"$serviceType#GetGenericPortMappingEntry\""
        )
        val xml = httpPost(controlUrl, body, headers) ?: return null
        val ext = tag(xml, "NewExternalPort")?.toIntOrNull()
        val int = tag(xml, "NewInternalPort")?.toIntOrNull()
        if (ext == null || int == null) return null
        return PortMapping(ext, int, tag(xml, "NewInternalClient") ?: "", tag(xml, "NewProtocol") ?: "TCP")
    }

    // MARK: minimal HTTP over raw sockets, home network only (every request passes LocalOnly)

    private fun httpGet(url: String): String? = request("GET", url, null, emptyMap())

    private fun httpPost(url: String, body: String, headers: Map<String, String>): String? =
        request("POST", url, body, headers)

    private fun request(
        method: String, url: String, body: String?, headers: Map<String, String>
    ): String? {
        return try {
            val uri = URI(url)
            val host = uri.host ?: return null
            // the address came from a device's reply, so it is not trusted
            LocalOnly.check(host)
            val port = if (uri.port > 0) uri.port else 80
            val path = (uri.rawPath ?: "/").ifEmpty { "/" } +
                (uri.rawQuery?.let { "?$it" } ?: "")
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), HTTP_TIMEOUT_MS)
                s.soTimeout = HTTP_TIMEOUT_MS
                val out = StringBuilder()
                out.append("$method $path HTTP/1.1\r\n")
                out.append("Host: $host:$port\r\n")
                out.append("Connection: close\r\n")
                headers.forEach { (k, v) -> out.append("$k: $v\r\n") }
                val payload = body?.toByteArray(Charsets.UTF_8)
                if (payload != null) out.append("Content-Length: ${payload.size}\r\n")
                out.append("\r\n")
                s.getOutputStream().write(out.toString().toByteArray(Charsets.ISO_8859_1))
                if (payload != null) s.getOutputStream().write(payload)
                s.getOutputStream().flush()

                val raw = BufferedInputStream(s.getInputStream()).readBytes()
                val text = String(raw, Charsets.UTF_8)
                val split = text.indexOf("\r\n\r\n")
                if (split >= 0) text.substring(split + 4) else text
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun headerValue(text: String, name: String): String? {
        for (line in text.split("\r\n")) {
            if (line.uppercase().startsWith("${name.uppercase()}:")) {
                return line.substringAfter(":").trim().ifEmpty { null }
            }
        }
        return null
    }

    private fun tag(xml: String, name: String): String? {
        val o = xml.indexOf("<$name>")
        if (o < 0) return null
        val c = xml.indexOf("</$name>", o)
        if (c < 0) return null
        return xml.substring(o + name.length + 2, c).trim()
    }
}
