Seam {VERSION} for Android, Mac and Linux. Install it on the phone and the computer, then open Seam on the computer and scan its code with the phone.

## What's changed

{CHANGES}

## Android

Download `Seam-{VERSION}-android.apk` on the phone and open it. Android asks once to allow installs from your browser or file app. Needs Android 12 or later.

Seam then offers new versions itself: once a day it checks GitHub, and **Update** on its home screen downloads the new version, checks it's signed by Seam, and asks Android to install it (Android asks once to let Seam install updates). Turn checks off in Settings › Updates.

## Mac

1. Download `Seam-{VERSION}-mac.zip`, open it, and drag **Seam** to Applications.
2. Open Seam. macOS says it can't check Seam for malicious software, because it isn't notarized by Apple yet. Close the message.
3. Open **System Settings › Privacy & Security**, scroll down to the message about Seam, and click **Open Anyway**. Confirm with your password.

You only do this for the first install. After that, Seam can update itself: it asks once whether to check, then once a day it checks GitHub and offers a new version only if it's signed with Seam's update key; nothing installs until you say so. Your pairing carries over. Turn checks off in Settings › About. Needs macOS 14 or later, Apple silicon or Intel.

## Linux

Install Seam from its Flatpak repo: open [com.avetero.Seam.flatpakref](https://aveterosystems.github.io/seam/com.avetero.Seam.flatpakref) and click **Install** in Software (GNOME) or Discover (KDE Plasma), or in a terminal:

```
flatpak install --user https://aveterosystems.github.io/seam/com.avetero.Seam.flatpakref
```

Updates then come through Software, Discover or `flatpak update`, checked against Seam's signing key. On Ubuntu, add Flatpak first: `sudo apt install flatpak gnome-software-plugin-flatpak`, then log out and in. Needs GNOME or KDE Plasma on Wayland, x86_64 or ARM.

On GNOME, automatic clipboard also needs the **Seam Clipboard** extension: Seam offers to set it up. `Seam-{VERSION}-gnome-extension.zip` is that extension, for installing by hand. The `.flatpak` files are the same builds as the repo's, for installing without it (`flatpak install Seam-{VERSION}-linux-x86_64.flatpak`); they don't update themselves.

## Check what you downloaded

Two checks, each proving something different:

- **Who built it.** Every file here, `SHA256SUMS` and `appcast.xml` included, has a GitHub attestation saying it was built by Seam's release workflow from this tag. With the [GitHub CLI](https://cli.github.com):

  ```
  gh attestation verify Seam-{VERSION}-mac.zip --repo aveterosystems/seam \
    --signer-workflow aveterosystems/seam/.github/workflows/release.yml --source-ref refs/tags/v{VERSION}
  ```

  (and the same for `Seam-{VERSION}-android.apk` and the Linux files).
- **Who signed it.** The signing certificates are the same for every release. If one ever changes, something is wrong:
  - Android: `{ANDROID_CERT}` (`apksigner verify --print-certs`, "certificate SHA-256 digest")
  - Mac: `{MAC_CERT}` (SHA-256 of the certificate in `codesign -d --extract-certificates`)
  - Linux: the Flatpak repo's key, `{FLATPAK_KEY}` (`gpg --show-keys` on `apps/linux/flatpak/seam.gpg`; Flatpak checks it on every install and update)
