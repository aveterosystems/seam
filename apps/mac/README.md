# Seam for Mac

The Mac app, in Swift 6 and SwiftUI for macOS 14+. It's a menu bar app with a main window (Messages, Files, Notifications), Settings and a Share extension; a Finder File Provider comes later. The Xcode project is generated from `project.yml` with XcodeGen, so no `.xcodeproj` is committed. Shared code lives in local Swift packages under `Packages/`: `SeamProtocol`, `SeamCrypto`, `SeamTransport`, `SeamCore`, `SeamStorage`, `SeamDesign`, and one package per feature under `Packages/Features/`.
