package com.eworldq8.soor.scan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import com.eworldq8.soor.engine.Severity
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * One check of the network itself, beyond any single device. Read from what
 * Android already knows about the connection and from the router's own
 * answer; nothing is sent to find these out. Judged here, in both languages,
 * because they are about the network rather than a host and port.
 */
data class NetCheck(
    val id: String,
    val severity: Severity?,
    val titleAr: String, val titleEn: String,
    val detailAr: String, val detailEn: String,
    val linkAr: String? = null, val linkEn: String? = null, val link: String? = null,
)

object NetworkChecks {
    private val KNOWN_RESOLVERS = setOf(
        "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
        "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15", "76.76.2.0", "76.76.10.0",
    )

    fun run(context: Context, externalIp: String?): List<NetCheck> {
        val out = mutableListOf<NetCheck>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val props = network?.let { cm.getLinkProperties(it) }
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        wifiSecurity(caps)?.let { out += it }
        props?.let { out += dns(it) }
        props?.let { privateDns(it)?.let { c -> out += c } }
        props?.let { ipv6(it)?.let { c -> out += c } }
        externalIp?.let { out += external(it) }
        return out
    }

    /** The encryption of the Wi-Fi itself, which Android reports on 12 and later. */
    private fun wifiSecurity(caps: NetworkCapabilities?): NetCheck? {
        if (Build.VERSION.SDK_INT < 31 || caps == null) return null
        val info = try { caps.transportInfo as? WifiInfo } catch (e: Exception) { null } ?: return null
        val type = try { info.currentSecurityType } catch (e: Exception) { return null }
        return when (type) {
            WifiInfo.SECURITY_TYPE_OPEN, WifiInfo.SECURITY_TYPE_WEP -> NetCheck("wifi", Severity.HIGH,
                "الواي فاي بلا تشفير يُعتد به", "Your Wi-Fi is not properly encrypted",
                "كل ما يمر في شبكتك يمكن لمن حولك قراءته، فادخل إعدادات الراوتر وحوّل التشفير إلى WPA2 أو WPA3 بكلمة مرور طويلة.",
                "Anything crossing your network can be read by whoever is in range. Open the router's settings and switch the encryption to WPA2 or WPA3 with a long password.")
            WifiInfo.SECURITY_TYPE_PSK -> NetCheck("wifi", null,
                "الواي فاي مشفّر بـ WPA2", "Wi-Fi encrypted with WPA2",
                "تشفير جيد، وWPA3 أفضل منه إن كان راوترك يدعمه.", "Good encryption, and WPA3 is better if your router supports it.")
            WifiInfo.SECURITY_TYPE_SAE -> NetCheck("wifi", null,
                "الواي فاي مشفّر بـ WPA3", "Wi-Fi encrypted with WPA3",
                "أحدث تشفير للواي فاي.", "The newest Wi-Fi encryption.")
            else -> null
        }
    }

    /** Who answers name lookups. A swapped DNS server is the commonest trick after a router break-in. */
    private fun dns(props: LinkProperties): NetCheck {
        val v4 = props.dnsServers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        val outside = v4.filter { !LocalOnly.isAllowed(it) && it !in KNOWN_RESOLVERS }
        val ar = v4.joinToString("، ")
        val en = v4.joinToString(", ")
        return when {
            v4.isEmpty() -> NetCheck("dns", null, "DNS", "DNS", "لم يُعلن الراوتر خادم DNS.", "The router announced no DNS server.")
            outside.isNotEmpty() -> NetCheck("dns", Severity.LOW,
                "خادم DNS من خارج شبكتك", "A DNS server from outside your network",
                "تُجيب عن أسماء المواقع خوادم منها ${outside.joinToString("، ")}، فإن كان من مزوّد الإنترنت فهذا طبيعي، وإن لم تعرفه فأعد DNS في الراوتر إلى التلقائي، لأن تبديله أشهر ما يفعله من يخترق راوترًا ليوجّهك إلى مواقع مزيفة.",
                "Name lookups go to ${outside.joinToString(", ")}. If that is your internet provider's server it is normal; if you do not recognise it, set the router's DNS back to automatic, because swapping it is the commonest thing a router intruder does to steer you to fake sites.")
            v4.all { LocalOnly.isAllowed(it) } -> NetCheck("dns", null,
                "أسماء المواقع يجيب عنها الراوتر", "Name lookups go through the router",
                "خادم DNS في شبكتك هو $ar، وهذا المعتاد.", "Your DNS server is $en, the usual arrangement.")
            else -> NetCheck("dns", null,
                "خوادم DNS معروفة", "Known DNS servers",
                "تُجيب عن أسماء المواقع $ar، وهي خوادم عامة معروفة.", "Name lookups go to $en, well-known public resolvers.")
        }
    }

    /** Android's own encrypted DNS, a privacy setting worth a nudge. */
    private fun privateDns(props: LinkProperties): NetCheck? {
        if (Build.VERSION.SDK_INT < 28) return null
        val name = props.privateDnsServerName
        return if (props.isPrivateDnsActive) NetCheck("pdns", null,
            "بحث الأسماء مشفّر", "Name lookups are encrypted",
            if (name != null) "«DNS الخاص» مفعّل عبر $name، فلا يرى أحد في الطريق أي المواقع تزور." else "«DNS الخاص» مفعّل تلقائيًا.",
            if (name != null) "Private DNS is on through $name, so nobody on the way sees which sites you look up." else "Private DNS is on automatically.")
        else NetCheck("pdns", Severity.INFO,
            "بحث الأسماء يمر مكشوفًا", "Name lookups travel in the clear",
            "يمكنك تفعيل «DNS الخاص» في إعدادات أندرويد، الشبكة والإنترنت، ليُشفَّر بحثك عن أسماء المواقع، وهذا إعداد في هاتفك لا في الراوتر.",
            "You can turn on Private DNS in Android's Network and internet settings so your name lookups are encrypted; it is a phone setting, not a router one.")
    }

    /** A public IPv6 address bypasses the router's port table entirely. */
    private fun ipv6(props: LinkProperties): NetCheck? {
        val global = props.linkAddresses.map { it.address }.filterIsInstance<Inet6Address>().any { a ->
            !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isLoopbackAddress && (a.address[0].toInt() and 0xFE) != 0xFC
        }
        if (!global) return null
        return NetCheck("ipv6", Severity.LOW,
            "شبكتك تعمل بعناوين IPv6 عامة", "Your network has public IPv6 addresses",
            "مع IPv6 قد يصل الإنترنت إلى الجهاز مباشرة دون المرور بجدول الراوتر الذي يفحصه سُور، فتأكد أن جدار حماية IPv6 مفعّل في الراوتر.",
            "With IPv6 the internet can reach a device directly, without the router's port table that Soor reads, so make sure the router's IPv6 firewall is on.")
    }

    /** The address the world sees, as the router itself reports it. */
    private fun external(ip: String): NetCheck {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        val cgnat = p.size == 4 && p[0] == 100 && p[1] in 64..127
        return when {
            cgnat -> NetCheck("wan", null,
                "بيتك خلف CGNAT", "Your home is behind CGNAT",
                "عنوان راوترك على الإنترنت ($ip) مشترك عند مزوّد الخدمة، فلا يصل أحد من الإنترنت إلى راوترك مباشرة، ومعه لا تعمل المنافذ الممرَّرة أصلًا.",
                "Your router's internet address ($ip) is shared at the provider, so nobody on the internet reaches your router directly, and forwarded ports do not work anyway.")
            LocalOnly.isAllowed(ip) -> NetCheck("wan", null,
                "راوترك خلف راوتر آخر", "Your router sits behind another router",
                "الراوتر يرى أمامه عنوانًا داخليًا ($ip)، فالإنترنت لا يصل إليه مباشرة، وجدول المنافذ الذي يهم هو جدول الراوتر الخارجي.",
                "The router sees a private address in front of it ($ip), so the internet does not reach it directly; the port table that matters is the outer router's.")
            else -> NetCheck("wan", Severity.INFO,
                "عنوان بيتك على الإنترنت", "Your home's address on the internet",
                "$ip هو العنوان الذي يراك به العالم، ومحركات الفحص مثل Shodan تفهرس ما يظهر عليه للإنترنت، فانظر ماذا تعرف عنه.",
                "$ip is the address the world sees you at, and scanning engines such as Shodan index whatever it shows to the internet, so see what they know about it.",
                "ماذا يرى Shodan عن عنوانك", "What Shodan sees at your address", "https://www.shodan.io/host/$ip")
        }
    }
}
