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

D-Bus: `com.avetero.Seam.Clipboard` at `/com/avetero/Seam/Clipboard`, interface
`com.avetero.Seam.Clipboard1`: `Version` (u, the API level), `ChangeCount() → x`,
`Read(x if_still) → (b ok, as types, s text)`, `Write(s) → x`.

Test the access check with `gjs -m trust.test.js`. Pack it for extensions.gnome.org with
`gnome-extensions pack seam-clipboard@avetero.com --extra-source=trust.js`.
