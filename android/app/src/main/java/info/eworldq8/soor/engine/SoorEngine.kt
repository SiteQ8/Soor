package info.eworldq8.soor.engine

// The Soor engine, ported to Kotlin from docs/engine/soor.js and kept in step
// with the Swift port. It takes what a scan already found on the local network
// and turns it into findings a person can act on. It reaches no network of its
// own and keeps no state. The shared vectors in tests/vectors.json must produce
// the same finding here as in the JS and Swift engines.

enum class Lang { AR, EN }

data class Bilingual(val ar: String, val en: String) {
    fun text(lang: Lang): String = if (lang == Lang.AR) ar else en
}

data class ServiceEntry(
    val id: String,
    val ports: List<Int>,
    val proto: String,
    val match: List<String>,
    val category: String,
    val severity: String,
    val name: Bilingual,
    val what: Bilingual,
    val fix: Bilingual
)

data class DefaultCredential(val user: String, val pass: String)

data class CameraFingerprints(
    val httpServer: List<String> = emptyList(),
    val httpTitle: List<String> = emptyList(),
    val rtspServer: List<String> = emptyList(),
    val banner: List<String> = emptyList()
)

data class CameraVendor(
    val id: String,
    val name: String,
    val fingerprints: CameraFingerprints,
    val defaultCredentials: List<DefaultCredential>,
    val firstBootPassword: Boolean,
    val notes: Bilingual
)

data class Observation(
    val host: String,
    val port: Int,
    val proto: String = "tcp",
    val httpServer: String? = null,
    val httpTitle: String? = null,
    val rtspServer: String? = null,
    val banner: String? = null,
    val raw: String? = null,
    val noAuth: Boolean = false,
    val internetExposed: Boolean = false,
    val deviceName: String? = null,
    val isNew: Boolean = false
)

enum class Severity(val key: String, val order: Int) {
    CRITICAL("critical", 0),
    HIGH("high", 1),
    MEDIUM("medium", 2),
    LOW("low", 3),
    INFO("info", 4);

    companion object {
        fun from(s: String): Severity =
            entries.firstOrNull { it.key == s } ?: INFO
    }
}

data class Finding(
    val kind: String,
    val severity: Severity,
    val host: String,
    val port: Int,
    val exposed: Boolean,
    val service: String? = null,
    val vendor: String? = null,
    val reason: String? = null
)

object SoorEngine {

    private fun anyMatch(haystack: String?, needles: List<String>?): Boolean {
        if (needles.isNullOrEmpty()) return false
        val h = (haystack ?: "").lowercase()
        return needles.any { it.isNotEmpty() && h.contains(it.lowercase()) }
    }

    private fun serviceBanner(o: Observation): String =
        listOfNotNull(o.httpServer, o.httpTitle, o.rtspServer, o.banner, o.raw)
            .joinToString(" \n ")

    fun matchService(o: Observation, services: List<ServiceEntry>): ServiceEntry? {
        val banner = serviceBanner(o)
        var byPort: ServiceEntry? = null
        for (svc in services) {
            if (anyMatch(banner, svc.match)) return svc   // banner beats port
            if (svc.ports.contains(o.port) && byPort == null) byPort = svc
        }
        return byPort
    }

    fun matchCamera(o: Observation, vendors: List<CameraVendor>): CameraVendor? {
        val banner = serviceBanner(o)
        for (v in vendors) {
            val fp = v.fingerprints
            if (anyMatch(o.httpServer, fp.httpServer) ||
                anyMatch(o.httpTitle, fp.httpTitle) ||
                anyMatch(o.rtspServer, fp.rtspServer) ||
                anyMatch(banner, fp.banner)
            ) return v
        }
        return null
    }

    fun shipsWeakCredentials(vendor: CameraVendor?): Boolean {
        if (vendor == null) return false
        return vendor.defaultCredentials.any { c ->
            val pass = c.pass.lowercase()
            pass.isEmpty() ||
                (!pass.contains("set on first boot") && !pass.contains("account based"))
        }
    }

    fun analyse(
        observations: List<Observation>,
        services: List<ServiceEntry>,
        cameras: List<CameraVendor>
    ): List<Finding> {
        var findings = mutableListOf<Finding>()

        for (o in observations) {
            val svc = matchService(o, services)
            val cam = matchCamera(o, cameras)
            val isCamera = cam != null || svc?.category == "camera"

            if (isCamera && cam != null && shipsWeakCredentials(cam)) {
                if (o.internetExposed) {
                    findings.add(
                        Finding("exposed-camera-default", Severity.CRITICAL, o.host, o.port, true,
                            svc?.id ?: "rtsp", cam.id, if (o.noAuth) "no-auth" else "known-default")
                    )
                } else {
                    findings.add(
                        Finding("default-credentials", Severity.HIGH, o.host, o.port, false,
                            svc?.id, cam.id, if (o.noAuth) "no-auth" else "known-default")
                    )
                }
                continue
            }

            if (svc != null && o.internetExposed && svc.severity != "low") {
                findings.add(Finding("exposed-service", Severity.CRITICAL, o.host, o.port, true, svc.id))
                continue
            }

            if (svc != null && o.noAuth && svc.category != "info") {
                findings.add(Finding("open-auth", Severity.HIGH, o.host, o.port, o.internetExposed, svc.id))
                continue
            }

            if (svc != null) {
                val sev = if (svc.severity == "low") Severity.INFO else Severity.from(svc.severity)
                findings.add(Finding("weak-service", sev, o.host, o.port, o.internetExposed, svc.id))
                continue
            }

            if (o.isNew) {
                findings.add(Finding("new-device", Severity.INFO, o.host, o.port, o.internetExposed))
            }
        }

        // One camera can answer on several ports, so keep only the worst finding
        // per host and vendor. A person should see one finding per camera.
        val cameraKinds = setOf("exposed-camera-default", "default-credentials")
        val best = mutableMapOf<String, Int>()
        val kept = mutableListOf<Finding>()
        for (f in findings) {
            if (f.kind !in cameraKinds || f.vendor == null) { kept.add(f); continue }
            val key = "${f.host}|${f.vendor}"
            val idx = best[key]
            if (idx == null) {
                best[key] = kept.size
                kept.add(f)
            } else if (f.severity.order < kept[idx].severity.order) {
                kept[idx] = f
            }
        }
        findings = kept

        // A device newly on the network is worth noting once per host, without
        // duplicating a louder finding.
        val hostsWithFinding = findings.map { it.host }.toMutableSet()
        for (o in observations) {
            if (o.isNew && o.host !in hostsWithFinding) {
                findings.add(Finding("new-device", Severity.INFO, o.host, o.port, o.internetExposed))
                hostsWithFinding.add(o.host)
            }
        }

        return findings.sortedWith(
            compareBy({ it.severity.order }, { it.host }, { it.port })
        )
    }

    fun summarise(findings: List<Finding>): Map<Severity, Int> {
        val counts = Severity.entries.associateWith { 0 }.toMutableMap()
        for (f in findings) counts[f.severity] = (counts[f.severity] ?: 0) + 1
        return counts
    }
}
