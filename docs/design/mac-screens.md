# Mac screens

Boards M01–M08 on the [canvas](https://claude.ai/artifact/L2EffY2kHu6PjJy6Nkoigd), page **Mac app**. Each board has a **dark** tweak. Tokens and components are in [design-system.md](design-system.md).

The Mac app is a menu bar app (`LSUIElement`), so it has no Dock icon. The main window opens from the popover or with ⌘O. Settings open with ⌘,.

## M01 Menu bar popover (`MacMenuBar.dc.html`, prototype `mac-popover.html`)

**Purpose:** everything daily, two clicks away.

**Built** (`apps/mac/Seam/UI/Popover*.swift`), `MenuBarExtra(.window)`, 340 pt wide, top to bottom:
1. **One banner slot**: a startup error (with Reset Seam), then a warning, then "Seam X is available" with Update. It unfolds in place.
2. **Header**: a tinted phone glyph, the phone's name, and the status line with a live dot: "Connected · Same Wi‑Fi" or "Connected · Through Tor". Offline it reads "Last seen 12 min ago", and ⓘ unfolds why underneath. Any other reason (Tor blocked, a pause, no shared connection) stays in words. On the right are the battery pill (the bolt springs in and out, the number counts, and it turns `warn` at 15% or below when not charging) and, under it, "Wi‑Fi" or "Mobile data" with a moon for Do Not Disturb, or "as of 14:02" offline.
3. **The stitch**: it draws itself on the same Wi‑Fi, its dashes flow through Tor, it's broken and grey offline, and the styles crossfade.
4. **Queue bar** (offline with copies waiting): "3 waiting · sends when your phone is back", with a turning clock.
5. **Two tiles**: Clipboard ("Syncing", "On", "Off") and Notifications ("On · 2 showing", "Paused · 15:00", "Paused · tomorrow", "Off", "Waiting for phone"). Its ▾ menu has Pause for 1 hour, Pause until tomorrow, Resume now, Turn off (or Turn on) and Notification settings.
6. **Notifications**: the latest three. Hover or focus shows Reply (or Open) and ✕. Reply opens a field in place: Return sends, Escape closes, then "✓ Sent from your phone" shows for 1.1 s and the row leaves. "All N ›" opens the main window.
7. **Clipboard from phone**: the last two. The whole row is clickable, with a link or ¶ icon. Text copies again and shows "✓ Copied"; a link opens and a pill confirms it. "History ›" opens the main window.
8. **Footer**: "End-to-end encrypted", "Open Seam ⌘O" and one gear button (⌘,).

The words for every state come from SeamSession's `Looks.swift` (`statusLine`, `PhoneStatusLook`, `NotificationsLook`, `ClipboardLook`, `Words`), which has unit tests. The controller records when the link last went from connected to not.

**Behaviour:** the menu bar icon pops once and VoiceOver announces what arrived. The bell rings for a notification. Motion follows [motion.md](motion.md), checked frame by frame against the prototype.

**States:** connected (Same Wi‑Fi or Tor), offline with copies waiting, low battery, paused, waiting for the phone's settings, empty lists, not paired ("Pair your phone"), startup error.

**Not built yet:** dropping files on the icon (Files isn't built).

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

## M03 Settings (`MacSettings.dc.html`, prototype `mac-settings.html`)

**Purpose:** the System Settings-style window.

**Built** (`apps/mac/Seam/UI/Settings*.swift`, `Sidebar.swift`, `Rows.swift`): a fixed 700 × 540 window drawn to the prototype.
- **Sidebar**: a floating panel holding the traffic lights and Search. Typing jumps to the first pane that matches and fades the rest.
- **Title bar**: 52 pt, with the pane's title, and Show history on Notifications and Clipboard.
- **Grouped rows** in the prototype's sizes, with its switch, segmented control and capsule buttons.

Switching panes fades the old one out, then the new one's sections rise in a stagger.

- **General:** Open at login (`SMAppService.mainApp`, with Login Items if macOS wants approval). Updates: Check automatically ⓘ, How often, and Seam's version with Check now, which asks quietly and shows "Checking", "✓ Seam is up to date" or the new version in place. Then Quit, and About at the bottom.
- **The phone, by name:** a live card (a phone glyph that fills to the battery level, the status line, the stitch, and Battery, Network and DND). Connections: Same Wi‑Fi and Internet through Tor; the last one on locks, and the footnote says why. Pairing: the verification code, blurred until Show; "Paired 14 September 2026" (the date is kept from pairing on, cleared on unpair or reset, and "Paired with this Mac" when unknown); Unpair, which asks first.
- **Notifications:** Show on this Mac, Dismiss on both devices, Quiet while I'm using my phone, and the System Settings link. Apps: a live "6 of 10 on" count, a filter, and an On, Title only or Off control per app (with an animated "Reply on Mac" badge). Select mode adds checkboxes and a floating bar to set several apps at once.
- **Clipboard:** Sync the clipboard, Never send passwords (always on), Waiting copies ⓘ, and Automatic from your phone ⓘ with Set up (the USB/adb steps).
- **Privacy & Security:** End-to-end encrypted, Away from home through Tor, Update checks with Change (opens General), and the Tor notice.

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
