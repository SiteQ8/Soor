package com.eworldq8.soor.scan

import android.content.Context

/**
 * The devices Soor has seen on each network, so it can tell you when a new one
 * appears. Kept in the app's private storage on this phone only, never backed
 * up (allowBackup is off) and never sent anywhere, and erased from About.
 */
class KnownDevices(context: Context) {
    private val prefs = context.getSharedPreferences("soor_known_devices", Context.MODE_PRIVATE)

    /** null means this network has never been scanned, so nothing on it is "new" yet */
    fun seen(network: String): Set<String>? = prefs.getStringSet(network, null)?.toSet()

    fun remember(network: String, ids: Set<String>) {
        prefs.edit().putStringSet(network, (seen(network) ?: emptySet()) + ids).apply()
    }

    fun forget() { prefs.edit().clear().apply() }

    companion object {
        fun networkKey(net: LocalNet) = "net:" + (net.gateway ?: net.prefix)

        /** a device's own UPnP identity when it has one, since addresses can change */
        fun id(ip: String, ssdp: SsdpDevice?): String =
            ssdp?.usn?.substringBefore("::")?.takeIf { it.startsWith("uuid:") } ?: "ip:$ip"
    }
}
