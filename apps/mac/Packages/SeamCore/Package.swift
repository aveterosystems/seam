// swift-tools-version: 6.0
// SPDX-License-Identifier: AGPL-3.0-only
import PackageDescription

// SeamCoreFFI.xcframework is the Rust core (core/seam-ffi), built by scripts/build-mac-core.sh.
// It's git-ignored: run that script once before building the package or the app.
let package = Package(
    name: "SeamCore",
    platforms: [.macOS(.v14)],
    products: [.library(name: "SeamCore", targets: ["SeamCore"])],
    targets: [
        .binaryTarget(name: "SeamCoreFFI", path: "SeamCoreFFI.xcframework"),
        .target(name: "SeamCore", dependencies: ["SeamCoreFFI"]),
        .testTarget(name: "SeamCoreTests", dependencies: ["SeamCore"]),
    ]
)
