# Mac screens

Boards M01–M08 on the [canvas](https://claude.ai/artifact/L2EffY2kHu6PjJy6Nkoigd), page **Mac app**. Each board has a **dark** tweak. Tokens and components are in [design-system.md](design-system.md).

The Mac app is a menu bar app (`LSUIElement`), so it has no Dock icon. The main window opens from the popover or with ⌘O. Settings open with ⌘,.

## M01 Menu bar popover (`MacMenuBar.dc.html`, prototype `mac-popover.html`)

**Purpose:** everything daily, two clicks away.

**Built** (`apps/mac/Seam/UI/Popover*.swift`), `MenuBarExtra(.window)`, 320 pt wide, drawn as Apple's own menu bar extras are: no fill of its own, so the menu bar's glass shows through. Top to bottom:
1. **One banner slot**: a startup error (with Reset Seam), then a warning, then "Seam X is available" with Update. It unfolds in place.
2. **Header**, like a connected device in the Bluetooth menu: the phone on a disc (`PhoneDisc`, teal while connected, grey when not), its name, and the status line with a live dot: "Connected" and the path's icon (Wi‑Fi or globe). Hovering the icon names the path; VoiceOver reads "Connected · Same Wi‑Fi". Offline it reads "Last seen 12 min ago", and ⓘ unfolds why. On the right, the battery as the Mac's Battery menu writes it ("82%" and the battery symbol, a bolt while charging) in a faint capsule with a chevron, and under it "☾ Do Not Disturb", "as of 14:02" offline, or "Low battery" (the word as well as the `warn` colour). Clicking the battery unfolds its time and rate under the stitch ("42 min until full", "18 W charging", and a level bar), asked for afresh every 10 s while it's open.
3. **The stitch**, right under the header: it draws itself on the same Wi‑Fi, its dashes flow through Tor, it's broken and grey offline, and the styles crossfade.
4. **Queue bar** (offline with something waiting): "3 waiting · sends when Pixel 9 is back", with a turning clock.
5. **The control module**, as Control Centre's connectivity block: one rounded module (`Pop.module`) with a row per feature, each a round button that fills teal when on, its name and its state in words: Clipboard ("Syncing", "On", "Off") and Notifications ("On · 2 showing", "Paused until 15:00", "Off", "Waiting for phone"). Paused is the calm paused blue, never `warn`. Each row's ⋯ is the system menu (Clipboard: turn sync on or off, settings; Notifications: pause for 1 hour or until tomorrow, resume, turn off or on, settings). Out of reach of the phone the module desaturates: still on, but nothing flows.
6. **Recent notifications**: the latest three, with "Show all" (a small capsule) only when there are any. The time sits on the title's line; on hover or focus, Reply (or Open) and ✕ take its place. Reply opens a field in place: Return sends, Escape closes, then "✓ Sent to your phone" shows for 1.1 s and the row leaves.
7. **Recent copies**: the last two from the phone, "From Pixel 9 · 2 min ago". The whole row is clickable; Copy or Open shows on hover, then "✓ Copied", or a pill confirms a link opened.
8. **Footer**: "End-to-end encrypted", then menu items as every menu bar extra ends: Open Seam ⌘O, Settings ⌘,, Check for updates (release builds) and Quit Seam ⌘Q.

The words for every state come from SeamSession's `Looks.swift` (`statusLine`, `PhoneStatusLook`, `NotificationsLook`, `ClipboardLook`, `Words`), which has unit tests. The controller records when the link last went from connected to not.

**Behaviour:** the menu bar icon pops once and VoiceOver announces what arrived. The bell rings for a notification. Motion follows [motion.md](motion.md), checked frame by frame against the prototype.

**States:** connected (Same Wi‑Fi or Tor), offline with copies waiting, low battery, paused, waiting for the phone's settings, empty lists, not paired ("Pair your phone"), startup error.

**Not built yet:** dropping files on the icon (Files isn't built).

## M02 Pairing (`MacPairing.dc.html`)

**Purpose:** first run, or adding a phone.

**Layout:** a 740×520 window. On the left: the icon, "Pair your phone", three numbered steps and a security line. On the right: the QR code, the 6-digit confirmation code, a "Waiting for your phone…" countdown and "Can't scan? Pair with a code".

**Data:** the QR code encodes the Mac's public keys, a one-time token and the relay URL. The code refreshes every 5 minutes.

**Copy:** the waiting line is "Waiting for your phone · code expires in 4:59" over the stitch's travelling grey dots (waiting, never an amber warning). Step 1 links to the latest release. Buttons are Seam's capsules; Cancel stays the prominent default on the code check.

**Behaviour:** when the phone scans, the code under the QR code is highlighted, and the user checks it matches the phone (A02). Once both sides confirm, the window becomes "Paired with Pixel 9", the menu bar icon appears, and the window closes after 2 s.

**States:**
- **Token expired:** a new code appears automatically.
- **Codes don't match** (reported by the phone): the attempt is thrown away, and a new QR code appears with a one-line explanation.
- **No network:** pairing still works on the same Wi‑Fi. With neither the relay nor the LAN available: "Connect to the internet or the same Wi‑Fi as your phone".

**Done when:** a first-time user pairs in under 30 s without reading anything except the window.

## M03 Settings (`MacSettings.dc.html`, prototype `mac-settings.html`)

**Purpose:** the System Settings-style window.

**Built** (`apps/mac/Seam/UI/Settings*.swift`): a standard Mac `Settings` window, a toolbar tab per pane, each a grouped `Form` with the system's toggles, pop-up menus and buttons. Each tab is as tall as its content. Rows are a title with a smaller secondary line under it (`RowLabel`); facts with no control (end-to-end encryption, Tor, passwords) show their state where a control would be.

- **General:** Open at login (`SMAppService.mainApp`, with Login Items if macOS wants approval). Updates: Check automatically for updates, How often, Nightly builds (asks first). About: the icon, the licence with a link to the source, the version and Check now, which shows "Checking", "✓ Seam is up to date" or the new version in place. Quit lives in the popover and ⌘Q.
- **The phone, by name:** a header like a device's in System Settings (the phone disc, its name, the status line, the stitch, and battery, network, free storage and Do Not Disturb; offline, the battery says "as of 14:02"). Connections: Same Wi‑Fi and Internet through Tor (the last one on locks, and the footnote says why), and "Connection stuck?" with Reconnect. Pairing: the verification code, blurred until Show; "Paired 14 September 2026"; Unpair, which asks first.
- **Notifications:** Show on this Mac, Dismiss on both devices, Quiet while I'm using my phone, and Open System Settings. Apps: "6 of 10 on", a filter field (with more than 8 apps) and Set all; each app is On, Sender only or Off in a pop-up menu, with a reply arrow when it can be answered from the Mac (explained in the footnote).
- **Clipboard:** Sync the clipboard, Passwords stay on this Mac ("Always"), how long copies wait (footnote), and Copy automatically with Set up (the USB/adb steps).
- **Privacy:** End-to-end encrypted ("Always"), Internet through Tor (On or Off, its switch is on the phone's tab), Update checks (its switch is in General), and the Tor notice.

**Not built:** "Show in the menu bar". With it off, nothing could bring Seam's window back on relaunch, so it stays out until that's solved. Files, Messages and Find my phone panes come with their features.

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

**Built** (`MainWindow*.swift`): a standard `NavigationSplitView` window. The sidebar has Notifications (with its count) and Clipboard as plain symbols, and the phone's card at the bottom (the disc, battery, the popover's status line and the stitch), which opens the phone's settings. The toolbar has Clear all (asks first, and says whether the phone is cleared too) or Clear history, and Settings. Both pages are the same native list: a header ("Recent notifications", "Recent copies"), rows with the app's icon or the copy's kind, the time on the title's line, actions on hover (dismiss takes the time's place, as in Notification Centre) and in a context menu, and a note under the rows. Reply unfolds the shared `ReplyField` in place. Empty, off, paused, waiting and not-paired states are centred (`ContentUnavailableView`) with one action where there is one.

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
