// swift-tools-version: 6.0
import Foundation
import PackageDescription

// shortcut: links the Rust core's debug static library straight from core/target;
// switch to an XCFramework binaryTarget (universal, release) when the app target exists.
let rustLib = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()
    .appendingPathComponent("../../../../core/target/debug/libseam_ffi.a")
    .standardized.path

let package = Package(
    name: "SeamCore",
    platforms: [.macOS(.v14)],
    products: [.library(name: "SeamCore", targets: ["SeamCore"])],
    targets: [
        .systemLibrary(name: "SeamCoreFFI"),
        .target(
            name: "SeamCore",
            dependencies: ["SeamCoreFFI"],
            linkerSettings: [.unsafeFlags([rustLib])]
        ),
        .testTarget(name: "SeamCoreTests", dependencies: ["SeamCore"]),
    ]
)
