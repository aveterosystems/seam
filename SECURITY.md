# Security policy

Seam's whole purpose is that nobody but your own two devices can read what passes between them. We treat any weakness in that as the most serious kind of bug.

## Reporting a vulnerability

**Please don't open a public issue.** Report privately through GitHub:

**[Report a vulnerability →](https://github.com/aveterosystems/seam/security/advisories/new)**

Include what you found, how to reproduce it, and what an attacker could do with it. A proof of concept helps, but isn't required.

| | What to expect |
|---|---|
| Acknowledgement | Within 3 working days |
| First assessment | Within 10 working days |
| Fix and disclosure | Coordinated with you. We aim to ship a fix within 90 days and credit you in the release notes, unless you'd rather stay anonymous. |

## In scope

- The crypto core (`core/`): key handling, pairing and identity pinning, session setup, message and file encryption, padding
- The protocol (`protocol/`): anything that lets the relay or a network attacker learn, alter, replay or forge content
- The relay (`server/`): authentication, routing, queueing, logging of anything it shouldn't
- The apps (`apps/`): key storage, anything that sends content outside the end-to-end encryption, never-sync rules (passwords, sensitive notifications)
- Releases (`.github/workflows/release.yml`, `scripts/`, `release/`): anything that could get an unreviewed build signed or published

## Out of scope

- Attacks that need a device that's already compromised (malware running as the user, a rooted phone with the attacker in control)
- Denial of service against the relay, unless it also breaks confidentiality or integrity
- Vulnerabilities in libsignal itself: please report those to Signal at security@signal.org. Tell us too, so we can pin a fixed version.

## Supported versions

Seam is in early development. Until 1.0, only the [latest release](https://github.com/aveterosystems/seam/releases/latest) and the latest commit on `main` get security fixes.

## Checking a release

Each release is built by `.github/workflows/release.yml` from a tag on `main`, signed in a separate job that needs the maintainer's approval, and checked against the signing-certificate fingerprints pinned in [`release/`](release/). Its notes show how to verify the build attestations and fingerprints. A release signed by any other certificate isn't ours: please report it.

## How Seam is protected

- End-to-end encryption with the Signal Protocol, through Signal's own libsignal (pinned release): PQXDH post-quantum session setup and the triple ratchet
- One shared Rust core for both apps: no crypto is implemented twice, and none is home-made
- Peer keys pinned at pairing by QR code: never trusted on first use
- Fixed-size padding, envelopes bound to their ciphertext, a relay that logs no IPs or contents (its host, Cloudflare, still sees IPs at its edge; pushes, when added, carry nothing)
- Security properties are tested in `core/seam-core`, the Swift and Kotlin bindings and the apps; the threat model lists what isn't covered yet
- An independent audit is planned before 1.0
