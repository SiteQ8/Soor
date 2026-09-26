import Foundation
import Network
import Security

// The scan itself, on the phone and on the home network only. It finds the
// devices, knocks on their common ports, and reads what each service announces
// about itself. It never sends a password and never tries a login.
//
// The same rules as the Android app: a refusal proves a device is present, a
// web page answering 200 is never called "no password", and a certificate is
// called self-signed only when the handshake actually fails to verify.

struct LocalNet {
    let ip: String
    let prefix: String
    var gateway: String?
}

struct HostScan {
    let ip: String
    let openPorts: [Int]
    let observations: [Observation]
}

struct ScanProgress {
    let phase: String
    let done: Int
    let total: Int
    let portsChecked: Int
}

/// Only addresses inside the home network are ever contacted, the same rule the
/// Android app enforces before every connection.
enum LocalOnly {
    static let ssdpGroup = "239.255.255.250"

    static func isAllowed(_ host: String) -> Bool {
        if host == ssdpGroup { return true }
        let p = host.split(separator: ".").compactMap { Int($0) }
        guard p.count == 4, p.allSatisfy({ (0...255).contains($0) }) else { return false }
        if p[0] == 10 { return true }
        if p[0] == 172, (16...31).contains(p[1]) { return true }
        if p[0] == 192, p[1] == 168 { return true }
        if p[0] == 169, p[1] == 254 { return true }
        return false
    }
}

/// Runs closures with at most `limit` in flight; each closure calls done() when finished.
final class Gate {
    private let q = DispatchQueue(label: "soor.gate")
    private let limit: Int
    private var running = 0
    private var pending: [(@escaping () -> Void) -> Void] = []

    init(_ limit: Int) { self.limit = limit }

    func run(_ body: @escaping (@escaping () -> Void) -> Void) {
        q.async {
            if self.running < self.limit {
                self.running += 1
                body { self.finish() }
            } else {
                self.pending.append(body)
            }
        }
    }

    private func finish() {
        q.async {
            self.running -= 1
            if self.running < self.limit, !self.pending.isEmpty {
                let next = self.pending.removeFirst()
                self.running += 1
                next { self.finish() }
            }
        }
    }
}

final class NetworkScanner {

    enum Knock { case open, closed, silent }

    // the ports the knowledge base judges, plus a few that only tell what a device is
    static let commonPorts: [Int] = [21, 22, 23, 80, 81, 443, 139, 445, 548, 554, 631, 1900, 2020, 2323, 3389, 5555,
                                     7000, 8000, 8008, 8009, 8080, 8081, 8443, 8554, 8899, 9000, 9100, 34567, 62078]
    // a device is present if it answers on any of these, even to refuse
    static let livenessPorts: [Int] = [80, 443, 22, 445, 62078, 8080, 554, 5555, 8008, 9100, 7000, 139]
    private static let httpPorts: Set<Int> = [80, 81, 8080, 8081, 8000]
    private static let tlsPorts: Set<Int> = [443, 8443]
    private static let rtspPorts: Set<Int> = [554, 8554]

    private let queue = DispatchQueue(label: "soor.scan", attributes: .concurrent)
    private let counter = DispatchQueue(label: "soor.count")
    private var portsChecked = 0
    private(set) var cancelled = false

    var onProgress: ((ScanProgress) -> Void)?
    var onHostFound: ((String) -> Void)?
    var onProbing: ((String) -> Void)?

    /// Stopping is cooperative: the flag is checked before every knock, so a stop lands within a second.
    func cancel() { cancelled = true }

    func scan(net: LocalNet, seeds: [String], exposed: Set<String>, completion: @escaping ([HostScan]) -> Void) {
        discoverHosts(net: net, seeds: seeds) { hosts in
            let gate = Gate(16)
            let group = DispatchGroup()
            var results: [HostScan] = []
            var done = 0
            for ip in hosts {
                group.enter()
                gate.run { finish in
                    if self.cancelled { finish(); group.leave(); return }
                    self.onProbing?(ip)
                    self.probeHost(ip, exposed: exposed) { h in
                        self.counter.async {
                            results.append(h)
                            done += 1
                            self.onProgress?(ScanProgress(phase: "probing", done: done, total: hosts.count, portsChecked: self.portsChecked))
                            finish()
                            group.leave()
                        }
                    }
                }
            }
            group.notify(queue: self.counter) {
                completion(results.sorted { NetworkScanner.ipLess($0.ip, $1.ip) })
            }
        }
    }

    private func discoverHosts(net: LocalNet, seeds: [String], completion: @escaping ([String]) -> Void) {
        let lock = DispatchQueue(label: "soor.live")
        var live: [String] = []
        func found(_ ip: String) {
            var isNew = false
            lock.sync {
                if ip != net.ip && !live.contains(ip) { live.append(ip); isNew = true }
            }
            if isNew { onHostFound?(ip) }
        }
        seeds.filter { $0.hasPrefix(net.prefix + ".") }.forEach { found($0) }

        let gate = Gate(40)
        let group = DispatchGroup()
        var checked = 0
        let advance: (@escaping () -> Void) -> Void = { finish in
            self.counter.async {
                checked += 1
                self.onProgress?(ScanProgress(phase: "discovering", done: checked, total: 254, portsChecked: self.portsChecked))
                finish()
                group.leave()
            }
        }
        for i in 1...254 {
            let ip = "\(net.prefix).\(i)"
            group.enter()
            gate.run { finish in
                let already = lock.sync { live.contains(ip) }
                if self.cancelled || ip == net.ip || already { advance(finish); return }
                self.isHostUp(ip) { up in
                    if up { found(ip) }
                    advance(finish)
                }
            }
        }
        group.notify(queue: counter) { completion(lock.sync { live }) }
    }

    private func isHostUp(_ ip: String, completion: @escaping (Bool) -> Void) {
        func step(_ i: Int) {
            if i >= NetworkScanner.livenessPorts.count || cancelled { completion(false); return }
            knock(ip, NetworkScanner.livenessPorts[i], timeout: 0.4) { k in
                if k == .silent { step(i + 1) } else { completion(true) }
            }
        }
        step(0)
    }

    private static func isRefused(_ e: NWError) -> Bool {
        if case .posix(let code) = e { return code == .ECONNREFUSED }
        return false
    }

    private func knock(_ ip: String, _ port: Int, timeout: TimeInterval, completion: @escaping (Knock) -> Void) {
        counter.async { self.portsChecked += 1 }
        guard LocalOnly.isAllowed(ip), let p = NWEndpoint.Port(rawValue: UInt16(port)) else { completion(.silent); return }
        let conn = NWConnection(host: NWEndpoint.Host(ip), port: p, using: .tcp)
        var finished = false
        let finish: (Knock) -> Void = { k in
            self.counter.async {
                if finished { return }
                finished = true
                conn.cancel()
                completion(k)
            }
        }
        conn.stateUpdateHandler = { state in
            switch state {
            case .ready: finish(.open)
            case .failed(let e): finish(NetworkScanner.isRefused(e) ? .closed : .silent)
            case .waiting(let e): finish(NetworkScanner.isRefused(e) ? .closed : .silent)
            default: break
            }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + timeout) { finish(.silent) }
    }

    private func probeHost(_ ip: String, exposed: Set<String>, completion: @escaping (HostScan) -> Void) {
        var open: [Int] = []
        var obs: [Observation] = []
        func step(_ i: Int) {
            if i >= NetworkScanner.commonPorts.count || cancelled {
                completion(HostScan(ip: ip, openPorts: open, observations: obs)); return
            }
            let port = NetworkScanner.commonPorts[i]
            knock(ip, port, timeout: 0.9) { k in
                guard k == .open else { step(i + 1); return }
                open.append(port)
                let after: (String) -> Void = { banner in
                    obs.append(self.buildObservation(ip: ip, port: port, banner: banner, exposed: exposed.contains("\(ip):\(port)")))
                    step(i + 1)
                }
                if NetworkScanner.tlsPorts.contains(port) {
                    self.tlsUntrusted(ip, port) { r in
                        if let r = r { after(r ? "self-signed certificate" : "trusted certificate") } else { after("") }
                    }
                } else {
                    self.readBanner(ip, port, after)
                }
            }
        }
        step(0)
    }

    private func buildObservation(ip: String, port: Int, banner: String, exposed: Bool) -> Observation {
        var o = Observation(host: ip, port: port)
        o.internetExposed = exposed
        o.noAuth = NetworkScanner.looksUnauthenticated(banner, port: port)
        if NetworkScanner.rtspPorts.contains(port) {
            o.rtspServer = header(banner, "Server") ?? (banner.isEmpty ? nil : String(banner.prefix(200)))
        } else if NetworkScanner.httpPorts.contains(port) {
            o.httpServer = header(banner, "Server")
            o.httpTitle = title(banner)
            o.raw = banner.isEmpty ? nil : String(banner.prefix(400))
        } else {
            o.banner = banner.isEmpty ? nil : String(banner.prefix(200))
        }
        return o
    }

    /// Reads what the service volunteers: a bare GET for a web port, a DESCRIBE for
    /// a stream port, which a camera answers with the stream itself when it asks
    /// for no password. No credentials are ever sent.
    private func readBanner(_ ip: String, _ port: Int, _ completion: @escaping (String) -> Void) {
        guard LocalOnly.isAllowed(ip), let p = NWEndpoint.Port(rawValue: UInt16(port)) else { completion(""); return }
        let conn = NWConnection(host: NWEndpoint.Host(ip), port: p, using: .tcp)
        var finished = false
        let finish: (String) -> Void = { text in
            self.counter.async {
                if finished { return }
                finished = true
                conn.cancel()
                completion(text)
            }
        }
        conn.stateUpdateHandler = { state in
            switch state {
            case .ready:
                var request: String? = nil
                if NetworkScanner.httpPorts.contains(port) {
                    request = "GET / HTTP/1.0\r\nHost: \(ip)\r\nUser-Agent: Soor\r\n\r\n"
                } else if NetworkScanner.rtspPorts.contains(port) {
                    request = "DESCRIBE rtsp://\(ip):\(port)/ RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\nUser-Agent: Soor\r\n\r\n"
                }
                if let r = request, let data = r.data(using: .utf8) {
                    conn.send(content: data, completion: .contentProcessed { _ in })
                }
                conn.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, _, _ in
                    finish(data.flatMap { String(data: $0, encoding: .isoLatin1) } ?? "")
                }
            case .failed, .waiting:
                finish("")
            default:
                break
            }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 2.0) { finish("") }
    }

    /// Whether a TLS service's certificate fails to verify against the phone's own
    /// trust store, read from the handshake. Nothing is sent after it.
    /// true: no trusted authority vouches for it, almost always self-signed.
    /// nil: the handshake failed for another reason, so nothing is claimed.
    private func tlsUntrusted(_ ip: String, _ port: Int, _ completion: @escaping (Bool?) -> Void) {
        guard LocalOnly.isAllowed(ip), let p = NWEndpoint.Port(rawValue: UInt16(port)) else { completion(nil); return }
        let tls = NWProtocolTLS.Options()
        var untrusted: Bool? = nil
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let t = sec_trust_copy_ref(trust).takeRetainedValue()
            var error: CFError?
            let ok = SecTrustEvaluateWithError(t, &error)
            untrusted = !ok
            complete(ok)
        }, queue)
        let conn = NWConnection(host: NWEndpoint.Host(ip), port: p, using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
        var finished = false
        let finish: () -> Void = {
            self.counter.async {
                if finished { return }
                finished = true
                conn.cancel()
                completion(untrusted)
            }
        }
        conn.stateUpdateHandler = { state in
            switch state {
            case .ready, .failed, .waiting: finish()
            default: break
            }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 2.5) { finish() }
    }

    /// Only a stream that hands over its description to an anonymous DESCRIBE is
    /// called open. A web page answering 200 proves nothing, since most admin
    /// panels answer 200 with a login form.
    private static func looksUnauthenticated(_ text: String, port: Int) -> Bool {
        guard !text.isEmpty, rtspPorts.contains(port) else { return false }
        let t = text.lowercased()
        return t.hasPrefix("rtsp/1.0 200") && (t.contains("application/sdp") || t.contains("\nv=0"))
    }

    private func header(_ text: String, _ name: String) -> String? {
        for line in text.split(separator: "\n") {
            let l = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if l.lowercased().hasPrefix(name.lowercased() + ":") {
                let v = l.dropFirst(name.count + 1).trimmingCharacters(in: .whitespaces)
                return v.isEmpty ? nil : String(v)
            }
        }
        return nil
    }

    private func title(_ text: String) -> String? {
        let lower = text.lowercased()
        guard let a = lower.range(of: "<title>"), let b = lower.range(of: "</title>", range: a.upperBound..<lower.endIndex) else { return nil }
        let t = text[a.upperBound..<b.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    /// The phone's own address and /24 on Wi-Fi, or nil when not on one.
    static func localNet() -> LocalNet? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>? = nil
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            let flags = Int32(p.pointee.ifa_flags)
            if let sa = p.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET),
               (flags & IFF_UP) != 0, (flags & IFF_LOOPBACK) == 0 {
                let name = String(cString: p.pointee.ifa_name)
                if name == "en0" {
                    var addr = sa.pointee
                    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(&addr, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                        let ip = String(cString: host)
                        let parts = ip.split(separator: ".")
                        if parts.count == 4, LocalOnly.isAllowed(ip) {
                            let gw = defaultGateway().flatMap { LocalOnly.isAllowed($0) ? $0 : nil }
                            return LocalNet(ip: ip, prefix: parts[0...2].joined(separator: "."), gateway: gw)
                        }
                    }
                }
            }
            ptr = p.pointee.ifa_next
        }
        return nil
    }

    /// The default route's gateway, read from the kernel's routing table, so the
    /// router is known rather than guessed: many homes use .1, not all of them.
    static func defaultGateway() -> String? {
        // net/route.h is not public on iOS, so the kernel constants are written out:
        // NET_RT_FLAGS 2, RTF_GATEWAY 0x2, RTA_DST 0x1, RTA_GATEWAY 0x2
        let rtfGateway: Int32 = 0x2, rtaDst: Int32 = 0x1, rtaGateway: Int32 = 0x2
        var mib: [Int32] = [CTL_NET, PF_ROUTE, 0, AF_INET, 2, rtfGateway]
        var len: Int = 0
        guard sysctl(&mib, UInt32(mib.count), nil, &len, nil, 0) == 0, len > 0 else { return nil }
        var buf = [UInt8](repeating: 0, count: len)
        guard sysctl(&mib, UInt32(mib.count), &buf, &len, nil, 0) == 0 else { return nil }
        var offset = 0
        while offset + 4 <= len {
            let msglen = Int(buf[offset]) | (Int(buf[offset + 1]) << 8)
            guard msglen > 0 else { break }
            let flags = Int32(bitPattern: UInt32(buf[offset + 8]) | (UInt32(buf[offset + 9]) << 8) | (UInt32(buf[offset + 10]) << 16) | (UInt32(buf[offset + 11]) << 24))
            let addrs = Int32(bitPattern: UInt32(buf[offset + 12]) | (UInt32(buf[offset + 13]) << 8) | (UInt32(buf[offset + 14]) << 16) | (UInt32(buf[offset + 15]) << 24))
            // the sockaddrs follow the header; the header is 96 bytes on this kernel,
            // and the first sockaddr announces itself with its length and family
            var p = offset + 96
            if !(p + 2 <= len && buf[p] == 16 && buf[p + 1] == UInt8(AF_INET)) { p = offset + 92 }
            if (flags & rtfGateway) != 0, (addrs & rtaDst) != 0, (addrs & rtaGateway) != 0,
               p + 16 <= len, buf[p] == 16, buf[p + 1] == UInt8(AF_INET) {
                let dstZero = buf[p + 4] == 0 && buf[p + 5] == 0 && buf[p + 6] == 0 && buf[p + 7] == 0
                let g = p + 16
                if dstZero, g + 8 <= len, buf[g + 1] == UInt8(AF_INET) {
                    return "\(buf[g + 4]).\(buf[g + 5]).\(buf[g + 6]).\(buf[g + 7])"
                }
            }
            offset += msglen
        }
        return nil
    }

    static func ipLess(_ a: String, _ b: String) -> Bool {
        func n(_ s: String) -> Int { Int(s.split(separator: ".").last ?? "0") ?? 0 }
        return n(a) < n(b)
    }
}

// MARK: - What a device is

enum DeviceKind: String { case router, camera, tv, nas, computer, phone, printer, iot, unknown }

/// A best guess at what a device is, from its ports and the names it gives
/// itself. Only a guess, so it never changes a verdict: it picks the picture and the label.
enum DeviceKinds {
    private static let cameraPorts: Set<Int> = [554, 8554, 34567, 37777, 2020, 8899]

    static func guess(ports: Set<Int>, isGateway: Bool, cameraVendor: Bool, hint: String) -> DeviceKind {
        let text = hint.lowercased()
        func has(_ words: String...) -> Bool { words.contains { text.contains($0) } }
        if isGateway || has("internetgatewaydevice", "router") { return .router }
        if has("_companion-link", "_rdlink") && !has("macbook", "imac", "mac mini") { return .phone }
        if has("_airplay", "_raop", "_googlecast", "apple tv") { return .tv }
        if has("_ipp", "_printer") { return .printer }
        if has("_smb", "_ssh", "_workstation", "macbook", "imac", "desktop-", "laptop") { return .computer }
        if has("_hap", "_sonos", "_spotify") { return .iot }
        if cameraVendor || !ports.isDisjoint(with: cameraPorts) || has("camera", "ipcam", "nvr", "dvr") { return .camera }
        if !ports.isDisjoint(with: [9100, 631, 515]) || has("printer", "laserjet", "deskjet") { return .printer }
        if !ports.isDisjoint(with: [8008, 8009, 7000, 5555]) || has("mediarenderer", "chromecast", "roku", " tv") { return .tv }
        if ports.contains(548) || has("synology", "diskstation", "qnap", " nas") { return .nas }
        if ports.contains(62078) { return .phone }
        if !ports.isDisjoint(with: [22, 3389, 445, 139]) { return .computer }
        return .unknown
    }

    static func label(_ kind: DeviceKind, _ ar: Bool) -> String {
        switch kind {
        case .router: return ar ? "الراوتر" : "Router"
        case .camera: return ar ? "كاميرا" : "Camera"
        case .tv: return ar ? "تلفاز أو جهاز بث" : "TV or streaming box"
        case .nas: return ar ? "جهاز تخزين" : "Storage"
        case .computer: return ar ? "حاسوب" : "Computer"
        case .phone: return ar ? "هاتف أو جهاز لوحي" : "Phone or tablet"
        case .printer: return ar ? "طابعة" : "Printer"
        case .iot: return ar ? "جهاز منزلي ذكي" : "Smart home device"
        case .unknown: return ar ? "جهاز" : "Device"
        }
    }
}

// MARK: - What the phone remembers, on the phone only

/// The devices Soor has seen on each network and the findings of the last scan,
/// so it can say what is new and what changed. Kept in the app's own defaults,
/// never sent anywhere, and erased from About.
enum KnownDevices {
    private static var defaults: UserDefaults { .standard }

    static func networkKey(_ net: LocalNet) -> String { "net:" + (net.gateway ?? net.prefix) }

    static func seen(_ network: String) -> Set<String>? {
        (defaults.array(forKey: "devices." + network) as? [String]).map { Set($0) }
    }

    static func remember(_ network: String, _ ids: Set<String>) {
        defaults.set(Array((seen(network) ?? []).union(ids)), forKey: "devices." + network)
    }

    static func lastFindings(_ network: String) -> Set<String>? {
        (defaults.array(forKey: "findings." + network) as? [String]).map { Set($0) }
    }

    static func rememberFindings(_ network: String, _ sigs: Set<String>) {
        defaults.set(Array(sigs), forKey: "findings." + network)
    }

    static func signature(_ f: Finding) -> String { "\(f.kind)|\(f.host)|\(f.port)" }

    static func forget() {
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("devices.") || key.hasPrefix("findings.") {
            defaults.removeObject(forKey: key)
        }
    }
}

// MARK: - The names devices give themselves

/// Bonjour names, which Apple devices, printers, TVs and speakers announce.
/// Browsing is what iOS allows without special entitlement, and each type
/// browsed is declared in Info.plist. Must be used from the main thread.
final class Bonjour: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    static let types = ["_companion-link._tcp.", "_rdlink._tcp.", "_airplay._tcp.", "_raop._tcp.", "_googlecast._tcp.",
                        "_smb._tcp.", "_ssh._tcp.", "_workstation._tcp.", "_ipp._tcp.", "_printer._tcp.",
                        "_http._tcp.", "_hap._tcp.", "_spotify-connect._tcp.", "_sonos._tcp."]

    struct Found { let name: String; let service: String }

    private var browsers: [NetServiceBrowser] = []
    private var services: [NetService] = []
    private(set) var found: [String: Found] = [:]

    func start() {
        for t in Bonjour.types {
            let b = NetServiceBrowser()
            b.delegate = self
            b.searchForServices(ofType: t, inDomain: "local.")
            browsers.append(b)
        }
    }

    func stop() {
        browsers.forEach { $0.stop() }
        services.forEach { $0.stop() }
        browsers = []
    }

    static func clean(_ raw: String) -> String {
        var s = raw.replacingOccurrences(of: "\\032", with: " ").replacingOccurrences(of: "\\.", with: ".")
        if let r = s.range(of: #"\s*\(\d+\)$"#, options: .regularExpression) { s.removeSubrange(r) }
        return String(s.trimmingCharacters(in: .whitespaces).prefix(40))
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        service.delegate = self
        services.append(service)
        service.resolve(withTimeout: 3)
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        var fn: String? = nil
        if let txt = sender.txtRecordData() {
            let dict = NetService.dictionary(fromTXTRecord: txt)
            if let d = dict["fn"] { fn = String(data: d, encoding: .utf8) }
        }
        let name = Bonjour.clean(fn ?? sender.name)
        guard !name.isEmpty else { return }
        for data in sender.addresses ?? [] {
            let ip: String? = data.withUnsafeBytes { raw -> String? in
                guard let base = raw.baseAddress, raw.count >= MemoryLayout<sockaddr_in>.size else { return nil }
                let sa = base.assumingMemoryBound(to: sockaddr.self).pointee
                guard sa.sa_family == UInt8(AF_INET) else { return nil }
                var sin = base.assumingMemoryBound(to: sockaddr_in.self).pointee
                var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                guard inet_ntop(AF_INET, &sin.sin_addr, &buf, socklen_t(INET_ADDRSTRLEN)) != nil else { return nil }
                return String(cString: buf)
            }
            if let ip = ip, LocalOnly.isAllowed(ip), found[ip] == nil {
                found[ip] = Found(name: name, service: sender.type)
            }
        }
    }
}

/// The computer name a Windows PC gives itself, asked on port 137 and told nothing.
enum NetBIOS {
    private static func query() -> Data {
        var q = [UInt8](repeating: 0, count: 50)
        q[0] = 0x13; q[1] = 0x37; q[5] = 1; q[12] = 0x20
        q[13] = UInt8(ascii: "C"); q[14] = UInt8(ascii: "K")
        for k in 15..<45 { q[k] = UInt8(ascii: "A") }
        q[47] = 0x21; q[49] = 1
        return Data(q)
    }

    static func name(of ip: String, queue: DispatchQueue, completion: @escaping (String?) -> Void) {
        guard LocalOnly.isAllowed(ip), let port = NWEndpoint.Port(rawValue: 137) else { completion(nil); return }
        let conn = NWConnection(host: NWEndpoint.Host(ip), port: port, using: .udp)
        let lock = DispatchQueue(label: "soor.nb")
        var finished = false
        let finish: (String?) -> Void = { v in
            lock.async {
                if finished { return }
                finished = true
                conn.cancel()
                completion(v)
            }
        }
        conn.stateUpdateHandler = { state in
            switch state {
            case .ready:
                conn.send(content: query(), completion: .contentProcessed { _ in })
                conn.receiveMessage { data, _, _, _ in finish(data.flatMap(parse)) }
            case .failed, .waiting: finish(nil)
            default: break
            }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 0.8) { finish(nil) }
    }

    private static func parse(_ d: Data) -> String? {
        let b = [UInt8](d)
        let n = b.count
        var p = 12
        guard n > p else { return nil }
        p += (b[p] & 0xC0) == 0xC0 ? 2 : 34
        p += 8
        p += 2
        guard p < n else { return nil }
        let count = Int(b[p]); p += 1
        for i in 0..<count {
            let at = p + i * 18
            guard at + 18 <= n else { break }
            let suffix = b[at + 15]
            let group = (b[at + 16] & 0x80) != 0
            let raw = String(bytes: b[at..<(at + 15)], encoding: .isoLatin1) ?? ""
            let name = raw.trimmingCharacters(in: .whitespaces)
            if !group, suffix == 0, !name.isEmpty, name.unicodeScalars.allSatisfy({ (33...126).contains($0.value) }) { return name }
        }
        return nil
    }
}


// MARK: - The network itself

/// One check of the network itself, beyond any single device. Read from what the
/// phone already knows and from the router's own answer; nothing is sent to find
/// these out. Judged here, in both languages, because they are about the network
/// rather than a host and port.
struct NetCheck: Identifiable {
    let id: String
    let severity: Severity?
    let titleAr: String
    let titleEn: String
    let detailAr: String
    let detailEn: String
    var linkAr: String? = nil
    var linkEn: String? = nil
    var link: String? = nil
}

enum NetworkChecks {
    static func run(externalIp: String?) -> [NetCheck] {
        var out: [NetCheck] = []
        if hasPublicIPv6() {
            out.append(NetCheck(id: "ipv6", severity: .low,
                titleAr: "شبكتك تعمل بعناوين IPv6 عامة", titleEn: "Your network has public IPv6 addresses",
                detailAr: "مع IPv6 قد يصل الإنترنت إلى الجهاز مباشرة دون المرور بجدول الراوتر الذي يفحصه سُور، فتأكد أن جدار حماية IPv6 مفعّل في الراوتر.",
                detailEn: "With IPv6 the internet can reach a device directly, without the router's port table that Soor reads, so make sure the router's IPv6 firewall is on."))
        }
        if let ip = externalIp { out.append(external(ip)) }
        return out
    }

    /// A public IPv6 address on the Wi-Fi interface, which bypasses the router's port table.
    static func hasPublicIPv6() -> Bool {
        var ifaddr: UnsafeMutablePointer<ifaddrs>? = nil
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return false }
        defer { freeifaddrs(ifaddr) }
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            if let sa = p.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET6),
               String(cString: p.pointee.ifa_name) == "en0" {
                let sin6 = UnsafeRawPointer(sa).assumingMemoryBound(to: sockaddr_in6.self).pointee
                let b = withUnsafeBytes(of: sin6.sin6_addr) { Array($0) }
                if b.count == 16 {
                    let linkLocal = b[0] == 0xFE && (b[1] & 0xC0) == 0x80
                    let unique = (b[0] & 0xFE) == 0xFC
                    let global = (b[0] & 0xE0) == 0x20
                    if global && !linkLocal && !unique { return true }
                }
            }
            ptr = p.pointee.ifa_next
        }
        return false
    }

    /// The address the world sees, as the router itself reports it.
    static func external(_ ip: String) -> NetCheck {
        let p = ip.split(separator: ".").compactMap { Int($0) }
        let cgnat = p.count == 4 && p[0] == 100 && (64...127).contains(p[1])
        if cgnat {
            return NetCheck(id: "wan", severity: nil,
                titleAr: "بيتك خلف CGNAT", titleEn: "Your home is behind CGNAT",
                detailAr: "عنوان راوترك على الإنترنت (\(ip)) مشترك عند مزوّد الخدمة، فلا يصل أحد من الإنترنت إلى راوترك مباشرة، ومعه لا تعمل المنافذ الممرَّرة أصلًا.",
                detailEn: "Your router's internet address (\(ip)) is shared at the provider, so nobody on the internet reaches your router directly, and forwarded ports do not work anyway.")
        }
        if LocalOnly.isAllowed(ip) {
            return NetCheck(id: "wan", severity: nil,
                titleAr: "راوترك خلف راوتر آخر", titleEn: "Your router sits behind another router",
                detailAr: "الراوتر يرى أمامه عنوانًا داخليًا (\(ip))، فالإنترنت لا يصل إليه مباشرة، وجدول المنافذ الذي يهم هو جدول الراوتر الخارجي.",
                detailEn: "The router sees a private address in front of it (\(ip)), so the internet does not reach it directly; the port table that matters is the outer router's.")
        }
        return NetCheck(id: "wan", severity: .info,
            titleAr: "عنوان بيتك على الإنترنت", titleEn: "Your home's address on the internet",
            detailAr: "\(ip) هو العنوان الذي يراك به العالم، ومحركات الفحص مثل Shodan تفهرس ما يظهر عليه للإنترنت، فانظر ماذا تعرف عنه.",
            detailEn: "\(ip) is the address the world sees you at, and scanning engines such as Shodan index whatever it shows to the internet, so see what they know about it.",
            linkAr: "ماذا يرى Shodan عن عنوانك", linkEn: "What Shodan sees at your address", link: "https://www.shodan.io/host/\(ip)")
    }
}
