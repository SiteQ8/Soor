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

    /** the name the person gave a device, kept by the device's identity so it survives an address change */
    fun label(id: String): String? = prefs.getString("label:$id", null)
    fun setLabel(id: String, name: String?) {
        val e = prefs.edit()
        if (name.isNullOrBlank()) e.remove("label:$id") else e.putString("label:$id", name.trim().take(40))
        e.apply()
    }

    fun firstSeen(id: String): Long? = prefs.getLong("first:$id", 0L).takeIf { it > 0L }
    fun noteSeen(id: String) { if (!prefs.contains("first:$id")) prefs.edit().putLong("first:$id", System.currentTimeMillis()).apply() }

    /** the findings of the last scan of a network, by signature, so the next scan can say what changed */
    fun lastFindings(network: String): Set<String>? = prefs.getStringSet("findings:$network", null)?.toSet()
    fun rememberFindings(network: String, sigs: Set<String>) { prefs.edit().putStringSet("findings:$network", sigs).apply() }

    companion object {
        fun signature(f: com.eworldq8.soor.engine.Finding) = "${f.kind}|${f.host}|${f.port}"
        fun networkKey(net: LocalNet) = "net:" + (net.gateway ?: net.prefix)

        /** a device's own UPnP identity when it has one, since addresses can change */
        fun id(ip: String, ssdp: SsdpDevice?): String =
            ssdp?.usn?.substringBefore("::")?.takeIf { it.startsWith("uuid:") } ?: "ip:$ip"
    }
}
