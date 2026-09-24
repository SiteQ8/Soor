import Foundation

// Loads the shared JSON knowledge base (the same files the site and tests use,
// copied into the app bundle) and renders findings into bilingual text, mirroring
// docs/engine/report.js.

struct Knowledge {
    let services: [ServiceEntry]
    let cameras: [CameraVendor]

    static func load(bundle: Bundle = .main) -> Knowledge {
        let dec = JSONDecoder()
        func url(_ name: String) -> URL? {
            bundle.url(forResource: name, withExtension: "json")
        }
        var services: [ServiceEntry] = []
        var cameras: [CameraVendor] = []
        if let u = url("services"), let d = try? Data(contentsOf: u),
           let f = try? dec.decode(ServicesFile.self, from: d) {
            services = f.services
        }
        if let u = url("cameras"), let d = try? Data(contentsOf: u),
           let f = try? dec.decode(CamerasFile.self, from: d) {
            cameras = f.vendors
        }
        return Knowledge(services: services, cameras: cameras)
    }

    func service(_ id: String?) -> ServiceEntry? {
        guard let id = id else { return nil }
        return services.first { $0.id == id }
    }
    func camera(_ id: String?) -> CameraVendor? {
        guard let id = id else { return nil }
        return cameras.first { $0.id == id }
    }
}

struct RenderedFinding {
    let severityLabel: String
    let title: String
    let detail: String
    let fix: String
    let host: String
    let port: Int
    let severity: Severity
}

enum SoorReport {

    static func kindTitle(_ kind: String, _ lang: Lang) -> String {
        let map: [String: Bilingual] = [
            "exposed-camera-default": Bilingual(ar: "كاميرا مكشوفة للإنترنت بكلمة مرور مصنع", en: "Camera exposed to the internet with a factory password"),
            "exposed-service": Bilingual(ar: "خدمة خطرة مكشوفة للإنترنت", en: "Risky service exposed to the internet"),
            "default-credentials": Bilingual(ar: "جهاز على طراز يُشحن ببيانات دخول معروفة", en: "Device on a model that ships with known login details"),
            "open-auth": Bilingual(ar: "خدمة تستجيب دون كلمة مرور", en: "Service that answers with no password"),
            "weak-service": Bilingual(ar: "خدمة تستحق المراجعة", en: "Service worth reviewing"),
            "new-device": Bilingual(ar: "جهاز ظهر على الشبكة لأول مرة", en: "A device seen on the network for the first time")
        ]
        return map[kind]?.text(lang) ?? kind
    }

    static func severityLabel(_ s: Severity, _ lang: Lang) -> String {
        let map: [Severity: Bilingual] = [
            .critical: Bilingual(ar: "حرِج", en: "Critical"),
            .high: Bilingual(ar: "مرتفع", en: "High"),
            .medium: Bilingual(ar: "متوسط", en: "Medium"),
            .low: Bilingual(ar: "منخفض", en: "Low"),
            .info: Bilingual(ar: "للعلم", en: "Info")
        ]
        return map[s]?.text(lang) ?? s.rawValue
    }

    static func render(_ f: Finding, _ k: Knowledge, _ lang: Lang) -> RenderedFinding {
        let svc = k.service(f.service)
        let cam = k.camera(f.vendor)
        var detail: [String] = []
        var fix: [String] = []

        if let cam = cam { detail.append(cam.notes.text(lang)) }
        if let svc = svc { detail.append(svc.what.text(lang)); fix.append(svc.fix.text(lang)) }

        switch f.kind {
        case "exposed-camera-default":
            detail.insert(lang == .ar
                ? "منفذ هذه الكاميرا مرّره الراوتر إلى الإنترنت، وطرازها يُشحن ببيانات دخول معروفة، فهي قابلة لأن تظهر في محركات الفحص العامة."
                : "This camera's port is forwarded to the internet by the router, and its model ships with known login details, so it can appear on public scanning engines.", at: 0)
            fix.insert(lang == .ar
                ? "غيّر كلمة مرور الكاميرا الآن، ثم أغلق تمرير المنفذ في الراوتر أو عطّل UPnP."
                : "Change the camera password now, then close the port forward on the router or disable UPnP.", at: 0)
        case "exposed-service":
            detail.insert(lang == .ar
                ? "هذا المنفذ مرّره الراوتر إلى الإنترنت، فصار قابلًا للوصول من خارج البيت."
                : "This port is forwarded to the internet by the router, so it is reachable from outside the home.", at: 0)
            fix.insert(lang == .ar
                ? "أغلق تمرير المنفذ في الراوتر أو عطّل UPnP، ثم راجع الخدمة نفسها."
                : "Close the port forward on the router or disable UPnP, then review the service itself.", at: 0)
        case "default-credentials":
            fix.insert(lang == .ar
                ? "غيّر كلمة مرور الجهاز الآن ولا تبقِه على بيانات المصنع."
                : "Change the device password now and do not leave it on factory details.", at: 0)
        case "new-device":
            detail.append(lang == .ar
                ? "تحقّق أنك تعرف هذا الجهاز، فإن لم تعرفه فافصله وغيّر كلمة مرور الشبكة."
                : "Check that you recognise this device. If you do not, disconnect it and change the network password.")
        default: break
        }

        if f.reason == "no-auth" {
            detail.append(lang == .ar
                ? "الخدمة استجابت دون طلب كلمة مرور، وهذا مؤكَّد من الجهاز نفسه."
                : "The service answered without asking for a password, confirmed from the device itself.")
        }

        return RenderedFinding(
            severityLabel: severityLabel(f.severity, lang),
            title: kindTitle(f.kind, lang),
            detail: detail.filter { !$0.isEmpty }.joined(separator: " "),
            fix: fix.filter { !$0.isEmpty }.joined(separator: " "),
            host: f.host, port: f.port, severity: f.severity)
    }
}
