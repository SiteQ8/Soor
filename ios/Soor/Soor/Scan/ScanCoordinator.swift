import Foundation
import SwiftUI

// Ties discovery, the router's table, the sweep and the engine together, all on
// the phone. The engine judges every observation; this only gathers them and
// keeps the screen informed while it does. The same state the Android app keeps.

struct DeviceInfo: Identifiable, Equatable {
    let ip: String
    let name: String?
    let kind: DeviceKind
    let ports: [Int]
    let isGateway: Bool
    let isNew: Bool
    let worst: Severity?
    var id: String { ip }
}

/// One thing the scan just did, kept as data so the screen can word it in either language.
struct ScanEvent: Identifiable {
    let id = UUID()
    let kind: String
    let ip: String?
}

@MainActor
final class ScanCoordinator: ObservableObject {

    enum State: Equatable { case idle, scanning, done, noNetwork }
    enum Phase { case discover, probe, judge }

    @Published var state: State = .idle
    @Published var phase: Phase = .discover
    @Published var progress: Double = 0
    @Published var liveHosts: [String] = []
    @Published var portsChecked = 0
    @Published var findings: [Finding] = []
    @Published var devices: [DeviceInfo] = []
    @Published var newFindings: Set<String> = []
    @Published var fixedCount = 0
    @Published var checks: [NetCheck] = []
    @Published var firstScan = false
    @Published var lastScan: Date?
    @Published var network: LocalNet?
    @Published var events: [ScanEvent] = []
    @Published var startedAt: Date?
    @Published var duration: TimeInterval?
    @Published var partial = false
    @Published var stopping = false
    @Published var upnpEnabled = false

    let knowledge = Knowledge.load()
    private var scanner: NetworkScanner?
    private let names = DispatchQueue(label: "soor.names", attributes: .concurrent)

    func start() {
        guard state != .scanning else { return }
        guard let net = NetworkScanner.localNet() else { state = .noNetwork; return }
        state = .scanning
        phase = .discover
        progress = 0.02
        liveHosts = []
        portsChecked = 0
        events = [ScanEvent(kind: "start", ip: nil)]
        network = net
        startedAt = Date()
        partial = false
        stopping = false
        Task { await runScan(net) }
    }

    /// Stops the running scan within about a second and keeps what it found so far.
    func stop() {
        guard state == .scanning else { return }
        stopping = true
        event("stop")
        scanner?.cancel()
    }

    /// Back to the start, keeping the last results reachable from there.
    func home() { if state == .done { state = .idle } }

    func showResults() { if lastScan != nil { state = .done } }

    func forgetDevices() { KnownDevices.forget() }

    private func event(_ kind: String, _ ip: String? = nil) {
        events.append(ScanEvent(kind: kind, ip: ip))
        if events.count > 8 { events.removeFirst(events.count - 8) }
    }

    private func addHost(_ ip: String) {
        if !liveHosts.contains(ip) { liveHosts.append(ip); event("found", ip) }
    }

    private func runScan(_ net: LocalNet) async {
        let scanner = NetworkScanner()
        self.scanner = scanner
        let bonjour = Bonjour()
        bonjour.start()
        event("announce")

        event("router")
        let upnp = UPnPClient()
        let router: UPnPResult = await withCheckedContinuation { c in
            upnp.routerTable(candidates: ["\(net.prefix).1", "\(net.prefix).254"]) { c.resume(returning: $0) }
        }
        upnpEnabled = router.upnpEnabled
        event(router.upnpEnabled ? "upnp-on" : "upnp-off")
        checks = NetworkChecks.run(externalIp: router.externalIp)
        var full = net
        full.gateway = router.gateway ?? "\(net.prefix).1"
        let gw = full.gateway ?? "\(net.prefix).1"
        network = full
        addHost(gw)
        progress = 0.12

        scanner.onHostFound = { [weak self] ip in Task { @MainActor in self?.addHost(ip) } }
        scanner.onProbing = { [weak self] ip in Task { @MainActor in self?.event("probe", ip) } }
        scanner.onProgress = { [weak self] p in
            Task { @MainActor in
                guard let self = self else { return }
                let frac = Double(p.done) / Double(max(1, p.total))
                let discovering = p.phase == "discovering"
                self.phase = discovering ? .discover : .probe
                self.progress = discovering ? 0.12 + 0.43 * frac : 0.55 + 0.37 * frac
                self.portsChecked = p.portsChecked
            }
        }
        let hosts: [HostScan] = await withCheckedContinuation { c in
            scanner.scan(net: full, seeds: [gw], exposed: router.exposed) { c.resume(returning: $0) }
        }
        let stopped = scanner.cancelled
        self.scanner = nil
        bonjour.stop()
        let announced = bonjour.found

        // a Windows PC that announced nothing still answers to its NetBIOS name
        var pcNames: [String: String] = [:]
        for h in hosts where (h.openPorts.contains(139) || h.openPorts.contains(445)) && announced[h.ip] == nil {
            let name: String? = await withCheckedContinuation { c in
                NetBIOS.name(of: h.ip, queue: names) { c.resume(returning: $0) }
            }
            if let n = name { pcNames[h.ip] = n }
        }
        for ip in (Array(announced.keys) + Array(pcNames.keys)).prefix(6) { event("named", ip) }

        phase = .judge
        progress = 0.95
        event("judge")

        // a device is new when this network has been scanned before and it was not on it
        let key = KnownDevices.networkKey(full)
        let seen = KnownDevices.seen(key)
        var ids = Set<String>()
        var newHosts = Set<String>()
        var obs: [Observation] = []
        for h in hosts {
            let id = "ip:" + h.ip
            ids.insert(id)
            let isNew = seen.map { !$0.contains(id) } ?? false
            if isNew { newHosts.insert(h.ip) }
            if h.observations.isEmpty {
                if isNew { var o = Observation(host: h.ip, port: 0); o.isNew = true; obs.append(o) }
            } else {
                obs.append(contentsOf: h.observations.map { var o = $0; o.isNew = isNew; return o })
            }
        }
        if router.upnpEnabled, !obs.contains(where: { $0.host == gw && $0.port == 1900 }) {
            var g = Observation(host: gw, port: 1900)
            g.banner = "UPnP/1.1 IGD rootDevice"
            obs.append(g)
        }
        let result = SoorEngine.analyse(observations: obs, services: knowledge.services, cameras: knowledge.cameras)
        KnownDevices.remember(key, ids)

        // what changed since the last scan of this network, if there was one
        let sigs = Set(result.map { KnownDevices.signature($0) })
        let previous = KnownDevices.lastFindings(key)
        let fresh: Set<String> = (previous == nil || stopped) ? [] : sigs.subtracting(previous ?? [])
        let fixed = (previous == nil || stopped) ? 0 : (previous ?? []).subtracting(sigs).count
        if !stopped { KnownDevices.rememberFindings(key, sigs) }

        devices = hosts.map { h -> DeviceInfo in
            let cameraVendor = h.observations.contains { SoorEngine.matchCamera($0, knowledge.cameras) != nil }
            let found = announced[h.ip]
            var ports = h.openPorts
            if h.ip == gw, router.upnpEnabled, !ports.contains(1900) { ports.append(1900) }
            let hint = [found?.service, found?.name, pcNames[h.ip]].compactMap { $0 }.joined(separator: " ")
            let worst = result.filter { $0.host == h.ip }.min { $0.severity.order < $1.severity.order }?.severity
            return DeviceInfo(ip: h.ip, name: found?.name ?? pcNames[h.ip],
                              kind: DeviceKinds.guess(ports: Set(ports), isGateway: h.ip == gw, cameraVendor: cameraVendor, hint: hint),
                              ports: ports.sorted(), isGateway: h.ip == gw, isNew: newHosts.contains(h.ip), worst: worst)
        }.sorted { a, b in
            if a.isGateway != b.isGateway { return a.isGateway }
            let ao = a.worst?.order ?? 9
            let bo = b.worst?.order ?? 9
            if ao != bo { return ao < bo }
            return NetworkScanner.ipLess(a.ip, b.ip)
        }
        findings = result
        newFindings = fresh
        fixedCount = fixed
        firstScan = seen == nil
        lastScan = Date()
        duration = startedAt.map { Date().timeIntervalSince($0) }
        partial = stopped
        progress = 1
        state = .done
    }

    func summary() -> [Severity: Int] { SoorEngine.summarise(findings) }

    /// A plain-text report the person can share themselves. Nothing is sent by the app.
    func reportText(lang: Lang) -> String {
        let ar = lang == .ar
        var lines: [String] = []
        lines.append(ar ? "تقرير فحص سُور" : "Soor scan report")
        if let d = lastScan {
            let f = DateFormatter(); f.dateStyle = .medium; f.timeStyle = .short
            lines.append(f.string(from: d))
        }
        lines.append(Words.found(devices.count, ar))
        lines.append("")
        if findings.isEmpty { lines.append(ar ? "لا شيء يستحق القلق." : "Nothing to worry about.") }
        for f in findings {
            let r = SoorReport.render(f, knowledge, lang)
            lines.append("[\(r.severityLabel)] \(r.title) | \(f.host)" + (f.port > 0 ? ":\(f.port)" : ""))
            if !r.detail.isEmpty { lines.append(r.detail) }
            if !r.fix.isEmpty { lines.append((ar ? "الحل: " : "Fix: ") + r.fix) }
            lines.append("")
        }
        lines.append(ar ? "الأجهزة:" : "Devices:")
        for d in devices {
            lines.append("• " + (d.name ?? DeviceKinds.label(d.kind, ar)) + " | \(d.ip)" + (d.ports.isEmpty ? "" : " | " + d.ports.map(String.init).joined(separator: ", ")))
        }
        lines.append("")
        lines.append(ar ? "فُحص محليًا على الجهاز بتطبيق سُور، ولم يُرسل شيء إلى أي مكان."
                        : "Scanned locally on the device by Soor. Nothing was sent anywhere.")
        return lines.joined(separator: "\n")
    }

    // MARK: - Demo for the store screenshots
    //
    // A sample home, judged by the real engine exactly as a scan is. Reached only
    // through the launch argument -soor-demo, which nothing sets in normal use.

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
        let result = SoorEngine.analyse(observations: obs, services: knowledge.services, cameras: knowledge.cameras)
        func worst(_ ip: String) -> Severity? { result.filter { $0.host == ip }.min { $0.severity.order < $1.severity.order }?.severity }
        func dev(_ ip: String, _ name: String?, _ kind: DeviceKind, _ ports: [Int], gw: Bool = false, new: Bool = false) -> DeviceInfo {
            DeviceInfo(ip: ip, name: name, kind: kind, ports: ports, isGateway: gw, isNew: new, worst: worst(ip))
        }
        devices = [
            dev("192.168.1.1", nil, .router, [80, 443, 1900], gw: true),
            dev("192.168.1.64", nil, .camera, [554]),
            dev("192.168.1.90", "Living Room TV", .tv, [5555, 8008, 8009]),
            dev("192.168.1.30", "Office Printer", .printer, [80, 631, 9100]),
            dev("192.168.1.10", "DESKTOP-7K2QA", .computer, [22]),
            dev("192.168.1.42", "Ali's iPhone", .phone, [62078]),
            dev("192.168.1.150", nil, .unknown, [], new: true),
        ]
        findings = result
        newFindings = ["exposed-camera-default|192.168.1.64|554"]
        fixedCount = 2
        network = LocalNet(ip: "192.168.1.42", prefix: "192.168.1", gateway: "192.168.1.1")
        liveHosts = devices.map { $0.ip }
        portsChecked = 1834
        duration = 48
        upnpEnabled = true
        checks = [NetworkChecks.external("37.36.12.204")]
        lastScan = Date()
        state = .done
    }

    func demoScanning() {
        state = .scanning
        phase = .probe
        progress = 0.62
        network = LocalNet(ip: "192.168.1.42", prefix: "192.168.1", gateway: "192.168.1.1")
        liveHosts = ["192.168.1.1", "192.168.1.64", "192.168.1.90", "192.168.1.30", "192.168.1.10"]
        portsChecked = 1287
        startedAt = Date().addingTimeInterval(-41)
        events = [ScanEvent(kind: "start", ip: nil), ScanEvent(kind: "announced", ip: "192.168.1.90"), ScanEvent(kind: "upnp-on", ip: nil),
                  ScanEvent(kind: "found", ip: "192.168.1.64"), ScanEvent(kind: "found", ip: "192.168.1.30"),
                  ScanEvent(kind: "probe", ip: "192.168.1.1"), ScanEvent(kind: "probe", ip: "192.168.1.64")]
    }
}

/// Numbers in Arabic take different words by count, so each sentence is written out whole.
enum Words {
    static func found(_ n: Int, _ ar: Bool) -> String {
        if !ar { return n == 0 ? "Soor found no other devices on your network" : n == 1 ? "Soor found 1 device on your network" : "Soor found \(n) devices on your network" }
        switch n {
        case 0: return "لم يجد سُور أجهزة أخرى على شبكتك"
        case 1: return "وجد سُور جهازًا واحدًا على شبكتك"
        case 2: return "وجد سُور جهازين على شبكتك"
        case 3...10: return "وجد سُور \(n) أجهزة على شبكتك"
        default: return "وجد سُور \(n) جهازًا على شبكتك"
        }
    }

    static func newOnes(_ n: Int, _ ar: Bool) -> String {
        if !ar { return n == 1 ? "1 of them is new" : "\(n) of them are new" }
        switch n {
        case 1: return "منها جهاز جديد لم يظهر من قبل"
        case 2: return "منها جهازان جديدان لم يظهرا من قبل"
        case 3...10: return "منها \(n) أجهزة جديدة لم تظهر من قبل"
        default: return "منها \(n) جهازًا جديدًا"
        }
    }

    static func fixed(_ n: Int, _ ar: Bool) -> String {
        if !ar { return n == 1 ? "1 finding from the last scan is gone" : "\(n) findings from the last scan are gone" }
        switch n {
        case 1: return "زالت ملاحظة واحدة من الفحص السابق"
        case 2: return "زالت ملاحظتان من الفحص السابق"
        case 3...10: return "زالت \(n) ملاحظات من الفحص السابق"
        default: return "زالت \(n) ملاحظة من الفحص السابق"
        }
    }
}
