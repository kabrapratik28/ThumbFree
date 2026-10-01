// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "ThumbFreeKit",
    platforms: [.iOS(.v26), .macOS(.v26)],
    products: [
        .library(name: "TFCore", targets: ["TFCore"]),
        .library(name: "TFEngine", targets: ["TFEngine"]),
    ],
    targets: [
        .target(name: "TFCore"),
        .target(name: "TFEngine", dependencies: ["TFCore"]),
        .executableTarget(name: "tfreplay", dependencies: ["TFEngine", "TFCore"]),
        .executableTarget(name: "tfbench", dependencies: ["TFEngine", "TFCore"]),
        .testTarget(name: "TFCoreTests", dependencies: ["TFCore"]),
        .testTarget(name: "TFEngineTests", dependencies: ["TFEngine", "TFCore"]),
    ]
)
