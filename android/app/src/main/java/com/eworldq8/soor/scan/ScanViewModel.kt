package com.eworldq8.soor.scan

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eworldq8.soor.AppLanguage
import com.eworldq8.soor.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

// Ties discovery, the router's table, the sweep and the engine together, all on
// the device. The engine judges every observation; this only gathers them and
// keeps the screen informed while it does.

enum class ScanState { IDLE, SCANNING, DONE, NO_NETWORK }
enum class Phase { DISCOVER, PROBE, JUDGE }

data class DeviceInfo(
    val ip: String,
    val name: String?,
    val kind: DeviceKind,
    val ports: List<Int>,
    val isGateway: Boolean,
    val isNew: Boolean,
    val worst: Severity?,
)

/** One thing the scan just did, kept as data so the screen can word it in either language. */
data class ScanEvent(val kind: String, val ip: String? = null)

data class ScanUiState(
    val state: ScanState = ScanState.IDLE,
    val phase: Phase = Phase.DISCOVER,
    val progress: Float = 0f,
    val liveHosts: List<String> = emptyList(),
    val portsChecked: Int = 0,
    val findings: List<Finding> = emptyList(),
    val devices: List<DeviceInfo> = emptyList(),
    val firstScan: Boolean = false,
    val lastScan: Long? = null,
    val network: LocalNet? = null,
    val events: List<ScanEvent> = emptyList(),
    val startedAt: Long? = null,
    val durationMs: Long? = null,
    val partial: Boolean = false,
    val stopping: Boolean = false,
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(ScanUiState())
    val ui: StateFlow<ScanUiState> = _ui.asStateFlow()

    private val _lang = MutableStateFlow(AppLanguage.current(app))
    val lang: StateFlow<Lang> = _lang.asStateFlow()

    private val known = KnownDevices(app)

    val knowledge: Knowledge by lazy {
        val ctx = getApplication<Application>()
        Knowledge.parse(
            ctx.assets.open("services.json").bufferedReader().use { it.readText() },
            ctx.assets.open("cameras.json").bufferedReader().use { it.readText() }
        )
    }

    /** Reads the language again, after the phone's language or Soor's own changed. */
    fun syncLang(context: Context) {
        _lang.value = AppLanguage.current(context)
    }

    /** The language button: on Android 13 and later this changes Soor's language in the phone's settings. */
    fun switchLang(context: Context) {
        val next = if (_lang.value == Lang.AR) Lang.EN else Lang.AR
        AppLanguage.set(context, next)
        _lang.value = next
    }

    fun forgetDevices() = known.forget()

    private var scanner: NetworkScanner? = null

    /** Stops the running scan within about a second and keeps what it found so far. */
    fun stop() {
        if (_ui.value.state != ScanState.SCANNING) return
        _ui.update { it.copy(stopping = true) }
        event("stop")
        scanner?.cancel()
    }

    /** Back to the start, keeping the last results reachable from there. */
    fun home() { if (_ui.value.state == ScanState.DONE) _ui.update { it.copy(state = ScanState.IDLE) } }

    fun showResults() { if (_ui.value.lastScan != null) _ui.update { it.copy(state = ScanState.DONE) } }

    private fun event(kind: String, ip: String? = null) =
        _ui.update { it.copy(events = (it.events + ScanEvent(kind, ip)).takeLast(8)) }

    fun start() {
        if (_ui.value.state == ScanState.SCANNING) return
        val ctx = getApplication<Application>()
        val net = NetworkScanner.localNet(ctx)
        if (net == null) {
            _ui.value = _ui.value.copy(state = ScanState.NO_NETWORK)
            return
        }
        _ui.value = ScanUiState(state = ScanState.SCANNING, progress = 0.02f, network = net,
            startedAt = System.currentTimeMillis(), events = listOf(ScanEvent("start")))
        viewModelScope.launch {
            _ui.value = withContext(Dispatchers.IO) { runScan(ctx, net) }
        }
    }

    private fun addHost(ip: String) {
        val known = ip in _ui.value.liveHosts
        _ui.update { if (ip in it.liveHosts) it else it.copy(liveHosts = it.liveHosts + ip) }
        if (!known) event("found", ip)
    }

    private fun runScan(ctx: Context, net: LocalNet): ScanUiState {
        val scanner = NetworkScanner(ctx)
        this.scanner = scanner
        val upnp = UPnPClient()
        event("announce")
        val ssdp = upnp.discoverAll()
        ssdp.keys.forEach { ip -> if (ip !in _ui.value.liveHosts) { event("announced", ip) }; addHost(ip) }
        net.gateway?.let(::addHost)
        _ui.update { it.copy(progress = 0.08f) }

        event("router")
        val router = upnp.routerTable()
        event(if (router.upnpEnabled) "upnp-on" else "upnp-off")
        _ui.update { it.copy(progress = 0.12f) }

        scanner.onHostFound = ::addHost
        scanner.onProbing = { ip -> event("probe", ip) }
        scanner.onProgress = { p ->
            val discovering = p.phase == "discovering"
            val frac = p.done.toFloat() / max(1, p.total)
            _ui.update {
                it.copy(
                    phase = if (discovering) Phase.DISCOVER else Phase.PROBE,
                    progress = if (discovering) 0.12f + 0.43f * frac else 0.55f + 0.37f * frac,
                    portsChecked = p.portsChecked,
                )
            }
        }
        val hosts = scanner.scan(net, ssdp.keys + listOfNotNull(net.gateway), router.exposed)
        val stopped = scanner.cancelled
        this.scanner = null
        _ui.update { it.copy(phase = Phase.JUDGE, progress = 0.95f) }
        event("judge")

        // a device is new when this network has been scanned before and it was not on it
        val key = KnownDevices.networkKey(net)
        val seen = known.seen(key)
        val ids = mutableSetOf<String>()
        val newHosts = mutableSetOf<String>()
        val observations = mutableListOf<Observation>()
        for (h in hosts) {
            val id = KnownDevices.id(h.ip, ssdp[h.ip])
            ids += id
            val isNew = seen != null && id !in seen
            if (isNew) newHosts += h.ip
            if (h.observations.isEmpty()) {
                if (isNew) observations += Observation(host = h.ip, port = 0, isNew = true)
            } else {
                observations += h.observations.map { it.copy(isNew = isNew) }
            }
        }
        val gw = net.gateway
        if (router.upnpEnabled && gw != null && observations.none { it.host == gw && it.port == 1900 }) {
            observations += Observation(host = gw, port = 1900, banner = "UPnP/1.1 IGD rootDevice")
        }
        val findings = SoorEngine.analyse(observations, knowledge.services, knowledge.cameras)
        known.remember(key, ids)

        val devices = hosts.map { h ->
            val cameraVendor = h.observations.any { SoorEngine.matchCamera(it, knowledge.cameras) != null }
            val ports = h.openPorts + if (h.ip == gw && router.upnpEnabled && 1900 !in h.openPorts) listOf(1900) else emptyList()
            DeviceInfo(
                ip = h.ip,
                name = ssdp[h.ip]?.friendlyName,
                kind = DeviceKinds.guess(ports.toSet(), ssdp[h.ip], h.ip == gw, cameraVendor),
                ports = ports.sorted(),
                isGateway = h.ip == gw,
                isNew = h.ip in newHosts,
                worst = findings.filter { it.host == h.ip }.minByOrNull { it.severity.order }?.severity,
            )
        }.sortedWith(compareBy({ !it.isGateway }, { it.worst?.order ?: 9 }, { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }))

        val now = System.currentTimeMillis()
        return ScanUiState(
            state = ScanState.DONE, phase = Phase.JUDGE, progress = 1f,
            liveHosts = hosts.map { it.ip }, portsChecked = _ui.value.portsChecked,
            findings = findings, devices = devices, firstScan = seen == null,
            lastScan = now, network = net, startedAt = _ui.value.startedAt,
            durationMs = _ui.value.startedAt?.let { now - it }, partial = stopped,
        )
    }

    /** A plain-text report the person can share themselves. Nothing is sent by the app. */
    fun reportText(): String {
        val ar = _lang.value == Lang.AR
        val s = _ui.value
        val lines = mutableListOf<String>()
        lines += if (ar) "تقرير فحص سُور" else "Soor scan report"
        s.lastScan?.let { lines += SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(it)) }
        lines += Words.found(s.devices.size, ar)
        lines += ""
        if (s.findings.isEmpty()) lines += if (ar) "لا شيء يستحق القلق." else "Nothing to worry about."
        for (f in s.findings) {
            val r = SoorReport.render(f, knowledge, _lang.value)
            lines += "[${r.severityLabel}] ${r.title} | ${f.host}" + (if (f.port > 0) ":${f.port}" else "")
            if (r.detail.isNotEmpty()) lines += r.detail
            if (r.fix.isNotEmpty()) lines += (if (ar) "الحل: " else "Fix: ") + r.fix
            lines += ""
        }
        lines += if (ar) "الأجهزة:" else "Devices:"
        s.devices.forEach { d ->
            lines += "• " + (d.name ?: DeviceKinds.label(d.kind, ar)) + " | ${d.ip}" +
                (if (d.ports.isNotEmpty()) " | " + d.ports.joinToString(", ") else "")
        }
        lines += ""
        lines += if (ar) "فُحص محليًا على الجهاز بتطبيق سُور، ولم يُرسل شيء إلى أي مكان."
                 else "Scanned locally on the device by Soor. Nothing was sent anywhere."
        return lines.joinToString("\n")
    }
}
