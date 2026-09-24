import Foundation
import Network

// Reads the router's UPnP Internet Gateway Device port-forward table, so Soor can
// tell a device forwarded to the internet from a safe local one. This is the
// core of the camera-on-Shodan check: a camera is at risk when its port is in
// this table. It is a read, using the router's own published UPnP service.
//
// It works in two steps: SSDP multicast discovery to find the IGD control URL on
// the local network, then SOAP calls to GetGenericPortMappingEntry to walk the
// forwarded ports. Everything stays on the LAN.

final class UPnPClient {

    struct PortMapping {
        let externalPort: Int
        let internalPort: Int
        let internalClient: String
        let proto: String
    }

    private let queue = DispatchQueue(label: "soor.upnp")

    /// Returns the set of internal ports that the router has forwarded to the
    /// internet. Empty when UPnP is off or unreachable (which is itself a good
    /// sign). Also reports whether UPnP was found enabled at all.
    func exposedPorts(completion: @escaping (_ ports: Set<Int>, _ upnpEnabled: Bool, _ mappings: [PortMapping]) -> Void) {
        discoverControlURL { controlURL, serviceType in
            guard let controlURL = controlURL, let serviceType = serviceType else {
                completion([], false, []); return
            }
            self.walkMappings(controlURL: controlURL, serviceType: serviceType) { mappings in
                let ports = Set(mappings.map { $0.internalPort })
                completion(ports, true, mappings)
            }
        }
    }

    // MARK: - SSDP discovery

    private func discoverControlURL(completion: @escaping (URL?, String?) -> Void) {
        let ssdpAddress = "239.255.255.250"
        let ssdpPort: UInt16 = 1900
        let search = """
        M-SEARCH * HTTP/1.1\r
        HOST: \(ssdpAddress):\(ssdpPort)\r
        MAN: "ssdp:discover"\r
        MX: 2\r
        ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r
        \r

        """

        guard let port = NWEndpoint.Port(rawValue: ssdpPort) else { completion(nil, nil); return }
        let conn = NWConnection(host: NWEndpoint.Host(ssdpAddress), port: port, using: .udp)
        var finished = false
        let finish: (URL?, String?) -> Void = { url, st in
            if finished { return }
            finished = true
            conn.cancel()
            completion(url, st)
        }

        conn.stateUpdateHandler = { state in
            if case .ready = state {
                conn.send(content: search.data(using: .utf8), completion: .contentProcessed { _ in })
                conn.receiveMessage { data, _, _, _ in
                    guard let data = data, let text = String(data: data, encoding: .utf8),
                          let loc = self.header(text, "LOCATION") ?? self.header(text, "Location"),
                          let descURL = URL(string: loc) else { finish(nil, nil); return }
                    self.fetchDescription(descURL) { control, st in finish(control, st) }
                }
            } else if case .failed = state {
                finish(nil, nil)
            }
        }
        conn.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 3.0) { finish(nil, nil) }
    }

    // MARK: - Device description

    private func fetchDescription(_ url: URL, completion: @escaping (URL?, String?) -> Void) {
        var req = URLRequest(url: url)
        req.timeoutInterval = 4
        URLSession.shared.dataTask(with: req) { data, _, _ in
            guard let data = data, let xml = String(data: data, encoding: .utf8) else {
                completion(nil, nil); return
            }
            // Prefer WANIPConnection, fall back to WANPPPConnection.
            let serviceTypes = ["urn:schemas-upnp-org:service:WANIPConnection:1",
                                "urn:schemas-upnp-org:service:WANPPPConnection:1"]
            for st in serviceTypes {
                if let controlPath = self.controlURLForService(xml, serviceType: st) {
                    let base = URL(string: "/", relativeTo: url)?.baseURL ?? url
                    let control = URL(string: controlPath, relativeTo: base) ??
                                  URL(string: controlPath, relativeTo: url)
                    completion(control, st)
                    return
                }
            }
            completion(nil, nil)
        }.resume()
    }

    // Pull the controlURL that sits in the same <service> block as serviceType.
    private func controlURLForService(_ xml: String, serviceType: String) -> String? {
        guard let range = xml.range(of: serviceType) else { return nil }
        // search a window around the serviceType for the nearest controlURL
        let after = xml[range.upperBound...]
        if let c = after.range(of: "<controlURL>"),
           let e = after.range(of: "</controlURL>", range: c.upperBound..<after.endIndex) {
            return String(after[c.upperBound..<e.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return nil
    }

    // MARK: - Walk port mappings

    private func walkMappings(controlURL: URL, serviceType: String,
                              completion: @escaping ([PortMapping]) -> Void) {
        var mappings: [PortMapping] = []
        func step(_ index: Int) {
            if index > 60 { completion(mappings); return }   // safety bound
            getMapping(controlURL: controlURL, serviceType: serviceType, index: index) { mapping in
                if let m = mapping {
                    mappings.append(m)
                    step(index + 1)
                } else {
                    completion(mappings)   // no more entries
                }
            }
        }
        step(0)
    }

    private func getMapping(controlURL: URL, serviceType: String, index: Int,
                            completion: @escaping (PortMapping?) -> Void) {
        let body = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
        <s:Body>
        <u:GetGenericPortMappingEntry xmlns:u="\(serviceType)">
        <NewPortMappingIndex>\(index)</NewPortMappingIndex>
        </u:GetGenericPortMappingEntry>
        </s:Body>
        </s:Envelope>
        """
        var req = URLRequest(url: controlURL)
        req.httpMethod = "POST"
        req.timeoutInterval = 4
        req.setValue("text/xml; charset=\"utf-8\"", forHTTPHeaderField: "Content-Type")
        req.setValue("\"\(serviceType)#GetGenericPortMappingEntry\"", forHTTPHeaderField: "SOAPAction")
        req.httpBody = body.data(using: .utf8)

        URLSession.shared.dataTask(with: req) { data, resp, _ in
            guard let data = data, let xml = String(data: data, encoding: .utf8),
                  (resp as? HTTPURLResponse)?.statusCode == 200 else {
                completion(nil); return
            }
            let ext = self.tag(xml, "NewExternalPort")
            let intp = self.tag(xml, "NewInternalPort")
            let client = self.tag(xml, "NewInternalClient")
            let proto = self.tag(xml, "NewProtocol")
            if let e = ext.flatMap({ Int($0) }), let i = intp.flatMap({ Int($0) }) {
                completion(PortMapping(externalPort: e, internalPort: i,
                                       internalClient: client ?? "", proto: proto ?? "TCP"))
            } else {
                completion(nil)
            }
        }.resume()
    }

    // MARK: - Tiny parsers

    private func header(_ text: String, _ name: String) -> String? {
        for line in text.split(separator: "\r\n") {
            if line.uppercased().hasPrefix(name.uppercased() + ":") {
                return String(line.drop(while: { $0 != ":" }).dropFirst()).trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    private func tag(_ xml: String, _ name: String) -> String? {
        guard let o = xml.range(of: "<\(name)>"),
              let c = xml.range(of: "</\(name)>", range: o.upperBound..<xml.endIndex) else { return nil }
        return String(xml[o.upperBound..<c.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
