package info.eworldq8.soor

import info.eworldq8.soor.engine.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Runs the Kotlin engine against the SAME tests/vectors.json the JS and Swift
// engines use. A finding that differs here from the shared expectation fails the
// build, which is how Soor guarantees a finding is identical on every platform.

class SoorEngineTest {

    private fun repoFile(rel: String): String {
        // tests run with the module dir as working dir, so walk up to the repo root
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val f = File(dir, rel)
            if (f.exists()) return f.readText()
            dir = dir.parentFile ?: dir
        }
        throw IllegalStateException("could not find $rel from ${System.getProperty("user.dir")}")
    }

    private val knowledge: Knowledge by lazy {
        Knowledge.parse(repoFile("docs/data/services.json"), repoFile("docs/data/cameras.json"))
    }

    private fun obsFrom(o: JSONObject) = Observation(
        host = o.getString("host"),
        port = o.getInt("port"),
        proto = o.optString("proto", "tcp"),
        httpServer = o.optString("httpServer").ifEmpty { null },
        httpTitle = if (o.has("httpTitle")) o.optString("httpTitle") else null,
        rtspServer = o.optString("rtspServer").ifEmpty { null },
        banner = o.optString("banner").ifEmpty { null },
        raw = o.optString("raw").ifEmpty { null },
        noAuth = o.optBoolean("noAuth", false),
        internetExposed = o.optBoolean("internetExposed", false),
        deviceName = o.optString("deviceName").ifEmpty { null },
        isNew = o.optBoolean("isNew", false)
    )

    @Test
    fun knowledgeBaseLoads() {
        assertTrue("services did not load", knowledge.services.isNotEmpty())
        assertTrue("cameras did not load", knowledge.cameras.isNotEmpty())
    }

    @Test
    fun sharedVectors() {
        val cases = JSONObject(repoFile("tests/vectors.json")).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val obs = obsFrom(c.getJSONObject("observation"))
            val expect = c.getJSONObject("expect")

            val findings = SoorEngine.analyse(listOf(obs), knowledge.services, knowledge.cameras)
            val got = findings.firstOrNull { it.host == obs.host }
                ?: throw AssertionError("no finding for: $name")

            assertEquals("kind mismatch: $name", expect.getString("kind"), got.kind)
            assertEquals("severity mismatch: $name", expect.getString("severity"), got.severity.key)
            if (expect.has("service")) assertEquals("service mismatch: $name", expect.getString("service"), got.service)
            if (expect.has("vendor")) assertEquals("vendor mismatch: $name", expect.getString("vendor"), got.vendor)
            if (expect.has("exposed")) assertEquals("exposed mismatch: $name", expect.getBoolean("exposed"), got.exposed)
        }
    }

    @Test
    fun emptyScanProducesNothing() {
        assertTrue(SoorEngine.analyse(emptyList(), knowledge.services, knowledge.cameras).isEmpty())
    }

    @Test
    fun cameraOnSeveralPortsCollapsesToOne() {
        val a = Observation(host = "192.168.1.64", port = 554,
            rtspServer = "Hipcam RealServer/V1.0", noAuth = true, internetExposed = true)
        val b = Observation(host = "192.168.1.64", port = 80,
            httpServer = "App-webs", internetExposed = true)
        val f = SoorEngine.analyse(listOf(a, b), knowledge.services, knowledge.cameras)
            .filter { it.host == "192.168.1.64" && it.vendor == "hikvision" }
        assertEquals("camera on two ports should collapse to one finding", 1, f.size)
        assertEquals("exposed-camera-default", f[0].kind)
    }

    @Test
    fun firstBootVendorNotFlagged() {
        val axis = knowledge.cameras.firstOrNull { it.id == "axis" }
        assertEquals(false, SoorEngine.shipsWeakCredentials(axis))
        val foscam = knowledge.cameras.firstOrNull { it.id == "foscam" }
        assertEquals(true, SoorEngine.shipsWeakCredentials(foscam))
    }

    @Test
    fun outputIsDeterministic() {
        val cases = JSONObject(repoFile("tests/vectors.json")).getJSONArray("cases")
        val obs = (0 until cases.length()).map { obsFrom(cases.getJSONObject(it).getJSONObject("observation")) }
        val first = SoorEngine.analyse(obs, knowledge.services, knowledge.cameras)
        val second = SoorEngine.analyse(obs, knowledge.services, knowledge.cameras)
        assertEquals(first, second)
    }
}
