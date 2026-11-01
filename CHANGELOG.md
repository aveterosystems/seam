# Changelog

What changed in each release. `scripts/release.sh` drafts the next section from the merged PR titles and opens it for you to edit; the release, the Mac update window and the Android update card show that section.

## 0.2.4

- While the phone has Seam paused, the Mac holds back everything it would send, not only the clipboard
- The phone and Mac tell each other what they can do when they connect, ready for features only one side supports
- Shared rules move into the core: message time limits, the clipboard rules and the Hello are written once for both apps
- Remove the retired relay; docs describe Same Wi‑Fi and Tor only
- Docs: a glossary, and where each feature's core code lives
- README: show only what Seam does today
- Mac: the popover and Settings redesigned to the approved prototypes
- Android: the approved redesign, to the prototype, in light and dark
- Give up a dead link in seconds: Hellos list the open links, unacked messages fail over
- Status both ways: Mac battery to the phone; Wi‑Fi or mobile data and Do Not Disturb to the Mac
- Design docs in the repo, with a motion standard every screen follows
- CI and release: reuse main's Rust cache, compile the core once, test only what's built
- Mac: a main window with the phone's notifications and the clipboard history

## 0.2.3

- Mac: click the connection status to see why the phone is offline
- Integrate Tor - prefer Wi‑Fi, then a private onion service, user can toggle either
- Pause Seam from the phone; notification controls and a Quick Settings tile
- Android battery fix; path lists in the core; Mac paths in the tested package

## 0.2.2

- Each app gets one session module; path rules and app data move into seam-core
- Same Wi‑Fi connection, connection switches and private relays
- Android: automatic clipboard through Accessibility, no Developer options
- Releases say what changed, on GitHub and in both apps' update prompts

## 0.2.1

- Updates: choose how often Seam checks (half-hourly to weekly), install with one click, and see a badge on the Mac's menu bar icon when an update is waiting.

## 0.2.0

- Phone notifications show on the Mac, with settings you can change from either side.
- The Mac menu bar icon is the same size as the system's icons.

## 0.1.1

- In-app updates for both apps.
