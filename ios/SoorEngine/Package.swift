// swift-tools-version:5.9
import PackageDescription

// A small package so the engine and report renderer can be tested on a macOS
// runner against the shared vectors, proving the Swift port agrees with the JS
// and Kotlin engines. The iOS app compiles the same sources directly.
let package = Package(
    name: "SoorEngine",
    platforms: [.macOS(.v12)],
    targets: [
        .target(
            name: "SoorEngine",
            path: "Sources/SoorEngine"
        ),
        .testTarget(
            name: "SoorEngineTests",
            dependencies: ["SoorEngine"],
            path: "Tests/SoorEngineTests",
            resources: [
                .copy("vectors.json"),
                .copy("services.json"),
                .copy("cameras.json")
            ]
        )
    ]
)
