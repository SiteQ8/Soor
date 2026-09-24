import Foundation
import Network

// The real scanner. It runs entirely on the device and only ever touches the
// local network the phone is on. It does three honest things:
//   1. Finds the phone's own IPv4 address and subnet, then sweeps that /24 for
//      hosts that answer a TCP connection on any common port.
//   2. For each live host, probes a set of common service ports and reads the
//      banner the service volunteers (HTTP Server header and title, RTSP line,
//      raw TCP banner). It never sends a password and never tries a login.
//   3. Asks the router for its UPnP IGD port-forward list, so a device forwarded
//      to the internet can be told apart from a safe local one.
// It exploits nothing. It knocks on doors and reads what is announced.

struct ScanProgress {
    var phase: String
    var scanned: Int
    var total: Int
    var found: Int
}

final class NetworkScanner {

    // Ports worth probing on a home network, chosen to line up with the
    // knowledge base. Kept small so a sweep stays quick and polite.
    static let commonPorts: [Int] = [21, 22, 23, 80, 81, 443, 139, 445, 554, 1900,
                                     2020, 2323, 3389, 5555, 8000, 8080, 8081, 8443,
                                     8554, 8899, 9000, 34567]

    // A quick liveness set: if a host answers any of these, it is up.
    static let livenessPorts: [Int] = [80, 443, 8080, 554, 22, 445, 5555]

    private let queue = DispatchQueue(label: "soor.scan", attributes: .concurrent)
    private let connectTimeout: TimeInterval = 1.2
    private let bannerTimeout: TimeInterval = 1.5

    var onProgress: ((ScanProgress) -> Void)?

    // MARK: - Public entry

    /// Runs a full scan and returns the observations for the engine.
    func scan(exposedPorts: Set<Int>, completion: @escaping ([Observation]) -> Void) {
        guard let (localIP, prefix) = Self.localIPv4AndPrefix() else {
            completion([]); return
        }
        report("discovering", 0, 254, 0)

        discoverHosts(prefix: prefix, selfIP: localIP) { [weak self] hosts in
            guard let self = self else { return }
            var all: [Observation] = []
            let group = DispatchGroup()
            var done = 0
            let lock = NSLock()

            for host in hosts {
                group.enter()
                self.probeHost(host, exposedPorts: exposedPorts) { obs in
                    lock.lock()
                    all.append(contentsOf: obs)
                    done += 1
                    self.report("probing", done, hosts.count, hosts.count)
                    lock.unlock()
                    group.leave()
                }
            }
            group.notify(queue: .main) { completion(all) }
        }
    }

    private func report(_ phase: String, _ scanned: Int, _ total: Int, _ found: Int) {
        DispatchQueue.main.async {
            self.onProgress?(ScanProgress(phase: phase, scanned: scanned, total: total, found: found))
        }
    }

    // MARK: - Host discovery

    private func discoverHosts(prefix: String, selfIP: String,
                               completion: @escaping ([String]) -> Void) {
        var live: [String] = [selfIP]   // the phone itself is on the network
        let lock = NSLock()
        let group = DispatchGroup()
        var checked = 0

        for i in 1...254 {
            let ip = "\(prefix).\(i)"
            group.enter()
            isHostUp(ip) { up in
                lock.lock()
                checked += 1
                if up && ip != selfIP { live.append(ip) }
                self.report("discovering", checked, 254, live.count)
                lock.unlock()
                group.leave()
            }
        }
        group.notify(queue: self.queue) {
            completion(Array(Set(live)).sorted(by: Self.ipLess))
        }
    }

    private func isHostUp(_ ip: String, completion: @escaping (Bool) -> Void) {
        let group = DispatchGroup()
        var up = false
        let lock = NSLock()
        for port in Self.livenessPorts {
            group.enter()
            tryConnect(ip: ip, port: port, timeout: 0.8) { ok in
                if ok { lock.lock(); up = true; lock.unlock() }
                group.leave()
            }
        }
        group.notify(queue: queue) { completion(up) }
    }

    // MARK: - Port probing

    private func probeHost(_ ip: String, exposedPorts: Set<Int>,
                           completion: @escaping ([Observation]) -> Void) {
        var obs: [Observation] = []
        let lock = NSLock()
        let group = DispatchGroup()

        for port in Self.commonPorts {
            group.enter()
            tryConnect(ip: ip, port: port, timeout: connectTimeout) { [weak self] open in
                guard let self = self, open else { group.leave(); return }
                self.readBanner(ip: ip, port: port) { banner, noAuth in
                    var o = Observation(host: ip, port: port)
                    o.internetExposed = exposedPorts.contains(port)
                    o.noAuth = noAuth
                    self.assignBanner(&o, port: port, banner: banner)
                    lock.lock(); obs.append(o); lock.unlock()
                    group.leave()
                }
            }
        }
        group.notify(queue: queue) { completion(obs) }
    }

    // Route the banner into the field the engine expects for that port.
    private func assignBanner(_ o: inout Observation, port: Int, banner: String) {
        guard !banner.isEmpty else { return }
        if port == 554 || port == 8554 {
            o.rtspServer = extractHeader(banner, "Server") ?? banner
        } else if [80, 81, 443, 8080, 8081, 8443, 8000].contains(port) {
            o.httpServer = extractHeader(banner, "Server")
            o.httpTitle = extractTitle(banner)
            if banner.lowercased().contains("self-signed") { o.banner = "self-signed certificate" }
            o.raw = banner
        } else {
            o.banner = banner
        }
    }

    // MARK: - TCP connect

    private func tryConnect(ip: String, port: Int, timeout: TimeInterval,
                            completion: @escaping (Bool) -> Void) {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { completion(false); return }
        let host = NWEndpoint.Host(ip)
        let params = NWParameters.tcp
        params.prohibitedInterfaceTypes = [.cellular]   // local network only
        let conn = NWConnection(host: host, port: nwPort, using: params)
        var finished = false
        let finish: (Bool) -> Void = { ok in
            if finished { return }
            finished = true
            conn.cancel()
            completion(ok)
        }
        conn.stateUpdateHandler = { state in
            switch state {
            case .ready: finish(true)
            case .failed, .cancelled: finish(false)
            default: break
            }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + timeout) { finish(false) }
    }

    // MARK: - Banner read

    private func readBanner(ip: String, port: Int,
                            completion: @escaping (String, Bool) -> Void) {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { completion("", false); return }
        let params: NWParameters
        if port == 443 || port == 8443 {
            params = NWParameters.tls   // let the stack complete the handshake
        } else {
            params = NWParameters.tcp
        }
        params.prohibitedInterfaceTypes = [.cellular]
        let conn = NWConnection(host: NWEndpoint.Host(ip), port: nwPort, using: params)
        var finished = false
        let finish: (String, Bool) -> Void = { banner, noAuth in
            if finished { return }
            finished = true
            conn.cancel()
            completion(banner, noAuth)
        }

        conn.stateUpdateHandler = { state in
            switch state {
            case .ready:
                // For HTTP-ish ports, send a bare request so the server replies.
                let httpPorts: Set<Int> = [80, 81, 8080, 8081, 8000, 443, 8443]
                if httpPorts.contains(port) {
                    let req = "GET / HTTP/1.0\r\nHost: \(ip)\r\nUser-Agent: Soor\r\n\r\n"
                    conn.send(content: req.data(using: .utf8), completion: .contentProcessed { _ in })
                } else if port == 554 || port == 8554 {
                    let req = "OPTIONS rtsp://\(ip) RTSP/1.0\r\nCSeq: 1\r\n\r\n"
                    conn.send(content: req.data(using: .utf8), completion: .contentProcessed { _ in })
                }
                conn.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, _, _ in
                    let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
                    let noAuth = Self.looksUnauthenticated(text, port: port)
                    finish(text, noAuth)
                }
            case .failed, .cancelled:
                finish("", false)
            default: break
            }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + bannerTimeout) { finish("", false) }
    }

    // A service "answers with no password" when it returns content without a 401
    // and without a WWW-Authenticate challenge, or an RTSP stream that does not
    // demand authentication. This is read from the reply, never from a login try.
    private static func looksUnauthenticated(_ text: String, port: Int) -> Bool {
        let t = text.lowercased()
        if t.isEmpty { return false }
        if port == 554 || port == 8554 {
            return t.contains("rtsp/1.0 200") && !t.contains("www-authenticate")
        }
        if t.hasPrefix("http/") {
            let unauthorized = t.contains(" 401") || t.contains("www-authenticate")
            let ok = t.contains(" 200") || t.contains(" 302") || t.contains(" 301")
            return ok && !unauthorized
        }
        // Raw banners such as ADB/telnet: content with no obvious auth prompt.
        return !t.contains("password") && (port == 5555 || port == 445)
    }

    // MARK: - Header parsing

    private func extractHeader(_ text: String, _ name: String) -> String? {
        for line in text.split(separator: "\n") {
            let l = line.trimmingCharacters(in: .whitespaces)
            if l.lowercased().hasPrefix(name.lowercased() + ":") {
                return String(l.dropFirst(name.count + 1)).trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    private func extractTitle(_ text: String) -> String? {
        guard let r = text.range(of: "<title>", options: .caseInsensitive),
              let e = text.range(of: "</title>", options: .caseInsensitive, range: r.upperBound..<text.endIndex)
        else { return nil }
        return String(text[r.upperBound..<e.lowerBound]).trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Local address

    static func localIPv4AndPrefix() -> (ip: String, prefix: String)? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            let flags = Int32(p.pointee.ifa_flags)
            let addr = p.pointee.ifa_addr.pointee
            if (flags & (IFF_UP | IFF_RUNNING)) == (IFF_UP | IFF_RUNNING),
               addr.sa_family == UInt8(AF_INET) {
                let name = String(cString: p.pointee.ifa_name)
                if name == "en0" || name.hasPrefix("en") {
                    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    getnameinfo(p.pointee.ifa_addr, socklen_t(p.pointee.ifa_addr.pointee.sa_len),
                                &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
                    address = String(cString: host)
                }
            }
            ptr = p.pointee.ifa_next
        }
        guard let ip = address else { return nil }
        let parts = ip.split(separator: ".")
        guard parts.count == 4 else { return nil }
        return (ip, "\(parts[0]).\(parts[1]).\(parts[2])")
    }

    static func ipLess(_ a: String, _ b: String) -> Bool {
        func n(_ s: String) -> Int { Int(s.split(separator: ".").last ?? "0") ?? 0 }
        return n(a) < n(b)
    }
}
