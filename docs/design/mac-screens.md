# Mac screens

Boards M01–M08 on the [canvas](https://claude.ai/artifact/L2EffY2kHu6PjJy6Nkoigd), page **Mac app**. Each board has a **dark** tweak. Tokens and components are in [design-system.md](design-system.md).

The Mac app is a menu bar app (`LSUIElement`), so it has no Dock icon. The main window opens from the popover or with ⌘O. Settings open with ⌘,.

## M01 Menu bar popover (`MacMenuBar.dc.html`)

**Purpose:** everything daily, two clicks away.

**Layout:** `MenuBarExtra(.window)`, 360 pt wide, top to bottom:
1. A device header: phone glyph, name, status line, battery pill and a settings button.
2. The stitch.
3. Four round Control Centre-style modules: Send file, Clipboard (a toggle, on by default), Ring phone, Messages.
4. **From your phone's clipboard**: the last two items, each with Copy or Open.
5. **Notifications**: the latest three, with Reply / Mark read on messages, and Clear all.
6. A footer: "End-to-end encrypted" and "Open Seam ⌘O".

**Behaviour:**
- The menu bar icon is the Seam mark. It's filled when connected and outlined when offline, and pulses once when something arrives.
- Dropping files on the icon sends them.
- Reply expands an inline field in place, without opening the window.

**States:**
- **Offline:** broken stitch and "Will send when your phone is back". The actions stay enabled and queue.
- **Not paired:** the popover shows only "Pair your phone" (it opens M02).
- **Low battery:** the battery pill turns `warn` at 15% or below.

**Done when:** the popover opens in under 100 ms, every action works from the keyboard (Tab, Space, Return), and VoiceOver reads the header as "Pixel 9, connected, same Wi‑Fi, battery 82 percent".

## M02 Pairing (`MacPairing.dc.html`)

**Purpose:** first run, or adding a phone.

**Layout:** a 740×520 window. On the left: the icon, "Pair your phone", three numbered steps and a security line. On the right: the QR code, the 6-digit confirmation code, a "Waiting for your phone…" countdown and "Can't scan? Pair with a code".

**Data:** the QR code encodes the Mac's public keys, a one-time token and the relay URL. The code refreshes every 5 minutes.

**Behaviour:** when the phone scans, the code under the QR code is highlighted, and the user checks it matches the phone (A02). Once both sides confirm, the window becomes "Paired with Pixel 9", the menu bar icon appears, and the window closes after 2 s.

**States:**
- **Token expired:** a new code appears automatically.
- **Codes don't match** (reported by the phone): the attempt is thrown away, and a new QR code appears with a one-line explanation.
- **No network:** pairing still works on the same Wi‑Fi. With neither the relay nor the LAN available: "Connect to the internet or the same Wi‑Fi as your phone".

**Done when:** a first-time user pairs in under 30 s without reading anything except the window.

## M03 Settings (`MacSettings.dc.html`)

**Purpose:** the System Settings-style window.

**Layout:** a sidebar with General, the phone by name, a **Features** section (Notifications, Messages, Clipboard, Files, Find my phone), then a **Seam** section (Privacy & Security, About). The content area is a grouped `Form`.

The board shows **Notifications**:
- Show on this Mac
- Dismiss on both devices
- Quiet while I'm using my phone
- Hide content on the lock screen
- A searchable per-app list with switches. Messaging apps show a "Reply on Mac" pill, and sensitive apps show "title only".

**Other panes** (not drawn; build them with the same grouped form):
- **Clipboard:** sync on/off, history length, never sync passwords, and **Turn on automatic clipboard (phone → Mac)**. That button starts the one-time adb set-up (USB or wireless debugging), with a step list and progress.
- **Files:** save location, and ask before receiving large files.
- **Device:** name, battery, paired date, verification code, Forget.
- **Privacy & Security:** encryption status, re-verify the code, export diagnostics (no content).

**Done when:** every switch takes effect at once (no Apply button), and the per-app list fills in from the phone within 2 s of opening.

## M04 Messages (`MacMessages.dc.html`)

**Purpose:** text from the Mac using the phone's number.

**Layout:**
- The sidebar (Messages, Notifications, Files & clipboard, and a device card at the bottom).
- The conversation list, with search and a compose button.
- The thread, with a header (name, "Text message · sent from your phone's number", a call-on-phone button), bubbles (yours in teal, theirs in `fill2`) and a composer (attach, field, send).

**Data:** threads and contacts come from the phone (`full` flavour). In the `play` flavour, the list shows only the conversations that arrived as notifications, and reply goes through the notification's reply action.

**States:**
- **Play flavour:** a one-line banner: "Showing conversations from notifications. Get the full app for complete history".
- **Sending:** a "Sending…" label under the bubble. "Delivered" comes from the phone's SMS status.
- **Offline:** the message is queued and shown as "Will send when your phone is back".

**Done when:** 200 threads load in under 5 s, and a sent SMS appears in the phone's own Messages thread.

## M05 Notifications (`MacNotifications.dc.html`)

**Purpose:** the history, and a place to act on several at once. Live ones also show in Notification Centre (M07).

**Layout:** All / Unread segmented control and Clear all in the toolbar. Grouped by app, each group is a section with app name and count. Messaging notifications have an inline reply field. Sensitive apps show a lock and "Content hidden. Open on your phone". Each row has a dismiss button.

**Behaviour:** dismissing here dismisses on the phone, and the other way round. "Open on phone" turns the phone screen on at that notification. A footer line explains the two-way dismiss.

**States:** empty shows "No notifications from Pixel 9". If notification access is off on the phone, show "Turn on notification access on your phone", with a button that opens the phone's settings screen remotely.

**Done when:** a notification appears here and in Notification Centre within 2 s, and dismissing syncs within 1 s on the same Wi‑Fi.

## M06 Files & clipboard (`MacFiles.dc.html`)

**Purpose:** transfers and clipboard history in one place.

**Layout:**
- **Files column:** a dashed teal drop zone ("Drop files here to send them to Pixel 9 / or drop them on the Seam icon in the menu bar"), a **Sending** list with progress, the route and time left, and **Received** rows with "Show in Finder".
- **Clipboard column:** a confirmation callout ("What you copy on this Mac is on your phone automatically"), then the 24-hour history with Copy on each item, and a note that passwords are never synced.

**Behaviour:** the route label follows the stitch rules. A transfer that falls back from the LAN to the relay changes its label without restarting.

**States:** empty drop zone only. Failed transfer: "Paused, will resume when your phone is back", never "Failed".

**Done when:** dropping a 1 GB file starts sending in under 1 s, and Cancel stops it on both devices.

## M07 System surfaces (`MacSystem.dc.html`)

**Purpose:** the parts that are macOS itself. These matter most.

| Surface | What Seam does |
|---|---|
| Notification banners | Texts use **communication notifications** (`INSendMessageIntent`): contact avatar with a small Seam badge, inline reply. Files: "Photo from Pixel 9" with Show. Phone battery low at 15% |
| Share menu | "Send to Pixel 9" via a Share extension, in every app |
| Menu bar | Dropping onto the Seam icon sends. The icon pulses once on arrival |
| Finder (v2) | The phone appears under **Locations** through a File Provider extension. Files download when opened |
| Paste | Copy on the phone, ⌘V on the Mac (automatic clipboard). Nothing to click |

**Done when:** a user can go a whole day without opening a Seam window.

## M08 Phone mirroring & widgets (`MacMirroring.dc.html`)

**Purpose:** the closest thing to iPhone Mirroring, plus small native extras.

**Layout:**
- A phone-shaped window (no title bar chrome beyond the traffic lights) that streams the phone's screen, with a slim toolbar: Home, Back, Recents, Screenshot, Rotate.
- A desktop / Notification Centre widget (WidgetKit, small and medium): phone battery, charging, connection.
- A banner: "Code 482 913 copied", from SMS or a notification.

**How mirroring works:** the Mac talks to the phone over **adb wireless debugging**, using the same one-time set-up that turns on automatic clipboard. It streams the screen the way scrcpy does: H.264 from the phone, decoded with VideoToolbox. Mouse and keyboard go back as input events.
- Unverified: whether the phone can stay locked or dark while mirrored. scrcpy can turn the phone's display off.
- Unverified: how long Android keeps wireless debugging paired.

**Behaviour:** open it from the popover ("Mirror phone"). Clicks become taps, scroll becomes swipes, and typing goes straight to the phone. Dragging a file from Finder into the window sends it. Closing the window stops the stream.

**States:**
- **Not set up:** "Mirroring needs a one-time setup" with Set up, which runs the same steps as automatic clipboard.
- **Wireless debugging off:** "Turn on Wireless debugging on your phone", with a deep link.
- **Play flavour:** hidden.

**Done when:** mirroring starts in under 3 s on the same Wi‑Fi, input lag feels like scrcpy (target under 100 ms, to be measured), and the widget updates within a minute of a battery change.
