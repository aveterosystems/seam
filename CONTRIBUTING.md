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
2. **Findings are posted on the pull request**, in the format below: one inline comment per finding on the exact line, plus one summary comment. Each comment opens a review thread, and `main` won't accept the merge until every thread is resolved.
3. **Fix before merge.** Critical and High findings are fixed in the PR, each with a test, and the thread is resolved with a link to the fixing commit. Medium and Low are fixed, or accepted with a reason in the thread (a follow-up issue if it's deferred).
4. **Line-by-line reading** by the maintainer for anything in `core/`, `protocol/`, or the relay's login and pairing code.
5. **`main` is protected.** Changes land only through a pull request, with all five CI checks (`core`, `protocol`, `android`, `mac`, `server`) passing and every review thread resolved. Squash merges only, and nobody can bypass this, admins included.

An independent security audit is planned before 1.0. Review here is meant to make that audit short, not replace it.

### Review comment format

Every finding gets an id, `SR-<PR number>-<two digits>` (e.g. `SR-2-04`), numbered most severe first. Inline comment:

```markdown
**🔴 High · SR-2-04 · Pairing slot can be silently replaced**
`relay` · Confirmed

**Problem:** One sentence: what's wrong.
**Scenario:** Who does what, and the bad outcome.
**Fix:** The change, concretely.

<sub>Automated security review · resolve this thread with the fixing commit, or the reason it's accepted</sub>
```

Severity markers: 🟣 Critical · 🔴 High · 🟠 Medium · 🟡 Low · ⚪ Info. Status is **Confirmed** (traced in the code or shown by a test) or **Plausible** (needs proof).

Summary comment, one per review:

```markdown
## Security review · PR #2

| ID | Severity | Area | Finding | Status |
|---|---|---|---|---|
| SR-2-01 | 🔴 High | core | … | Confirmed |

**Areas reviewed:** …
**Checked and fine:** the security properties verified to hold.
**Outside this diff:** findings on code the PR doesn't change (no inline thread), each with file and line.
```

### Closing a finding

**Every thread gets a reply before it's resolved. Nothing is resolved silently.** The reply takes one of three forms:

```markdown
**✅ Fixed · SR-2-04** in abc1234
What changed, in one line.
**Proven by:** `test_name_one`, `test_name_two`
```

```markdown
**⚖️ Accepted · SR-2-25**
Why it's acceptable as it stands, what part was fixed, and the follow-up issue if it's deferred (#NN).
```

```markdown
**↩️ Disputed · SR-2-07**
Why the finding doesn't apply, with the evidence (code, test, or docs).
```

When to resolve:

| Reply | Resolved when | By |
|---|---|---|
| ✅ Fixed | CI is green on the fixing commit | The PR author |
| ⚖️ Accepted | The reason (and any follow-up issue) is written | The PR author |
| ↩️ Disputed | **The maintainer agrees.** It stays open until then. | The maintainer only |

Disputed threads stay open on purpose: an author doesn't get to dismiss a finding about their own code.

When every thread has its reply, post **one resolution summary** on the PR: a table of every finding (Low and Info included), its outcome (Fixed / Accepted / Disputed / Open) and its commit, so the review reads as one complete record.

```markdown
## Security review · resolution · PR #2

| ID | Severity | Outcome | Commit |
|---|---|---|---|
| SR-2-01 | 🔴 High | ✅ Fixed | 9ed4702 |
| SR-2-25 | 🟠 Medium | ⚖️ Accepted (partly fixed) | e724a60 |
```

## Code style

- Rust: `cargo fmt` and `cargo clippy -- -D warnings`.
- Protobuf: `buf format` and `buf lint`. Never reuse or renumber a field.
- Swift, Kotlin, TypeScript: follow the surrounding code. Files stay small (about 300 lines at most), split by responsibility.
- New source files start with `SPDX-License-Identifier: AGPL-3.0-only` in a comment.
- Comments say *why*, not what.

## Adding a feature

A feature is one Protobuf message in `protocol/seam/v1/` (taking the next free block of ten field numbers), one module per app behind the `Feature` interface, and one line in each app's feature registry. It advertises a capability string, so it stays off until both devices support it.
