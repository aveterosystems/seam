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
8. **The phone never holds a connection it doesn't need.** On battery it connects to send, when the screen turns on, and when an empty push wakes it for something urgent, then disconnects. Pings on any held connection are as rare as the network allows. Pushes carry no content.

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

## Releasing

One release per version, tagged `vX.Y.Z`, with both apps attached. The apps share a protocol version and ship together; the relay isn't released, it deploys from `main`.

Which number goes up:

| Release | When | Example |
|---|---|---|
| `patch` (0.2.0 → 0.2.1) | Fixes and small improvements to what's already there. No new feature, no protocol change | A crash fix, a clearer setting, update checks that run more often |
| `minor` (0.2.1 → 0.3.0) | Something new a user would notice: a feature, a new protocol message or capability. Older apps keep working with it | Phone notifications on the Mac, file transfer |
| `major` (0.3.0 → 1.0.0) | A change older apps can't work with (they must both update, or pair again), or 1.0 itself once Seam is stable | A new protocol version, new pairing |

If a release has both, the bigger one wins: a fix plus a new feature is `minor`.

1. **Once:** the maintainer runs `scripts/make-release-keys.sh` on their own Mac. It sets up a `release` environment (only `v*` tags, needs the maintainer's approval) and a ruleset so only admins can create, move or delete `v*` tags. It makes the Android signing key and Seam's Mac certificate (self-signed, not Apple's) in an encrypted disk image, stores them as `release` environment secrets, and writes their public fingerprints to `release/`. Commit those two files. Then `scripts/make-update-key.sh` makes the Mac update key (Ed25519, for Sparkle) in the same disk image, stores it as a `release` secret, and writes its public half into `apps/mac/Config/Release-Info.plist`; commit that too.
2. On an up-to-date `main`, run `scripts/release.sh patch` (or `minor`, `major`, or `X.Y.Z`). It raises `versionName` and `versionCode` in `apps/android/app/build.gradle.kts`, `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` in `apps/mac/project.yml`, and the README status lines; drafts the `CHANGELOG.md` section for the release from the merged PR titles and opens it in your `$EDITOR` (write it for people using Seam: it's shown in the GitHub release, Sparkle's update window on the Mac, and the Update card on Android); opens the release PR; waits for its checks; merges it; and tags the merge commit `vX.Y.Z`.
3. By hand instead: make those edits in a PR, and after it merges, `git tag -a vX.Y.Z origin/main -m "Seam X.Y.Z" && git push origin vX.Y.Z`.
4. The `release` workflow (`scripts/check-release.sh`) checks the tag is on `main`, that both versions match it and both build numbers went up, that `CHANGELOG.md` has a section for it, and that the pins are well formed. Then:
   - it builds both apps with no secrets;
   - it waits for the maintainer to approve the `release` environment;
   - it signs in separate jobs, checking the signatures match the pinned fingerprints;
   - it signs the Mac update and a signed `appcast.xml` (Sparkle's feed) with the update key;
   - it opens a **draft** release with `SHA256SUMS` and build attestations.

   Read the draft, then publish it.

Publishing makes it the latest release, which is what both apps' update checks look at: the Mac reads `releases/latest/download/appcast.xml`, the Android GitHub build reads the latest release's APK and `SHA256SUMS`. Drafts are never offered.

The Mac app isn't notarized (that needs a paid Apple Developer account), so the first download needs **Open Anyway** in System Settings once; after that Sparkle installs updates. `release/notes.md` explains it to users.
