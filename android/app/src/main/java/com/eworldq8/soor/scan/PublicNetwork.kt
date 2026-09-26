package com.eworldq8.soor.scan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build

/**
 * Signs that the current network is a public one rather than a home: a
 * sign-in page, the mark of cafés, hotels and airports, or Wi-Fi with no
 * encryption. Read from what Android already reports about the connection,
 * so no new permission is needed. Only a warning: the person decides.
 */
object PublicNetwork {
    fun signs(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val caps = cm.getNetworkCapabilities(network) ?: return emptyList()
        val out = mutableListOf<String>()
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) out += "captive"
        if (Build.VERSION.SDK_INT >= 31) {
            val info = try { caps.transportInfo as? WifiInfo } catch (e: Exception) { null }
            val type = try { info?.currentSecurityType } catch (e: Exception) { null }
            if (type == WifiInfo.SECURITY_TYPE_OPEN || type == WifiInfo.SECURITY_TYPE_OWE ||
                type == WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 || type == WifiInfo.SECURITY_TYPE_PASSPOINT_R3) out += "open"
        }
        return out
    }
}
