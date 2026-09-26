import Foundation
import Network

// Reads the router's forwarded-port table, the core of the exposure check: a
// device is visible from the internet when one of its ports is in this table.
//
// iOS lets an app send to a multicast group only with an entitlement Apple
// grants on request, so Soor asks the likely routers directly instead: a
// unicast M-SEARCH to each candidate on port 1900, then the description paths
// routers commonly serve. Every request stays inside the home network.

struct PortMapping {
    let externalPort: Int
    let internalPort: Int
    let internalClient: String
    let proto: String
}

struct UPnPResult {
    let upnpEnabled: Bool
    let mappings: [PortMapping]
    let gateway: String?

    /// "ip:port" for every port the router forwards to the internet, tied to its own device
    var exposed: Set<String> { Set(mappings.map { "\($0.internalClient):\($0.internalPort)" }) }
}

final class UPnPClient {
    private let queue = DispatchQueue(label: "soor.upnp")
    private static let serviceTypes = ["urn:schemas-upnp-org:service:WANIPConnection:1",
                                       "urn:schemas-upnp-org:service:WANPPPConnection:1"]
    private static let knownPaths: [(Int, String)] = [
        (1900, "/igd.xml"), (1900, "/rootDesc.xml"), (1900, "/gatedesc.xml"), (5000, "/rootDesc.xml"),
        (49152, "/rootDesc.xml"), (49153, "/rootDesc.xml"), (8200, "/rootDesc.xml"), (52869, "/gatedesc.xml"),
        (80, "/description.xml"), (1780, "/InternetGatewayDevice.xml"), (37215, "/upnpdev.xml"),
    ]

    func routerTable(candidates: [String], completion: @escaping (UPnPResult) -> Void) {
        let hosts = candidates.filter { LocalOnly.isAllowed($0) }
        searchUnicast(hosts, 0) { location in
            if let loc = location { self.finish(location: loc, completion: completion); return }
            let urls = hosts.flatMap { h in UPnPClient.knownPaths.compactMap { URL(string: "http://\(h):\($0.0)\($0.1)") } }
            self.probeKnown(urls, 0) { location in
                if let loc = location { self.finish(location: loc, completion: completion) }
                else { completion(UPnPResult(upnpEnabled: false, mappings: [], gateway: nil)) }
            }
        }
    }

    private func searchUnicast(_ hosts: [String], _ i: Int, _ completion: @escaping (String?) -> Void) {
        guard i < hosts.count else { completion(nil); return }
        msearch(host: hosts[i]) { loc in
            if let loc = loc { completion(loc) } else { self.searchUnicast(hosts, i + 1, completion) }
        }
    }

    private func msearch(host: String, completion: @escaping (String?) -> Void) {
        guard let port = NWEndpoint.Port(rawValue: 1900) else { completion(nil); return }
        let msg = "M-SEARCH * HTTP/1.1\r\nHOST: \(LocalOnly.ssdpGroup):1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
                  "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
        let conn = NWConnection(host: NWEndpoint.Host(host), port: port, using: .udp)
        var finished = false
        let finish: (String?) -> Void = { v in
            self.queue.async {
                if finished { return }
                finished = true
                conn.cancel()
                completion(v)
            }
        }
        conn.stateUpdateHandler = { state in
            switch state {
            case .ready:
                conn.send(content: msg.data(using: .utf8), completion: .contentProcessed { _ in })
                conn.receiveMessage { data, _, _, _ in
                    let text = data.flatMap { String(data: $0, encoding: .isoLatin1) } ?? ""
                    finish(self.header(text, "LOCATION"))
                }
            case .failed, .waiting:
                finish(nil)
            default:
                break
            }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 1.5) { finish(nil) }
    }

    private func probeKnown(_ urls: [URL], _ i: Int, _ completion: @escaping (String?) -> Void) {
        guard i < urls.count else { completion(nil); return }
        fetch(urls[i], timeout: 1.5) { body in
            if let b = body, b.contains("<controlURL>") { completion(urls[i].absoluteString) }
            else { self.probeKnown(urls, i + 1, completion) }
        }
    }

    private func finish(location: String, completion: @escaping (UPnPResult) -> Void) {
        guard let url = URL(string: location), let host = url.host, LocalOnly.isAllowed(host) else {
            completion(UPnPResult(upnpEnabled: false, mappings: [], gateway: nil)); return
        }
        fetch(url, timeout: 3) { xml in
            guard let xml = xml else { completion(UPnPResult(upnpEnabled: true, mappings: [], gateway: host)); return }
            var control: URL? = nil
            var service = ""
            for st in UPnPClient.serviceTypes {
                if let path = self.controlURL(xml, st), let abs = URL(string: path, relativeTo: url)?.absoluteURL {
                    control = abs; service = st; break
                }
            }
            guard let controlURL = control else { completion(UPnPResult(upnpEnabled: true, mappings: [], gateway: host)); return }
            self.walk(controlURL: controlURL, serviceType: service, index: 0, acc: []) { mappings in
                completion(UPnPResult(upnpEnabled: true, mappings: mappings, gateway: host))
            }
        }
    }

    private func walk(controlURL: URL, serviceType: String, index: Int, acc: [PortMapping], completion: @escaping ([PortMapping]) -> Void) {
        guard index < 60 else { completion(acc); return }
        getMapping(controlURL: controlURL, serviceType: serviceType, index: index) { m in
            guard let m = m else { completion(acc); return }
            self.walk(controlURL: controlURL, serviceType: serviceType, index: index + 1, acc: acc + [m], completion: completion)
        }
    }

    private func getMapping(controlURL: URL, serviceType: String, index: Int, completion: @escaping (PortMapping?) -> Void) {
        let body = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
        <s:Body><u:GetGenericPortMappingEntry xmlns:u="\(serviceType)">
        <NewPortMappingIndex>\(index)</NewPortMappingIndex>
        </u:GetGenericPortMappingEntry></s:Body></s:Envelope>
        """
        let headers = ["Content-Type": "text/xml; charset=\"utf-8\"",
                       "SOAPAction": "\"\(serviceType)#GetGenericPortMappingEntry\""]
        fetch(controlURL, timeout: 3, method: "POST", body: body, headers: headers) { xml in
            guard let xml = xml, let ext = self.tag(xml, "NewExternalPort").flatMap({ Int($0) }),
                  let int = self.tag(xml, "NewInternalPort").flatMap({ Int($0) }) else { completion(nil); return }
            completion(PortMapping(externalPort: ext, internalPort: int,
                                   internalClient: self.tag(xml, "NewInternalClient") ?? "",
                                   proto: self.tag(xml, "NewProtocol") ?? "TCP"))
        }
    }

    private func fetch(_ url: URL, timeout: TimeInterval, method: String = "GET", body: String? = nil,
                       headers: [String: String] = [:], completion: @escaping (String?) -> Void) {
        // the address came from a device's reply, so it is not trusted
        guard let host = url.host, LocalOnly.isAllowed(host) else { completion(nil); return }
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.httpMethod = method
        headers.forEach { req.setValue($1, forHTTPHeaderField: $0) }
        if let b = body { req.httpBody = b.data(using: .utf8) }
        URLSession.shared.dataTask(with: req) { data, _, _ in
            completion(data.flatMap { String(data: $0, encoding: .utf8) })
        }.resume()
    }

    private func controlURL(_ xml: String, _ serviceType: String) -> String? {
        guard let at = xml.range(of: serviceType) else { return nil }
        let after = xml[at.lowerBound...]
        guard let o = after.range(of: "<controlURL>"), let c = after.range(of: "</controlURL>", range: o.upperBound..<after.endIndex) else { return nil }
        return String(after[o.upperBound..<c.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func header(_ text: String, _ name: String) -> String? {
        for line in text.components(separatedBy: "\r\n") where line.uppercased().hasPrefix(name.uppercased() + ":") {
            let v = line.drop(while: { $0 != ":" }).dropFirst().trimmingCharacters(in: .whitespaces)
            return v.isEmpty ? nil : v
        }
        return nil
    }

    private func tag(_ xml: String, _ name: String) -> String? {
        guard let o = xml.range(of: "<\(name)>"), let c = xml.range(of: "</\(name)>", range: o.upperBound..<xml.endIndex) else { return nil }
        return String(xml[o.upperBound..<c.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
