package com.eworldq8.soor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import com.eworldq8.soor.engine.Knowledge
import com.eworldq8.soor.engine.Lang
import com.eworldq8.soor.engine.Observation
import com.eworldq8.soor.engine.SoorEngine
import com.eworldq8.soor.scan.DeviceInfo
import com.eworldq8.soor.scan.DeviceKind
import com.eworldq8.soor.scan.LocalNet
import com.eworldq8.soor.scan.Phase
import com.eworldq8.soor.scan.ScanEvent
import com.eworldq8.soor.scan.ScanState
import com.eworldq8.soor.scan.ScanUiState
import com.eworldq8.soor.ui.LocalStill
import com.eworldq8.soor.ui.Sheet
import com.eworldq8.soor.ui.SoorScreen
import com.eworldq8.soor.ui.SoorTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// Photographs the real screens, with the app's own fonts, on the build machine.
// The home shown is a sample, but every verdict on it comes from the real
// engine, so the pictures show exactly what the app would show for that home.
// Run:  ./gradlew :app:recordRoborazziDebug --tests com.eworldq8.soor.ScreenshotTest

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun repoFile(rel: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val f = File(dir, rel)
            if (f.exists()) return f.readText()
            dir = dir.parentFile ?: dir
        }
        throw IllegalStateException("could not find $rel")
    }

    private val knowledge by lazy { Knowledge.parse(repoFile("docs/data/services.json"), repoFile("docs/data/cameras.json")) }

    private val observations = listOf(
        Observation(host = "192.168.1.1", port = 80, httpServer = "Router Webserver", banner = "WWW-Authenticate: Basic realm"),
        Observation(host = "192.168.1.1", port = 1900, banner = "UPnP/1.1 IGD rootDevice"),
        Observation(host = "192.168.1.1", port = 443, banner = "self-signed certificate"),
        Observation(host = "192.168.1.64", port = 554, rtspServer = "Hipcam RealServer/V1.0", noAuth = true, internetExposed = true),
        Observation(host = "192.168.1.90", port = 5555),
        Observation(host = "192.168.1.30", port = 80, httpServer = "HP HTTP Server", httpTitle = "Printer", banner = "WWW-Authenticate: Basic realm"),
        Observation(host = "192.168.1.10", port = 22, banner = "SSH-2.0-OpenSSH_9.6"),
        Observation(host = "192.168.1.150", port = 0, isNew = true),
    )
    private val findings by lazy { SoorEngine.analyse(observations, knowledge.services, knowledge.cameras) }

    private fun dev(ip: String, name: String?, kind: DeviceKind, ports: List<Int>, gw: Boolean = false, new: Boolean = false) =
        DeviceInfo(ip, name, kind, ports, gw, new, findings.filter { it.host == ip }.minByOrNull { it.severity.order }?.severity)

    private val devices by lazy {
        listOf(
            dev("192.168.1.1", null, DeviceKind.ROUTER, listOf(80, 443, 1900), gw = true),
            dev("192.168.1.64", null, DeviceKind.CAMERA, listOf(554)),
            dev("192.168.1.90", "Living Room TV", DeviceKind.TV, listOf(5555, 8008, 8009)),
            dev("192.168.1.30", "Office Printer", DeviceKind.PRINTER, listOf(80, 631, 9100)),
            dev("192.168.1.10", "DESKTOP-7K2QA", DeviceKind.COMPUTER, listOf(22)),
            dev("192.168.1.42", "Ali's iPhone", DeviceKind.PHONE, listOf(62078)),
            dev("192.168.1.150", null, DeviceKind.UNKNOWN, emptyList(), new = true),
        )
    }
    private val net = LocalNet("192.168.1.42", "192.168.1", "192.168.1.1")
    private val results by lazy {
        ScanUiState(state = ScanState.DONE, phase = Phase.JUDGE, progress = 1f, liveHosts = devices.map { it.ip },
            portsChecked = 1834, findings = findings, devices = devices, lastScan = 1758880800000L, network = net,
            startedAt = 1758880752000L, durationMs = 48000L, fixedCount = 2,
            newFindings = setOf("exposed-camera-default|192.168.1.64|554"))
    }
    private val scanning = ScanUiState(state = ScanState.SCANNING, phase = Phase.PROBE, progress = 0.62f,
        liveHosts = listOf("192.168.1.1", "192.168.1.64", "192.168.1.90", "192.168.1.30", "192.168.1.10"), portsChecked = 1287,
        network = net, startedAt = System.currentTimeMillis() - 41000L,
        events = listOf(ScanEvent("start"), ScanEvent("announced", "192.168.1.90"), ScanEvent("upnp-on"),
            ScanEvent("found", "192.168.1.64"), ScanEvent("found", "192.168.1.30"), ScanEvent("found", "192.168.1.10"),
            ScanEvent("probe", "192.168.1.1"), ScanEvent("probe", "192.168.1.64")))

    private fun shot(name: String, ui: ScanUiState, lang: Lang = Lang.AR, sheet: Sheet? = null, scrollTo: Int? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalStill provides true) {
                SoorTheme { SoorScreen(ui, lang, knowledge, {}, {}, {}, {}, {}, sheet) }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        if (scrollTo != null) {
            compose.onNodeWithTag("results").performScrollToIndex(scrollTo)
            compose.mainClock.advanceTimeBy(600)
        }
        captureScreenRoboImage("build/shots/$name.png")
    }

    @Test fun home_ar() = shot("ar-1-home", ScanUiState())
    @Test fun home_last_ar() = shot("ar-8-home-last", results.copy(state = ScanState.IDLE))
    @Test fun scanning_ar() = shot("ar-2-scanning", scanning)
    @Test fun results_ar() = shot("ar-3-results", results)
    @Test fun devices_ar() = shot("ar-4-devices", results, scrollTo = 3 + findings.size)
    @Test fun finding_ar() = shot("ar-5-finding", results, sheet = Sheet.OfFinding(findings.first()))
    @Test fun device_ar() = shot("ar-6-device", results, sheet = Sheet.OfDevice(devices[1]))
    @Test fun about_ar() = shot("ar-7-about", ScanUiState(), sheet = Sheet.About)
    @Test fun home_en() = shot("en-1-home", ScanUiState(), Lang.EN)
    @Test fun scanning_en() = shot("en-2-scanning", scanning, Lang.EN)
    @Test fun results_en() = shot("en-3-results", results, Lang.EN)
    @Test fun finding_en() = shot("en-5-finding", results, Lang.EN, sheet = Sheet.OfFinding(findings.first()))
}
