package com.eworldq8.soor.scan

/**
 * The rule that keeps Soor inside your home.
 *
 * Android needs the INTERNET permission for any network connection at all,
 * even to a device in the next room, so the permission cannot be what keeps
 * Soor local. This rule does. Every connection Soor opens passes through
 * [check] first, and it refuses anything that is not a private home-network
 * address:
 *
 *   10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16   private networks (RFC 1918)
 *   169.254.0.0/16                              link-local
 *   239.255.255.250                             the UPnP discovery group, local only
 *
 * It accepts IPv4 literals only, never a hostname, so Soor never makes a DNS
 * lookup either. The code is open, and LocalOnlyTest proves the rule and checks
 * that every place Soor opens a socket calls it.
 */
object LocalOnly {

    const val SSDP_GROUP = "239.255.255.250"

    fun isAllowed(host: String?): Boolean {
        if (host == null) return false
        val h = host.trim()
        if (h == SSDP_GROUP) return true
        val parts = h.split(".")
        if (parts.size != 4) return false
        val o = IntArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || p.any { !it.isDigit() }) return false
            val v = p.toInt()
            if (v > 255) return false
            o[i] = v
        }
        return when {
            o[0] == 10 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 169 && o[1] == 254 -> true
            else -> false
        }
    }

    /** Throws before any connection to an address outside the home network. */
    fun check(host: String?) {
        if (!isAllowed(host)) {
            throw SecurityException("Soor only connects inside the home network, refused: $host")
        }
    }
}
