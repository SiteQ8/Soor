import Foundation
import SwiftUI

// Ties the scanner, the UPnP reader, and the engine together. The view observes
// this. Order: read the router's forwarded ports first (so exposure is known),
// then sweep the network, then run the engine. Everything on the device.

@MainActor
final class ScanCoordinator: ObservableObject {

    enum State: Equatable { case idle, scanning, done, noNetwork }

    @Published var state: State = .idle
    @Published var progressText: String = ""
    @Published var progressValue: Double = 0
    @Published var findings: [Finding] = []
    @Published var deviceCount: Int = 0
    @Published var upnpEnabled: Bool = false
    @Published var lastScan: Date?

    let knowledge = Knowledge.load()
    private let scanner = NetworkScanner()
    private let upnp = UPnPClient()

    func start(lang: Lang) {
        guard state != .scanning else { return }
        guard NetworkScanner.localIPv4AndPrefix() != nil else {
            state = .noNetwork; return
        }
        state = .scanning
        findings = []
        deviceCount = 0
        progressValue = 0
        progressText = lang == .ar ? "قراءة إعدادات الراوتر…" : "Reading router settings…"

        scanner.onProgress = { [weak self] p in
            guard let self = self else { return }
            Task { @MainActor in
                if p.total > 0 { self.progressValue = Double(p.scanned) / Double(p.total) }
                switch p.phase {
                case "discovering":
                    self.progressText = lang == .ar
                        ? "البحث عن الأجهزة… \(p.found) جهاز"
                        : "Finding devices… \(p.found) found"
                case "probing":
                    self.progressText = lang == .ar
                        ? "فحص المنافذ… \(p.scanned)/\(p.total)"
                        : "Probing ports… \(p.scanned)/\(p.total)"
                default: break
                }
            }
        }

        // Step 1: router exposure, then Step 2: the sweep.
        upnp.exposedPorts { [weak self] ports, enabled, _ in
            guard let self = self else { return }
            Task { @MainActor in self.upnpEnabled = enabled }
            self.scanner.scan(exposedPorts: ports) { observations in
                Task { @MainActor in
                    self.finish(observations: observations, exposedPorts: ports, upnpEnabled: enabled)
                }
            }
        }
    }

    private func finish(observations: [Observation], exposedPorts: Set<Int>, upnpEnabled: Bool) {
        var obs = observations
        // Represent an enabled UPnP as its own reviewable finding on the router.
        if upnpEnabled, let (_, prefix) = NetworkScanner.localIPv4AndPrefix() {
            let gateway = "\(prefix).1"
            if !obs.contains(where: { $0.host == gateway && $0.port == 1900 }) {
                var g = Observation(host: gateway, port: 1900)
                g.banner = "UPnP/1.1 IGD rootDevice"
                obs.append(g)
            }
        }
        let hosts = Set(obs.map { $0.host })
        deviceCount = hosts.count
        findings = SoorEngine.analyse(observations: obs,
                                      services: knowledge.services,
                                      cameras: knowledge.cameras)
        lastScan = Date()
        state = .done
    }

    // MARK: - Demo for the store screenshots
    //
    // A sample home, judged by the real engine exactly as a scan is, so the
    // screenshots show what the app shows for that home. Reached only through
    // the launch argument -soor-demo, which nothing sets in normal use.

    func loadDemo() {
        var router = Observation(host: "192.168.1.1", port: 80)
        router.httpServer = "Router Webserver"; router.banner = "WWW-Authenticate: Basic realm"
        var upnp = Observation(host: "192.168.1.1", port: 1900)
        upnp.banner = "UPnP/1.1 IGD rootDevice"
        var tls = Observation(host: "192.168.1.1", port: 443)
        tls.banner = "self-signed certificate"
        var camera = Observation(host: "192.168.1.64", port: 554)
        camera.rtspServer = "Hipcam RealServer/V1.0"; camera.noAuth = true; camera.internetExposed = true
        let box = Observation(host: "192.168.1.90", port: 5555)
        var printer = Observation(host: "192.168.1.30", port: 80)
        printer.httpServer = "HP HTTP Server"; printer.httpTitle = "Printer"; printer.banner = "WWW-Authenticate: Basic realm"
        var laptop = Observation(host: "192.168.1.10", port: 22)
        laptop.banner = "SSH-2.0-OpenSSH_9.6"
        var mystery = Observation(host: "192.168.1.150", port: 0)
        mystery.isNew = true
        let obs = [router, upnp, tls, camera, box, printer, laptop, mystery]
        deviceCount = Set(obs.map { $0.host }).count
        findings = SoorEngine.analyse(observations: obs, services: knowledge.services, cameras: knowledge.cameras)
        upnpEnabled = true
        lastScan = Date()
        state = .done
    }

    func demoScanning(lang: Lang) {
        state = .scanning
        progressValue = 0.62
        progressText = lang == .ar ? "فحص المنافذ… 9/14" : "Probing ports… 9/14"
    }

    func summary() -> [Severity: Int] { SoorEngine.summarise(findings) }

    func reportText(lang: Lang) -> String {
        var lines: [String] = []
        lines.append(lang == .ar ? "تقرير فحص سُور" : "Soor scan report")
        if let d = lastScan {
            let f = DateFormatter(); f.dateStyle = .medium; f.timeStyle = .short
            lines.append(f.string(from: d))
        }
        lines.append(lang == .ar ? "عدد الأجهزة: \(deviceCount)" : "Devices: \(deviceCount)")
        lines.append("")
        if findings.isEmpty {
            lines.append(lang == .ar ? "لا مخاطر ظاهرة." : "No visible risks.")
        }
        for f in findings {
            let r = SoorReport.render(f, knowledge, lang)
            lines.append("[\(r.severityLabel)] \(r.title) | \(r.host):\(r.port)")
            if !r.detail.isEmpty { lines.append(r.detail) }
            if !r.fix.isEmpty { lines.append((lang == .ar ? "الحل: " : "Fix: ") + r.fix) }
            lines.append("")
        }
        lines.append(lang == .ar
            ? "فُحص محليًا على الجهاز بتطبيق سُور، ولم تُجمع أو تُرسل أي بيانات."
            : "Scanned locally on the device by Soor. No data collected or sent.")
        return lines.joined(separator: "\n")
    }
}
