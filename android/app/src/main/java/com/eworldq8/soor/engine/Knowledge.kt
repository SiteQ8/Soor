package com.eworldq8.soor.engine

import org.json.JSONArray
import org.json.JSONObject

// Loads the shared JSON knowledge base (the same docs/data files the site and the
// tests use, copied into the app's assets) and renders findings into bilingual
// text, mirroring docs/engine/report.js and the Swift port. Uses org.json, which
// Android ships, so the app carries no parsing dependency.

data class Knowledge(
    val services: List<ServiceEntry>,
    val cameras: List<CameraVendor>
) {
    fun service(id: String?): ServiceEntry? = id?.let { i -> services.firstOrNull { it.id == i } }
    fun camera(id: String?): CameraVendor? = id?.let { i -> cameras.firstOrNull { it.id == i } }

    companion object {
        fun parse(servicesJson: String, camerasJson: String): Knowledge =
            Knowledge(parseServices(servicesJson), parseCameras(camerasJson))

        private fun bilingual(o: JSONObject?): Bilingual =
            Bilingual(o?.optString("ar") ?: "", o?.optString("en") ?: "")

        private fun strings(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            return (0 until a.length()).map { a.optString(it) }
        }

        private fun ints(a: JSONArray?): List<Int> {
            if (a == null) return emptyList()
            return (0 until a.length()).map { a.optInt(it) }
        }

        fun parseServices(json: String): List<ServiceEntry> {
            val arr = JSONObject(json).optJSONArray("services") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ServiceEntry(
                    id = o.getString("id"),
                    ports = ints(o.optJSONArray("ports")),
                    proto = o.optString("proto", "tcp"),
                    match = strings(o.optJSONArray("match")),
                    category = o.optString("category"),
                    severity = o.optString("severity"),
                    name = bilingual(o.optJSONObject("name")),
                    what = bilingual(o.optJSONObject("what")),
                    fix = bilingual(o.optJSONObject("fix"))
                )
            }
        }

        fun parseCameras(json: String): List<CameraVendor> {
            val arr = JSONObject(json).optJSONArray("vendors") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val fp = o.optJSONObject("fingerprints")
                val creds = o.optJSONArray("default_credentials")
                CameraVendor(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    fingerprints = CameraFingerprints(
                        httpServer = strings(fp?.optJSONArray("http_server")),
                        httpTitle = strings(fp?.optJSONArray("http_title")),
                        rtspServer = strings(fp?.optJSONArray("rtsp_server")),
                        banner = strings(fp?.optJSONArray("banner"))
                    ),
                    defaultCredentials = if (creds == null) emptyList() else
                        (0 until creds.length()).map { j ->
                            val c = creds.getJSONObject(j)
                            DefaultCredential(c.optString("user"), c.optString("pass"))
                        },
                    firstBootPassword = o.optBoolean("first_boot_password", false),
                    notes = bilingual(o.optJSONObject("notes"))
                )
            }
        }
    }
}

data class RenderedFinding(
    val severityLabel: String,
    val title: String,
    val detail: String,
    val fix: String,
    val host: String,
    val port: Int,
    val severity: Severity
)

object SoorReport {

    private val KIND = mapOf(
        "exposed-camera-default" to Bilingual(
            "كاميرا مكشوفة للإنترنت بكلمة مرور مصنع",
            "Camera exposed to the internet with a factory password"),
        "exposed-service" to Bilingual(
            "خدمة خطرة مكشوفة للإنترنت",
            "Risky service exposed to the internet"),
        "default-credentials" to Bilingual(
            "جهاز على طراز يُشحن ببيانات دخول معروفة",
            "Device on a model that ships with known login details"),
        "open-auth" to Bilingual(
            "خدمة تستجيب دون كلمة مرور",
            "Service that answers with no password"),
        "weak-service" to Bilingual(
            "خدمة تستحق المراجعة",
            "Service worth reviewing"),
        "new-device" to Bilingual(
            "جهاز ظهر على الشبكة لأول مرة",
            "A device seen on the network for the first time")
    )

    private val SEVERITY = mapOf(
        Severity.CRITICAL to Bilingual("حرِج", "Critical"),
        Severity.HIGH to Bilingual("مرتفع", "High"),
        Severity.MEDIUM to Bilingual("متوسط", "Medium"),
        Severity.LOW to Bilingual("منخفض", "Low"),
        Severity.INFO to Bilingual("للعلم", "Info")
    )

    fun kindTitle(kind: String, lang: Lang): String = KIND[kind]?.text(lang) ?: kind
    fun severityLabel(s: Severity, lang: Lang): String = SEVERITY[s]?.text(lang) ?: s.key

    fun render(f: Finding, k: Knowledge, lang: Lang): RenderedFinding {
        val svc = k.service(f.service)
        val cam = k.camera(f.vendor)
        val detail = mutableListOf<String>()
        val fix = mutableListOf<String>()
        val ar = lang == Lang.AR

        cam?.let { detail.add(it.notes.text(lang)) }
        svc?.let { detail.add(it.what.text(lang)); fix.add(it.fix.text(lang)) }

        when (f.kind) {
            "exposed-camera-default" -> {
                detail.add(0, if (ar)
                    "منفذ هذه الكاميرا مرّره الراوتر إلى الإنترنت، وطرازها يُشحن ببيانات دخول معروفة، فهي قابلة لأن تظهر في محركات الفحص العامة."
                else
                    "This camera's port is forwarded to the internet by the router, and its model ships with known login details, so it can appear on public scanning engines.")
                fix.add(0, if (ar)
                    "غيّر كلمة مرور الكاميرا الآن، ثم أغلق تمرير المنفذ في الراوتر أو عطّل UPnP."
                else
                    "Change the camera password now, then close the port forward on the router or disable UPnP.")
            }
            "exposed-service" -> {
                detail.add(0, if (ar)
                    "هذا المنفذ مرّره الراوتر إلى الإنترنت، فصار قابلًا للوصول من خارج البيت."
                else
                    "This port is forwarded to the internet by the router, so it is reachable from outside the home.")
                fix.add(0, if (ar)
                    "أغلق تمرير المنفذ في الراوتر أو عطّل UPnP، ثم راجع الخدمة نفسها."
                else
                    "Close the port forward on the router or disable UPnP, then review the service itself.")
            }
            "default-credentials" -> {
                fix.add(0, if (ar)
                    "غيّر كلمة مرور الجهاز الآن ولا تبقِه على بيانات المصنع."
                else
                    "Change the device password now and do not leave it on factory details.")
            }
            "new-device" -> {
                detail.add(if (ar)
                    "تحقّق أنك تعرف هذا الجهاز، فإن لم تعرفه فافصله وغيّر كلمة مرور الشبكة."
                else
                    "Check that you recognise this device. If you do not, disconnect it and change the network password.")
            }
        }

        if (f.reason == "no-auth") {
            detail.add(if (ar)
                "الخدمة استجابت دون طلب كلمة مرور، وهذا مؤكَّد من الجهاز نفسه."
            else
                "The service answered without asking for a password, confirmed from the device itself.")
        }

        return RenderedFinding(
            severityLabel = severityLabel(f.severity, lang),
            title = kindTitle(f.kind, lang),
            detail = detail.filter { it.isNotEmpty() }.joinToString(" "),
            fix = fix.filter { it.isNotEmpty() }.joinToString(" "),
            host = f.host,
            port = f.port,
            severity = f.severity
        )
    }
}
