# Android screens

Boards A01–A08 on the [canvas](https://claude.ai/artifact/L2EffY2kHu6PjJy6Nkoigd), page **Android app**. Each board has a **dark** tweak (A07 defaults to dark, like the real shade). Each board is a 390×844 phone screen. Tokens and components are in [design-system.md](design-system.md).

The app is deliberately small. Most of Seam on Android is system surfaces (A07): share sheet, Quick Settings, notifications, the copy pop-up. The app itself is for status, set-up and settings.

## A01 Home (`AndHome.dc.html`)

**Purpose:** "my Mac is connected". Calm and nearly empty.

**Layout:**
- A top bar: Seam mark and wordmark, with **Activity** and **Settings** icon buttons. There's no bottom nav.
- The **cookie hero**: a large version of the Seam icon (Mac and phone joined by stitches) on the scalloped `primaryContainer` shape.
- The device name as a 36 sp hero headline, with "● Connected · Same Wi‑Fi" and "Encrypted · Mac battery 64%" under it.
- **Four round actions:** Clipboard (primary, rounded-square shape), Files, Photos, Lock Mac.
- A bottom card: "Copied on your Mac · 1 min ago", with the item and **Paste** (copies it into the phone clipboard).

**Behaviour:**
- Clipboard sends the current clip. This uses the app's foreground focus, so it always works.
- Files and Photos open the system pickers.
- Lock Mac locks the Mac at once.
- The hero's stitches follow the stitch rules (solid, dashed, dotted, broken).

**States:**
- **Offline:** the stitches break, the status reads "Offline · will send when your Mac is back", and the actions still queue.
- **Not paired:** the hero shows the Mac outline only, with a large **Pair with a Mac** button (opens A02).

**Done when:** the screen fits without scrolling on a 360 dp-wide phone, at 1.3× font scale.

## A02 Pairing (`AndPairing.dc.html`)

**Purpose:** scan and confirm.

**Layout:** a full-bleed camera viewfinder with teal corner brackets and "Point at the code on your Mac". After a scan, a bottom sheet rises: the Mac glyph, "Pair with MacBook Pro?", the 6-digit code in mono 34 sp, **Pair**, "Codes don't match" and "Keys stay in this phone's security chip".

**Behaviour:** Pair runs the Noise XX handshake and then Android's **companion device** association dialog (the system dialog, shown right after). "Codes don't match" aborts and tells the Mac. The camera permission is asked in context, the first time only.

**States:** camera denied leads to "Pair with a code instead" (type the 6 digits shown on the Mac). Expired token: "That code has expired. Scan the new one on your Mac".

**Done when:** scan to paired takes under 10 s, including the companion dialog.

## A03 Set-up checklist (`AndPermissions.dc.html`)

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

## A06 Settings (`AndSettings.dc.html`)

**Purpose:** per-feature switches, privacy, the paired Mac.

**Layout:** a back button, the "Settings" hero headline, then three grouped sections.
- **On your Mac:**
  - Notifications ("23 apps · sensitive ones title only", which opens the per-app list)
  - Texts
  - **Automatic clipboard**, highlighted in `primaryContainer` when on. It reads "On · set up from your Mac", or "Off · set up from your Mac" with an explainer.
  - Files (save location)
- **Privacy & security:** "End-to-end encrypted · keys verified when you paired · 482 913" (tap to re-verify) and "Never sync passwords".
- **Mac:** the device row with **Forget** in error colour.

**Behaviour:** the automatic clipboard switch can't be turned on from the phone alone. If the adb grant is missing, tapping it shows a sheet: "Open Seam on your Mac › Settings › Clipboard › Turn on automatic clipboard". Forget removes the keys on both devices.

**Done when:** every switch syncs to the Mac's matching setting within 2 s.

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
