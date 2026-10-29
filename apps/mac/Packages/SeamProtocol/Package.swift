// swift-tools-version: 6.0
// SPDX-License-Identifier: AGPL-3.0-only
import PackageDescription

// Generated from protocol/seam/v1 by scripts/gen-protocol.sh. The runtime version must match the
// protoc-gen-swift that generated the code (CI builds that exact tag; see .github/workflows/mac.yml).
let package = Package(
    name: "SeamProtocol",
    platforms: [.macOS(.v14)],
    products: [.library(name: "SeamProtocol", targets: ["SeamProtocol"])],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", exact: "1.39.0"),
    ],
    targets: [
        .target(
            name: "SeamProtocol",
            dependencies: [.product(name: "SwiftProtobuf", package: "swift-protobuf")]
        ),
        .testTarget(name: "SeamProtocolTests", dependencies: ["SeamProtocol"]),
    ]
)
