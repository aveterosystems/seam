# Seam Clipboard (GNOME Shell extension)

Wayland lets only the focused app read or write the clipboard. This extension lets Seam send what
you copy to your phone, and put what you copy on your phone here, without clicking Send
(decision 0012).

- **No other sandboxed app can use it.** Each caller is checked: it must be the Seam Flatpak
  (`com.avetero.Seam`, read from its `/.flatpak-info`, which a sandboxed app can't fake). Every
  other sandboxed app gets an error. A program running unsandboxed as you is outside what this
  can stop (it could fake a Flatpak's root, or install a shell extension of its own), as it is for
  every desktop portal; such a program can already read your keyring and files.
- **Nothing is kept or logged.** It counts clipboard changes and hands over the current copy when
  Seam asks. Seam only asks while clipboard sync is on.
- **Development builds** of Seam run outside Flatpak. To let one in, create
  `~/.config/seam/allow-development-build`. Delete it when you're done.

## Installing it

Seam offers it in **Preferences › Clipboard**. Once it's on extensions.gnome.org, GNOME asks
once, then installs it and turns it on. Until then, or by hand: download `Seam-<version>-gnome-extension.zip` from a
[release](https://github.com/aveterosystems/seam/releases), then

```bash
gnome-extensions install Seam-*-gnome-extension.zip
```

log out and back in, and Seam's **Turn On** (or the Extensions app) turns it on. Without it,
clipboard still works with one click: **Send Clipboard**.

D-Bus: `com.avetero.Seam.Clipboard` at `/com/avetero/Seam/Clipboard`, interface
`com.avetero.Seam.Clipboard1`: `Version` (u, the API level), `ChangeCount() → x`,
`Read(x if_still) → (b ok, as types, s text)`, `Write(s) → x`.

Licensed GPL-3.0-or-later (`COPYING`), unlike the rest of Seam (AGPL-3.0-only): extensions.gnome.org
takes only licences compatible with GNOME Shell's GPL-2.0-or-later.

Test the access check with `gjs -m trust.test.js`. Every release attaches the zip for
extensions.gnome.org (`Seam-<version>-gnome-extension.zip`, the same files as
`gnome-extensions pack seam-clipboard@avetero.com --extra-source=trust.js --extra-source=COPYING`).

## Keeping up with GNOME

GNOME ships a new version each March and September, and extensions only load on the versions in
`metadata.json`'s `shell-version`. For each one, while it's in beta:

1. Run Seam with the extension on the beta (a Fedora prerelease VM has it), and check Seam's
   clipboard door opens: copy something, and it reaches the phone without **Send Clipboard**.
2. Add the version to `shell-version` (extensions.gnome.org takes stable versions and at most
   one in development) and, if the interface changed, bump `API` in `extension.js`.
3. Upload the zip from the next release to extensions.gnome.org, which reviews it again.

A GNOME release the extension doesn't support yet isn't broken for users: GNOME leaves the
extension off, Seam sees no answer on the bus and falls back to **Send Clipboard**, and
Preferences says so.
