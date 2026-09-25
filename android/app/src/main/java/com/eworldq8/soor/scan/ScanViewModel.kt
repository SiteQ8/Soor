package com.eworldq8.soor.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eworldq8.soor.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Ties the scanner, the UPnP reader, and the engine together. Order: read the
// router's forwarded ports first so exposure is known, then sweep the network,
// then run the engine. Everything on the device.

enum class ScanState { IDLE, SCANNING, DONE, NO_NETWORK }

data class ScanUiState(
    val state: ScanState = ScanState.IDLE,
    val progressText: String = "",
    val progress: Float = 0f,
    val findings: List<Finding> = emptyList(),
    val deviceCount: Int = 0,
    val upnpEnabled: Boolean = false,
    val lastScan: Date? = null
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(ScanUiState())
    val ui: StateFlow<ScanUiState> = _ui.asStateFlow()

    private val _lang = MutableStateFlow(Lang.AR)
    val lang: StateFlow<Lang> = _lang.asStateFlow()

    val knowledge: Knowledge by lazy {
        val ctx = getApplication<Application>()
        Knowledge.parse(
            ctx.assets.open("services.json").bufferedReader().use { it.readText() },
            ctx.assets.open("cameras.json").bufferedReader().use { it.readText() }
        )
    }

    fun toggleLang() {
        _lang.value = if (_lang.value == Lang.AR) Lang.EN else Lang.AR
    }

    fun start() {
        if (_ui.value.state == ScanState.SCANNING) return
        val ctx = getApplication<Application>()
        if (NetworkScanner.localIPv4(ctx) == null) {
            _ui.value = _ui.value.copy(state = ScanState.NO_NETWORK)
            return
        }
        val ar = _lang.value == Lang.AR
        _ui.value = ScanUiState(
            state = ScanState.SCANNING,
            progressText = if (ar) "قراءة إعدادات الراوتر…" else "Reading router settings…"
        )

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val upnp = UPnPClient().exposedPorts()
                val scanner = NetworkScanner(ctx)
                scanner.onProgress = { p ->
                    val text = when (p.phase) {
                        "discovering" -> if (ar) "البحث عن الأجهزة… ${p.found} جهاز"
                                         else "Finding devices… ${p.found} found"
                        "probing" -> if (ar) "فحص المنافذ… ${p.scanned}/${p.total}"
                                     else "Probing ports… ${p.scanned}/${p.total}"
                        else -> ""
                    }
                    val frac = if (p.total > 0) p.scanned.toFloat() / p.total else 0f
                    _ui.value = _ui.value.copy(progressText = text, progress = frac)
                }
                val observations = scanner.scan(upnp.exposedPorts).toMutableList()

                // Represent an enabled UPnP as its own reviewable finding on the router.
                if (upnp.upnpEnabled) {
                    NetworkScanner.localIPv4(ctx)?.let { (_, prefix) ->
                        val gateway = "$prefix.1"
                        if (observations.none { it.host == gateway && it.port == 1900 }) {
                            observations.add(
                                Observation(host = gateway, port = 1900,
                                    banner = "UPnP/1.1 IGD rootDevice")
                            )
                        }
                    }
                }
                val findings = SoorEngine.analyse(observations, knowledge.services, knowledge.cameras)
                Triple(findings, observations.map { it.host }.toSet().size, upnp.upnpEnabled)
            }
            _ui.value = ScanUiState(
                state = ScanState.DONE,
                findings = result.first,
                deviceCount = result.second,
                upnpEnabled = result.third,
                lastScan = Date()
            )
        }
    }

    fun summary(): Map<Severity, Int> = SoorEngine.summarise(_ui.value.findings)

    /** A plain-text report the person can share themselves. Nothing is sent by us. */
    fun reportText(): String {
        val ar = _lang.value == Lang.AR
        val s = _ui.value
        val lines = mutableListOf<String>()
        lines.add(if (ar) "تقرير فحص سُور" else "Soor scan report")
        s.lastScan?.let {
            lines.add(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(it))
        }
        lines.add(if (ar) "عدد الأجهزة: ${s.deviceCount}" else "Devices: ${s.deviceCount}")
        lines.add("")
        if (s.findings.isEmpty()) {
            lines.add(if (ar) "لا مخاطر ظاهرة." else "No visible risks.")
        }
        for (f in s.findings) {
            val r = SoorReport.render(f, knowledge, _lang.value)
            lines.add("[${r.severityLabel}] ${r.title} | ${r.host}:${r.port}")
            if (r.detail.isNotEmpty()) lines.add(r.detail)
            if (r.fix.isNotEmpty()) lines.add((if (ar) "الحل: " else "Fix: ") + r.fix)
            lines.add("")
        }
        lines.add(
            if (ar) "فُحص محليًا على الجهاز بتطبيق سُور، ولم تُجمع أو تُرسل أي بيانات."
            else "Scanned locally on the device by Soor. No data collected or sent."
        )
        return lines.joinToString("\n")
    }
}
