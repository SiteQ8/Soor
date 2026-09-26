package com.eworldq8.soor

import com.eworldq8.soor.scan.LocalOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

// Proves the rule that keeps Soor inside the home network, and checks that the
// scanner never opens a socket without asking the rule first. The second part
// reads the source itself, so a future edit cannot quietly bypass the rule.

class LocalOnlyTest {

    @Test
    fun homeNetworkAddressesAreAllowed() {
        listOf(
            "192.168.1.1", "192.168.0.254", "10.0.0.5", "10.255.255.255",
            "172.16.0.1", "172.31.255.255", "169.254.10.20", LocalOnly.SSDP_GROUP
        ).forEach { assertTrue("should allow $it", LocalOnly.isAllowed(it)) }
    }

    @Test
    fun everythingElseIsRefused() {
        listOf(
            "8.8.8.8", "1.1.1.1", "142.250.72.14",          // the internet
            "172.15.255.255", "172.32.0.1",                 // just outside 172.16/12
            "100.64.0.1",                                   // carrier NAT, not a home network
            "127.0.0.1",                                    // not a device on the network
            "239.255.255.251", "224.0.0.1",                 // other multicast groups
            "example.com", "router.local", "soor.3li.info", // hostnames would need DNS
            "192.168.1", "192.168.1.1.1", "256.1.1.1",      // malformed
            "192.168.1.01x", "", " ", "::1", "fe80::1"
        ).forEach { assertFalse("should refuse $it", LocalOnly.isAllowed(it)) }
        assertFalse(LocalOnly.isAllowed(null))
    }

    @Test
    fun checkThrowsBeforeAnyConnection() {
        try {
            LocalOnly.check("8.8.8.8")
            fail("check must throw for an internet address")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("8.8.8.8"))
        }
        LocalOnly.check("192.168.1.1") // must not throw
    }

    private fun scanSources(): List<File> {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val scan = File(dir, "src/main/java/com/eworldq8/soor/scan")
            if (scan.isDirectory) return scan.listFiles { f -> f.extension == "kt" }!!.toList()
            val nested = File(dir, "android/app/src/main/java/com/eworldq8/soor/scan")
            if (nested.isDirectory) return nested.listFiles { f -> f.extension == "kt" }!!.toList()
            dir = dir.parentFile ?: dir
        }
        throw IllegalStateException("scanner sources not found")
    }

    @Test
    fun everySocketPassesTheRuleFirst() {
        val opens = listOf(".connect(InetSocketAddress(", ".send(DatagramPacket(")
        var found = 0
        for (file in scanSources()) {
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                if (opens.any { line.contains(it) }) {
                    found++
                    val window = lines.subList(maxOf(0, i - 6), i).joinToString("\n")
                    assertTrue(
                        "${file.name}:${i + 1} opens a connection without LocalOnly.check before it",
                        window.contains("LocalOnly.check(")
                    )
                }
            }
        }
        assertEquals("expected the seven places the scanner connects", 7, found)
    }

    @Test
    fun noOtherWayOut() {
        // no HTTP stacks, no URL fetching, no hostnames resolved: only the
        // guarded raw sockets above
        val forbidden = listOf("HttpURLConnection", "openConnection(", "URL(", "OkHttp", "Retrofit", "WebView")
        for (file in scanSources()) {
            val text = file.readText()
            forbidden.forEach { f ->
                assertFalse("${file.name} uses $f, which would bypass LocalOnly", text.contains(f))
            }
        }
    }
}
