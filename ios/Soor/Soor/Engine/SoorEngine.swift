import Foundation

// The Soor engine, ported to Swift from docs/engine/soor.js.
// It takes what a scan already found on the local network and turns it into
// findings a person can act on. It reaches no network of its own and keeps no
// state. The shared vectors in tests/vectors.json must produce the same finding
// here as in the JS and Kotlin engines.

// MARK: - Knowledge base models

struct Bilingual: Codable, Hashable {
    let ar: String
    let en: String
    func text(_ lang: Lang) -> String { lang == .ar ? ar : en }
}

enum Lang: String { case ar, en }

struct ServiceEntry: Codable {
    let id: String
    let ports: [Int]
    let proto: String
    let match: [String]
    let category: String
    let severity: String
    let name: Bilingual
    let what: Bilingual
    let fix: Bilingual
}

struct DefaultCredential: Codable {
    let user: String
    let pass: String
}

struct CameraFingerprints: Codable {
    let http_server: [String]?
    let http_title: [String]?
    let rtsp_server: [String]?
    let banner: [String]?
}

struct CameraVendor: Codable {
    let id: String
    let name: String
    let fingerprints: CameraFingerprints
    let default_credentials: [DefaultCredential]
    let first_boot_password: Bool
    let notes: Bilingual
}

struct ServicesFile: Codable { let services: [ServiceEntry] }
struct CamerasFile: Codable { let vendors: [CameraVendor] }

// MARK: - Scan observation and finding

struct Observation {
    var host: String
    var port: Int
    var proto: String = "tcp"
    var httpServer: String?
    var httpTitle: String?
    var rtspServer: String?
    var banner: String?
    var raw: String?
    var noAuth: Bool = false
    var internetExposed: Bool = false
    var deviceName: String?
    var isNew: Bool = false
}

enum Severity: String, CaseIterable {
    case critical, high, medium, low, info
    var order: Int {
        switch self {
        case .critical: return 0
        case .high: return 1
        case .medium: return 2
        case .low: return 3
        case .info: return 4
        }
    }
}

struct Finding: Identifiable {
    let id = UUID()
    var kind: String
    var severity: Severity
    var host: String
    var port: Int
    var exposed: Bool
    var service: String?
    var vendor: String?
    var reason: String?
}

// MARK: - Engine

enum SoorEngine {

    private static func lower(_ s: String?) -> String { (s ?? "").lowercased() }

    private static func anyMatch(_ haystack: String?, _ needles: [String]?) -> Bool {
        guard let needles = needles, !needles.isEmpty else { return false }
        let h = lower(haystack)
        for n in needles {
            let nn = n.lowercased()
            if !nn.isEmpty && h.contains(nn) { return true }
        }
        return false
    }

    private static func serviceBanner(_ o: Observation) -> String {
        [o.httpServer, o.httpTitle, o.rtspServer, o.banner, o.raw]
            .compactMap { $0 }
            .joined(separator: " \n ")
    }

    static func matchService(_ o: Observation, _ services: [ServiceEntry]) -> ServiceEntry? {
        let banner = serviceBanner(o)
        var byPort: ServiceEntry?
        for svc in services {
            let portHit = svc.ports.contains(o.port)
            if anyMatch(banner, svc.match) { return svc }   // banner beats port
            if portHit && byPort == nil { byPort = svc }
        }
        return byPort
    }

    static func matchCamera(_ o: Observation, _ vendors: [CameraVendor]) -> CameraVendor? {
        let banner = serviceBanner(o)
        for v in vendors {
            let fp = v.fingerprints
            if anyMatch(o.httpServer, fp.http_server) ||
               anyMatch(o.httpTitle, fp.http_title) ||
               anyMatch(o.rtspServer, fp.rtsp_server) ||
               anyMatch(banner, fp.banner) {
                return v
            }
        }
        return nil
    }

    static func shipsWeakCredentials(_ vendor: CameraVendor?) -> Bool {
        guard let vendor = vendor else { return false }
        return vendor.default_credentials.contains { c in
            let pass = c.pass.lowercased()
            return pass.isEmpty ||
                (!pass.contains("set on first boot") && !pass.contains("account based"))
        }
    }

    private static func sev(_ s: String) -> Severity { Severity(rawValue: s) ?? .info }

    static func analyse(observations: [Observation],
                        services: [ServiceEntry],
                        cameras: [CameraVendor]) -> [Finding] {
        var findings: [Finding] = []

        for o in observations {
            let svc = matchService(o, services)
            let cam = matchCamera(o, cameras)
            let isCamera = cam != nil || (svc?.category == "camera")

            if isCamera, let cam = cam, shipsWeakCredentials(cam) {
                if o.internetExposed {
                    findings.append(Finding(kind: "exposed-camera-default", severity: .critical,
                        host: o.host, port: o.port, exposed: true,
                        service: svc?.id ?? "rtsp", vendor: cam.id,
                        reason: o.noAuth ? "no-auth" : "known-default"))
                } else {
                    findings.append(Finding(kind: "default-credentials", severity: .high,
                        host: o.host, port: o.port, exposed: false,
                        service: svc?.id, vendor: cam.id,
                        reason: o.noAuth ? "no-auth" : "known-default"))
                }
                continue
            }

            if let svc = svc, o.internetExposed, svc.severity != "low" {
                findings.append(Finding(kind: "exposed-service", severity: .critical,
                    host: o.host, port: o.port, exposed: true, service: svc.id, vendor: nil, reason: nil))
                continue
            }

            if let svc = svc, o.noAuth, svc.category != "info" {
                findings.append(Finding(kind: "open-auth", severity: .high,
                    host: o.host, port: o.port, exposed: o.internetExposed, service: svc.id, vendor: nil, reason: nil))
                continue
            }

            if let svc = svc {
                let s: Severity = svc.severity == "low" ? .info : sev(svc.severity)
                findings.append(Finding(kind: "weak-service", severity: s,
                    host: o.host, port: o.port, exposed: o.internetExposed, service: svc.id, vendor: nil, reason: nil))
                continue
            }

            if o.isNew {
                findings.append(Finding(kind: "new-device", severity: .info,
                    host: o.host, port: o.port, exposed: o.internetExposed, service: nil, vendor: nil, reason: nil))
            }
        }

        // Collapse duplicate camera findings for the same host+vendor to the worst.
        let cameraKinds: Set<String> = ["exposed-camera-default", "default-credentials"]
        var best: [String: Int] = [:]   // key -> index in kept
        var kept: [Finding] = []
        for f in findings {
            guard cameraKinds.contains(f.kind), let v = f.vendor else { kept.append(f); continue }
            let key = "\(f.host)|\(v)"
            if let idx = best[key] {
                if f.severity.order < kept[idx].severity.order { kept[idx] = f }
                // else drop this duplicate
            } else {
                best[key] = kept.count
                kept.append(f)
            }
        }
        findings = kept

        // A new device is worth noting once per host, without duplicating a louder finding.
        var hostsWithFinding = Set(findings.map { $0.host })
        for o in observations where o.isNew && !hostsWithFinding.contains(o.host) {
            findings.append(Finding(kind: "new-device", severity: .info,
                host: o.host, port: o.port, exposed: o.internetExposed, service: nil, vendor: nil, reason: nil))
            hostsWithFinding.insert(o.host)
        }

        findings.sort { a, b in
            if a.severity.order != b.severity.order { return a.severity.order < b.severity.order }
            if a.host != b.host { return a.host < b.host }
            return a.port < b.port
        }
        return findings
    }

    static func summarise(_ findings: [Finding]) -> [Severity: Int] {
        var counts: [Severity: Int] = [:]
        for s in Severity.allCases { counts[s] = 0 }
        for f in findings { counts[f.severity, default: 0] += 1 }
        return counts
    }
}
