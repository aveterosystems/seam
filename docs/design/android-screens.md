# Android screens

Boards A01–A08 on the [canvas](https://claude.ai/artifact/L2EffY2kHu6PjJy6Nkoigd), page **Android app**. Each board has a **dark** tweak (A07 defaults to dark, like the real shade). Each board is a 390×844 phone screen. Tokens and components are in [design-system.md](design-system.md).

The app is deliberately small. Most of Seam on Android is system surfaces (A07): share sheet, Quick Settings, notifications, the copy pop-up. The app itself is for status, set-up and settings.

## A01 Home (built)

**Purpose:** "my Mac is connected", and the two things to do about it. Built to the approved prototype (`docs/design/prototypes/android-app.html`, kept locally), light and dark.

**Layout, top to bottom:**
- A 56 dp bar: the Seam mark and wordmark, and **Settings**.
- The update card when a newer Seam is out (GitHub build, tertiaryContainer, "Seam 0.3 is ready to install", **Update**), or the once-only "Check for updates automatically?" card.
- The **cookie hero** (196 dp): the Mac and this phone joined by three stitches, on the 9-scallop cookie. Connected, it's primaryContainer and turns once every 40 s; paused, it springs to a softer tertiaryContainer cookie and stops; otherwise it's a still grey circle. The shape morphs on the prototype's bouncy curve (0.8 s).
- The Mac's name (30 sp), the status chip ("● Connected · Same Wi‑Fi"; the dot sends out a ring every 2.4 s while connected; tertiary while paused, "Paused · until 14:44"), and "Encrypted   Mac battery 64%" ("—" while the Mac is out of reach, hidden until it has ever sent its battery).
- A button group: **Send clipboard** (primary) and **Pause** (tonal), 4 : 1. Pressing one widens it and tightens its corners. Paused, Pause folds away and the main button becomes **Resume**.
- "Copied on your Mac · just now" with **Paste**, while a Mac copy is fresh. Paste turns green and says "✓ Pasted", then the card folds away.
- A grouped list: **Notifications on Mac ›** ("On · 6 apps") and **Automatic clipboard**, highlighted in primaryContainer while on.

**Behaviour:**
- Send clipboard says "✓ Sent to Mac" on the button for 1.3 s when it went, "Sending" when the connection is still starting (it doesn't claim it was sent, SR-8-04), and "Will send when MacBook Pro is back" in a snackbar when the Mac is out of reach.
- Pause opens the Pause sheet: 30 minutes, 1 hour, until tomorrow morning, until I resume. The notification's Pause button opens the same sheet. Resume says "Seam is back on".
- Automatic clipboard can't be switched from the phone alone: the row and its switch explain how (on the Mac, or through Accessibility).

**States:** connected (Same Wi‑Fi: solid stitches that draw themselves in; Tor: dashes flowing along), offline ("Offline · will send when it's back", broken grey stitches, still), paused, idle ("Paired · connects when there's something to sync"), and Tor's own problems in words. Not paired: the pairing intro.

## A02 Pairing (`AndPairing.dc.html`, restyled with the same parts; follows the light or dark theme)

**Purpose:** scan and confirm.

**Layout:** a full-bleed camera viewfinder with teal corner brackets and "Point at the code on your Mac". After a scan, a bottom sheet rises: the Mac glyph, "Pair with MacBook Pro?", the 6-digit code in mono 34 sp, **Pair**, "Codes don't match" and "Keys stay in this phone's security chip".

**Behaviour:** Pair runs the Noise XX handshake and then Android's **companion device** association dialog (the system dialog, shown right after). "Codes don't match" aborts and tells the Mac. The camera permission is asked in context, the first time only.

**States:** camera denied leads to "Pair with a code instead" (type the 6 digits shown on the Mac). Expired token: "That code has expired. Scan the new one on your Mac".

**Done when:** scan to paired takes under 10 s, including the companion dialog.

## A03 Set-up checklist (`AndPermissions.dc.html`, restyled with the same parts)

**Purpose:** one guided pass for the permissions, all optional.

**Layout:** "Step 2 of 2" and the hero headline "Paired. Now let Seam do its job", then a grouped list:

| Row | Permission | Notes |
|---|---|---|
| Stay connected to your Mac | Companion device (done during A02) | Shows a filled tick |
| Notifications on your Mac | Notification listener access | Primary "Allow" (filled) |
| Texts on your Mac | SMS (`full` only) | Hidden in `play` |
| Keep working in the background | Ignore battery optimisation, plus an OEM guide if needed | |
| Clipboard tile | Adds the Quick Settings tile (`requestAddTileService`) | |

A footnote explains the clipboard: "Mac → phone is automatic. Phone → Mac is automatic once you set it up from your Mac" (`full`), or "one tap" (`play`). Then Skip for now / Done.

**Behaviour:** each Allow opens the exact system screen, and the row turns into a tick on return.

**Done when:** each row reflects the real permission state after returning from system settings, with no stale ticks.

## A04 Share to Mac (`AndShare.dc.html`)

**Purpose:** sending from any app.

**Layout:** the share sheet over the source app. Seam appears as a **direct share target** "MacBook Pro · Seam", first in the row. After tapping it, an in-sheet progress card appears: "Sending to MacBook Pro · Same Wi‑Fi, direct · 5.1 of 8.3 MB" with progress and Cancel. The item preview (stacked thumbnails, count, size) is at the top.

**Behaviour:** the sheet can be dismissed at once. The transfer continues as a foreground-service notification with progress, and the Mac shows its own banner on arrival. Text and links shared this way go to the Mac clipboard, and links open on the Mac (Handoff-style).

**States:** offline: "Will send when your Mac is back", queued, with the notification updating later.

**Done when:** tapping the target and dismissing the sheet completes the send in the background, even if the source app is closed.

## A05 Activity (`AndActivity.dc.html`)

**Purpose:** what moved, for the last 7 days.

**Layout:** a back button, the "Activity" hero headline, filter chips (All, Files, Clipboard, Replies), and grouped lists by day. Each row has a direction glyph, a title, "Sent to Mac" or "From Mac clipboard" with the time, inline progress when live, and an open arrow on received files.

**States:** empty: "Nothing yet. Things you send or receive show up here".

**Done when:** the list stays smooth at 500 rows and entries older than 7 days are deleted.

## A06 Settings (built)

**Layout:** back, and a 34 sp title that shrinks to 0.6 and fades over the first 60 dp of scroll while a 20 sp title fades in beside the arrow. Then grouped sections:
- **On your Mac:** Notifications ›, Automatic clipboard, Never send passwords (always on: tapping says so).
- **Connection:** Same Wi‑Fi, Internet through Tor. The last one on can't be turned off, and the footnote says what the pair means.
- **Privacy & security:** End-to-end encrypted, with the pairing code blurred until tapped.
- **Updates** (GitHub build): "Check daily" (or how often it's set to), How often (a sheet), and the version row that checks now ("Checking", "✓ Up to date"), with the note about Install unknown apps (SR-17-14).
- **Your Mac:** its name, "Paired 14 September 2026" (saved when pairing finishes, cleared when it's forgotten, hidden when unknown), and **Forget** in the error colour, which asks first.
- **About:** the version (Play build) and the source.

## Notifications (built)

Settings › Notifications, also opened from Home.
- **Show on your Mac**, a big primaryContainer card with its switch; off, the rest dims and can't be changed.
- Dismiss on both, and Quiet while I'm using this phone.
- A search field, then the apps, each with a mode chip (On, Title only, Off). Tapping one opens a sheet of radio cards; long-pressing one, or **Select**, starts picking several, with a floating toolbar to set them all at once and a snackbar saying what changed.
- The access row ("Allow") shows only while notification access is off.

## Moving between screens (built)

Forward, the new screen slides in from 30% to the right while the old drifts 18% left. Back, by gesture (predictive back) or the arrow, the screen first shrinks to 0.9, leans right and rounds its corners, then leaves. Sheets rise from below on the prototype's spring, dialogs scale up from 0.85, and confirmations of things that happened elsewhere are snackbars. With animations off, these become crossfades and the loops stop.

**Checking against the prototype:** debug builds have a screen gallery (`GalleryActivity`, debug only) that shows the real screens with the prototype's sample data, and `./gradlew :app:testFullDebugUnitTest --tests '*ScreensTest*' -Pscreens` renders every state, light and dark, to `app/build/screens/`.

## A07 System surfaces (`AndSystem.dc.html`)

**Purpose:** Seam where Android already is. Drawn on the dark shade.

| Surface | Detail |
|---|---|
| **Quick Settings tile** | "Send to Mac · Clipboard". Tapping it sends the current clip (the tile gets the focus it needs to read it) |
| **Arrival notification** | "Photo from MacBook Pro", with a thumbnail, Open and Share |
| **Ongoing notification** | "Connected to MacBook Pro". Silent, minimised, and can be hidden from settings (the foreground service requires it) |
| **Copy pop-up** (Android 13+) | A "Send to Mac" chip inside the system copy preview: the fallback when automatic clipboard is off |

**Done when:** with automatic clipboard on, a copy reaches the Mac before the system copy pop-up fades (target under 1 s on the same Wi‑Fi).

## A08 Find my phone (`AndFindPhone.dc.html`)

**Purpose:** the phone rings when the Mac asks, even on silent.

**Layout:** a full-screen intent over the lock screen. The `primaryContainer` background has a pulsing cookie shape and the phone glyph, with "Your Mac is looking for this phone", "Ringing from MacBook Pro" and a single huge **Stop** button (56 dp+, full width). Turning the phone over or pressing a volume key also stops it.

**Behaviour:**
- Rings on the alarm stream at full volume, overriding silent and Do Not Disturb (needs the full-screen-intent and DND-access permissions, asked in A03 if the user opts in).
- Vibrates and turns on the torch pulse (optional).
- The Mac sees "Found" when it's stopped, and the event is logged in A05.

**States:** without DND access it rings at the current alarm volume, and the Mac shows "Your phone may be on silent".

**Done when:** it rings within 3 s of pressing Ring on the Mac while the phone is locked and idle.
