## What and why

<!-- What this changes, and why. Link the issue if there is one. -->

## Security review

Every PR gets a security-focused review before it merges (see CONTRIBUTING.md, "Review"). Tick what applies:

- [ ] **Content stays end-to-end encrypted.** Nothing new leaves a device outside `core/seam-core`.
- [ ] **No crypto outside the core**, and no new primitive without a decision record.
- [ ] **No trust on first use.** Peer keys still come only from pairing.
- [ ] **The relay learns nothing new.** No new plaintext envelope or relay field revealing content, type, real size or identity.
- [ ] **Nothing sensitive is logged:** no contents, keys, phone numbers, names, IPs.
- [ ] **Every security claim this relies on has a test.**
- [ ] **Generated code is in sync** (`scripts/gen-protocol.sh`, `scripts/gen-bindings.sh`).
- [ ] Touches `core/`, `protocol/` or relay login/pairing: **read line by line** by the maintainer.

Review findings and how each was resolved:

<!-- e.g. "High: replayed PairRequest accepted → fixed in abc123, test added" -->

## Testing

<!-- What you ran, and on which devices. -->
