# PROTOTYPE, throwaway: async foreign traits through UniFFI 0.32

**Question:** can `seam-computer`'s OS interfaces (specs/computer-session/01-crate.md) be async traits defined in Rust, implemented in Swift, and called from the crate's own tokio runtime?

**Verdict: yes.** All checks pass, on the real `NSPasteboard`, Swift 6.4, UniFFI 0.32.2:

| Check | Result |
|---|---|
| Async calls Rust → Swift → Rust (change count, write, read first item with types) | Works |
| Swift `throws` → Rust `Err(PasteboardError::…)` | Works |
| Swift hopping to the main actor inside the method | Works |
| 1,000 calls at once from tokio tasks | ~2–3 ms total |
| One round trip | ~23 µs |
| Rust drops a waiting call (timeout) | Swift's `Task` is cancelled; the trait keeps working |
| Sync listener back to Swift | Arrives on a Rust thread; the app hops to its own (as the spec says) |

**One catch:** the generated Swift for async foreign traits doesn't compile in Swift 6 language mode (non-`Sendable` closures passed to `Task`). Compiling the generated file in Swift 5 mode, in its own module, fixes it; app code stays Swift 6. `SeamCore` already holds only the generated file, so the fix is one setting on that target: `swiftSettings: [.swiftLanguageMode(.v5)]`.

Also: `Pasteboard` clashes with an AppKit type name; the Mac qualifies it (`SeamCore.Pasteboard`) or the trait takes another name.

## Run it

```sh
cargo build --release
cargo run --release --bin uniffi-bindgen -- generate --library target/release/libproto_uniffi_async.a --language swift --out-dir swift-gen
swiftc -O -o build/spike swift-gen/proto_uniffi_async.swift swift/main.swift \
  -Xcc -fmodule-map-file=swift-gen/proto_uniffi_asyncFFI.modulemap -I swift-gen \
  -L target/release -lproto_uniffi_async -framework AppKit
./build/spike   # writes "seam spike" to the clipboard, puts the old text back at the end
```
