import XCTest
@testable import SoorEngine

// Runs the Swift engine against the SAME vectors.json the JS and Kotlin engines
// use. A finding that differs here from the shared expectation fails the build,
// which is how Soor guarantees a finding is identical on every platform.

final class SoorEngineTests: XCTestCase {

    struct ExpectedFinding: Decodable {
        let kind: String
        let severity: String
        let service: String?
        let vendor: String?
        let exposed: Bool?
    }
    struct Case: Decodable {
        let name: String
        let observation: ObservationJSON
        let expect: ExpectedFinding
    }
    struct ObservationJSON: Decodable {
        var host: String
        var port: Int
        var proto: String?
        var httpServer: String?
        var httpTitle: String?
        var rtspServer: String?
        var banner: String?
        var raw: String?
        var noAuth: Bool?
        var internetExposed: Bool?
        var deviceName: String?
        var isNew: Bool?

        func toObservation() -> Observation {
            var o = Observation(host: host, port: port)
            o.proto = proto ?? "tcp"
            o.httpServer = httpServer
            o.httpTitle = httpTitle
            o.rtspServer = rtspServer
            o.banner = banner
            o.raw = raw
            o.noAuth = noAuth ?? false
            o.internetExposed = internetExposed ?? false
            o.deviceName = deviceName
            o.isNew = isNew ?? false
            return o
        }
    }
    struct VectorsFile: Decodable { let cases: [Case] }

    func load<T: Decodable>(_ name: String, _ type: T.Type) throws -> T {
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json"),
                                "missing fixture \(name).json")
        let data = try Data(contentsOf: url)
        return try JSONDecoder().decode(T.self, from: data)
    }

    func testVectors() throws {
        let knowledge = Knowledge.load(bundle: .module)
        XCTAssertFalse(knowledge.services.isEmpty, "services knowledge did not load")
        XCTAssertFalse(knowledge.cameras.isEmpty, "cameras knowledge did not load")

        let vectors = try load("vectors", VectorsFile.self)
        for c in vectors.cases {
            let findings = SoorEngine.analyse(observations: [c.observation.toObservation()],
                                              services: knowledge.services,
                                              cameras: knowledge.cameras)
            let host = c.observation.host
            let got = findings.first { $0.host == host }
            XCTAssertNotNil(got, "no finding for: \(c.name)")
            guard let got = got else { continue }
            XCTAssertEqual(got.kind, c.expect.kind, "kind mismatch: \(c.name)")
            XCTAssertEqual(got.severity.rawValue, c.expect.severity, "severity mismatch: \(c.name)")
            if let s = c.expect.service { XCTAssertEqual(got.service, s, "service mismatch: \(c.name)") }
            if let v = c.expect.vendor { XCTAssertEqual(got.vendor, v, "vendor mismatch: \(c.name)") }
            if let e = c.expect.exposed { XCTAssertEqual(got.exposed, e, "exposed mismatch: \(c.name)") }
        }
    }

    func testEmptyScan() {
        let k = Knowledge.load(bundle: .module)
        XCTAssertTrue(SoorEngine.analyse(observations: [], services: k.services, cameras: k.cameras).isEmpty)
    }

    func testCameraDedup() {
        let k = Knowledge.load(bundle: .module)
        var a = Observation(host: "192.168.1.64", port: 554); a.rtspServer = "Hipcam RealServer/V1.0"; a.noAuth = true; a.internetExposed = true
        var b = Observation(host: "192.168.1.64", port: 80); b.httpServer = "App-webs"; b.internetExposed = true
        let f = SoorEngine.analyse(observations: [a, b], services: k.services, cameras: k.cameras)
            .filter { $0.host == "192.168.1.64" && $0.vendor == "hikvision" }
        XCTAssertEqual(f.count, 1, "camera on two ports should collapse to one finding")
        XCTAssertEqual(f.first?.kind, "exposed-camera-default")
    }

    func testFirstBootVendorNotFlagged() {
        let k = Knowledge.load(bundle: .module)
        let axis = k.cameras.first { $0.id == "axis" }
        XCTAssertEqual(SoorEngine.shipsWeakCredentials(axis), false)
        let foscam = k.cameras.first { $0.id == "foscam" }
        XCTAssertEqual(SoorEngine.shipsWeakCredentials(foscam), true)
    }
}
