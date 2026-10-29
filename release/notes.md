Seam {VERSION} for Android and Mac. Install both, then open Seam on the Mac and scan its code with the phone.

## Android

Download `Seam-{VERSION}-android.apk` on the phone and open it. Android asks once to allow installs from your browser or file app. Needs Android 12 or later. To update, install the new APK over the old one.

## Mac

1. Download `Seam-{VERSION}-mac.zip`, open it, and drag **Seam** to Applications.
2. Open Seam. macOS says it can't check Seam for malicious software, because it isn't notarized by Apple yet. Close the message.
3. Open **System Settings › Privacy & Security**, scroll down to the message about Seam, and click **Open Anyway**. Confirm with your password.

Each version you download needs **Open Anyway** once. Your pairing carries over: every release is signed with the same Seam certificate, so a new version can use the keys the old one saved. Needs macOS 14 or later, Apple silicon or Intel.

## Check what you downloaded

Two checks, each proving something different:

- **Who built it.** Every file here, `SHA256SUMS` included, has a GitHub attestation saying it was built by Seam's release workflow from this tag. With the [GitHub CLI](https://cli.github.com):

  ```
  gh attestation verify Seam-{VERSION}-mac.zip --repo aveterosystems/seam \
    --signer-workflow aveterosystems/seam/.github/workflows/release.yml --source-ref refs/tags/v{VERSION}
  ```

  (and the same for `Seam-{VERSION}-android.apk`).
- **Who signed it.** The signing certificates are the same for every release. If one ever changes, something is wrong:
  - Android: `{ANDROID_CERT}` (`apksigner verify --print-certs`, "certificate SHA-256 digest")
  - Mac: `{MAC_CERT}` (SHA-256 of the certificate in `codesign -d --extract-certificates`)
