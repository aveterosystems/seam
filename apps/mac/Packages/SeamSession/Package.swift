// swift-tools-version: 6.0
// SPDX-License-Identifier: AGPL-3.0-only
import PackageDescription

// The Mac's relay connection and pairing QR code: plain Swift, testable without a network.
let package = Package(
    name: "SeamSession",
    platforms: [.macOS(.v14)],
    products: [.library(name: "SeamSession", targets: ["SeamSession"])],
    dependencies: [
        .package(path: "../SeamCore"),
        .package(path: "../SeamProtocol"),
    ],
    targets: [
        .target(
            name: "SeamSession",
            dependencies: [
                .product(name: "SeamCore", package: "SeamCore"),
                .product(name: "SeamProtocol", package: "SeamProtocol"),
            ]
        ),
        .testTarget(name: "SeamSessionTests", dependencies: ["SeamSession"]),
    ]
)
