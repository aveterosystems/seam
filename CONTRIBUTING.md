# Contributing to Seam

Thanks for helping. Seam is a security product first, so the bar for changes is high, but the process is simple.

## Before you start

- **Security issues** never go in public issues or pull requests. See [SECURITY.md](SECURITY.md).
- For anything bigger than a bug fix, **open an issue first** so we can agree on the approach before you write it.
- By contributing, you agree your contribution is licensed under the project's [AGPL-3.0-only](LICENSE) licence.

## The rules every change follows

1. **Content never leaves a device unencrypted.** Every payload goes through `core/seam-core` before any transport, the local network included.
2. **No crypto outside `core/seam-core`.** The apps call the generated bindings; they never encrypt, sign or derive keys themselves.
3. **No home-made cryptography.** Only libsignal and audited RustCrypto crates.
4. **Never trust a key on first use.** Peers are pinned at pairing.
5. **The relay learns nothing new.** No new plaintext envelope field that reveals content, type, real size or device identity.
6. **Never log** message contents, keys, phone numbers, contact or file names, or IP addresses.
7. **Every security property has a test.** If a change claims or relies on one, it proves it.

## Setting up

| You'll need | For |
|---|---|
| Rust (rustup) | `core/`. The toolchain is pinned in `core/rust-toolchain.toml` and installed automatically. |
| `protoc` | libsignal's build, and `scripts/gen-protocol.sh` |
| Xcode 26+ and XcodeGen | The Mac app |
| JDK 17, the Android SDK, NDK 29, cargo-ndk | The Android app |
| Node 22 | The relay |

Each part's README explains how to build and test it: [core](core/README.md), [protocol](protocol/README.md), [Mac](apps/mac/README.md), [Android](apps/android/README.md), [relay](server/README.md), [scripts](scripts/README.md).

## Making a change

1. Branch from `main`.
2. Keep generated code in sync. CI fails if it drifts:
   - Changed `protocol/`? Run `scripts/gen-protocol.sh`.
   - Changed `core/seam-ffi`'s API? Run `scripts/gen-bindings.sh`.
3. Run the tests for the parts you touched. CI runs everything a change affects.
4. Open a pull request saying what changed and why. Keep it focused: one change per PR.

## Review

**Every pull request gets a security-focused review before it merges.** Passing CI isn't enough.

1. **Automated review, per area.** Each part the PR touches (core, relay, Android, Mac) is reviewed against the rules above: trust-on-first-use leaks, pairing and login bypasses, replay and binding, nonce reuse, panics on untrusted input, anything sensitive logged, and claims without tests. Findings are ranked Critical / High / Medium / Low and checked before they're acted on.
2. **Fix before merge.** Critical and High findings are fixed in the PR, each with a test. Medium and Low are fixed or written up as issues. The PR description lists every finding and what happened to it.
3. **Line-by-line reading** by the maintainer for anything in `core/`, `protocol/`, or the relay's login and pairing code.
4. **`main` is protected.** Changes land only through a pull request, with all five CI checks (`core`, `protocol`, `android`, `mac`, `server`) passing and every review thread resolved. Squash merges only, and nobody can bypass this, admins included.

An independent security audit is planned before 1.0. Review here is meant to make that audit short, not replace it.

## Code style

- Rust: `cargo fmt` and `cargo clippy -- -D warnings`.
- Protobuf: `buf format` and `buf lint`. Never reuse or renumber a field.
- Swift, Kotlin, TypeScript: follow the surrounding code. Files stay small (about 300 lines at most), split by responsibility.
- New source files start with `SPDX-License-Identifier: AGPL-3.0-only` in a comment.
- Comments say *why*, not what.

## Adding a feature

A feature is one Protobuf message in `protocol/seam/v1/` (taking the next free block of ten field numbers), one module per app behind the `Feature` interface, and one line in each app's feature registry. It advertises a capability string, so it stays off until both devices support it.
