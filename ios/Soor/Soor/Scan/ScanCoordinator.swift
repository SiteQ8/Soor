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
