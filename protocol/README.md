# Seam protocol

The Protobuf schema shared by the Mac app, the Android app and the server, managed with buf. A plaintext `Envelope` carries routing fields and an HPKE-sealed `Payload`, which has one `oneof` case per feature. Code is generated for Swift (swift-protobuf), Kotlin (protobuf-kotlin lite) and TypeScript (@bufbuild/protobuf). `testdata/` holds golden vectors that every platform decodes in its tests. Never reuse or renumber a field; `buf breaking` enforces this in CI.
