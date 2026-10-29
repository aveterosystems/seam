Seam {VERSION} for Android and Mac. Install both, then open Seam on the Mac and scan its code with the phone.

## Android

Download `Seam-{VERSION}-android.apk` on the phone and open it. Android asks once to allow installs from your browser or file app. Needs Android 12 or later.

## Mac

1. Download `Seam-{VERSION}-mac.zip`, open it, and drag **Seam** to Applications.
2. Open Seam. macOS says it can't check it for malicious software, because Seam isn't notarized by Apple yet. Click **Done**.
3. Open **System Settings › Privacy & Security**, scroll down to "Seam was blocked", and click **Open Anyway**. Confirm with your password.

You only do this once; updates open normally. Seam is signed with its own certificate, so updates keep access to its keys. Needs macOS 14 or later, Apple silicon or Intel.

## Check what you downloaded

Every file was built by GitHub Actions from this tag. To prove it (needs the [GitHub CLI](https://cli.github.com)):

```
gh attestation verify Seam-{VERSION}-mac.zip --repo aveterosystems/seam
gh attestation verify Seam-{VERSION}-android.apk --repo aveterosystems/seam
```

Or compare with `SHA256SUMS`. Signing certificates (SHA-256), the same for every release:

- Android: `{ANDROID_CERT}`
- Mac: `{MAC_CERT}`
