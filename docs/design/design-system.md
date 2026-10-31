# Design system

The source of truth is the [design canvas](https://claude.ai/artifact/L2EffY2kHu6PjJy6Nkoigd) (private; share it from the page's Share menu). Board **00 Foundations** (`Main.dc.html`) holds the tokens. If this doc and a board disagree, the board wins: fix the doc.

Each app looks like its own platform. The Mac app follows current macOS, and the Android app follows Material 3 Expressive. What they share is the Seam teal, the icon, the "stitch" connection line and the words.

## Principles

| Rule | What it means on screen |
|---|---|
| **Instant** | No spinners for small things. Progress shows only for files. |
| **Always works** | No "not connected" dead end. Offline means "will send when back". |
| **Zero setup** | One QR scan, then one guided permission pass. |
| **Invisible when idle** | No Mac Dock icon or open window, and only a quiet, hideable phone notification. |
| **System surfaces first** | Notification Centre, the Share menu, Finder, Quick Settings and the share sheet come first. The app's own screens are the fallback. |

## Mac

**Use stock SwiftUI components and the system materials** (`.glassEffect`, `.background(.regularMaterial)`, `NavigationSplitView`, `Form` with `.formStyle(.grouped)`, `Toggle`, `.buttonStyle(.bordered)`/`.borderedProminent`). Don't custom-draw controls. The mockups imitate what the system draws, and the build should get it for free. `mac.css` on the canvas is only a stand-in.

| Part | Look | SwiftUI |
|---|---|---|
| Window | Glass over the desktop, 20 px corners, large soft shadow | Standard window; macOS supplies the material |
| Sidebar | Floats as its own glass panel, inset 8 px | `NavigationSplitView` sidebar |
| Toolbar | No divider; title on the left; controls in glass capsules | `.toolbar` items |
| Buttons | Capsules: raised white (or teal for primary) | `.bordered`, `.borderedProminent` |
| Sections | Grouped rows on a faint fill, not bordered cards | `Form` + `Section`, `GroupBox` |
| Fields | Rounded, borderless fill | `TextField`, `.searchable` |
| Switches | System toggle, tinted teal | `Toggle` + `.tint` |
| Menu bar popover | Control Centre style, with round module buttons | `MenuBarExtra(.window)` |

### Mac tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| `accent` | `#0B7F6F` | `#3DD6BE` | Primary buttons, selection, icons, the stitch |
| `accentInk` | `#07604F` | `#6FE6D2` | Teal text on `accentSoft` (passes 4.5:1) |
| `accentSoft` | teal 13% | teal 16% | Tinted callouts, pills |
| `onAccent` | `#FFFFFF` | `#062520` | Text on teal |
| `text` / `text2` / `text3` | `#1D1D1F` / `#4E4E53` / `#636368` | `#F5F5F7` / `#CDCDD2` / `#A9A9AF` | Primary, secondary, captions |
| `fill` / `fill2` | black 4.5% / 8% | white 6% / 11% | Grouped sections / fields, selected sidebar row |
| `ok` / `warn` / `danger` | `#1C7C3A` / `#9A5200` / `#B4232F` | `#4ADE80` / `#F5A524` / `#FF7B86` | Status dots, destructive text |

If the user has set a system accent colour, controls follow it. Seam teal is the default, and it's always used for the icon and the stitch.

### Mac type (SF Pro, the system font)

| Style | Size / weight | SwiftUI |
|---|---|---|
| Title | 20 / 600 (24 / 700 on pairing) | `.title3` / `.title2` |
| Headline | 15 / 600 | `.headline` |
| Body | 13 / 400 | `.body` |
| Caption | 11 / 500 | `.caption` |
| Codes and keys | JetBrains Mono, or SF Mono in the build | `.monospaced()` |

## Android

**Material 3 Expressive** in Compose. Dynamic colour (Material You) is on by default. Seam teal is the fallback scheme and the seed for Settings › Appearance › "Seam colours".

| Role | Light | Dark |
|---|---|---|
| `primary` / `onPrimary` | `#006B5E` / `#FFFFFF` | `#83D5C4` / `#003730` |
| `primaryContainer` / `on…` | `#9FF2E0` / `#00201B` | `#005047` / `#9FF2E0` |
| `secondaryContainer` / `on…` | `#CCE8E1` / `#06201B` | `#334B46` / `#CCE8E1` |
| `tertiaryContainer` / `on…` | `#CFE5FF` / `#001D33` | `#2E4A63` / `#CFE5FF` |
| `surface` | `#F4FBF8` | `#0E1513` |
| `surfaceContainerLow` / `…` / `High` / `Highest` | `#EEF5F2` / `#E8EFEC` / `#E2EAE7` / `#DCE4E1` | `#161D1B` / `#1A211F` / `#252B2A` / `#2F3634` |
| `onSurface` / `onSurfaceVariant` | `#161D1B` / `#3F4946` | `#DDE4E1` / `#BEC9C5` |
| `outline` / `outlineVariant` | `#6F7976` / `#BEC9C5` | `#89938F` / `#3F4946` |

### Android shapes

| Part | Shape |
|---|---|
| Grouped lists (the Android 16 Settings style) | Each row is its own surface. The group's outer corners are 24 dp, inner corners 4 dp, with 2 dp gaps between rows |
| Home hero | The Expressive "cookie": a circle with 9 soft scallops, filled `primaryContainer` |
| Main round actions | 68 dp circles. The selected or primary one morphs to a 22 dp rounded square |
| Cards, sheets | 24–28 dp |
| Buttons | Full pill: 40 dp normal, 56 dp large |
| Quick Settings tile (the system's own) | 64 dp pill |

### Android type (Roboto Flex)

| Style | Size / line | Notes |
|---|---|---|
| Hero headline | 36 / 44, 600, width 112% | Uses the width axis (`wdth`) for the Expressive "emphasised" feel |
| Headline | 24 / 32 | Dialog titles |
| Title | 16 / 24, 500 | List titles, sheet titles |
| Body large / body | 16 / 24 · 14 / 20 | |
| Label | 12 / 16, 500, +0.4 tracking | Section labels, chip text |

## Icons

The Mac uses **SF Symbols**, so weight and scale follow the system. Android uses the 24 px stroke set on board 00 (1.8 stroke, round caps), or Material Symbols Rounded where one matches.

| Meaning | SF Symbol | Android |
|---|---|---|
| Clipboard | `doc.on.clipboard` | clipboard (stroke set) |
| Send file | `arrow.up.doc` | file-up |
| Notifications | `bell` | bell |
| Messages | `message` | message |
| Phone | `iphone` (no Android glyph in SF Symbols) | smartphone |
| Mac | `laptopcomputer` | laptop |
| Ring | `speaker.wave.2` | volume |
| Encrypted | `checkmark.shield` | shield-check |
| Same Wi‑Fi | `wifi` | wifi |
| Relay | `cloud` | cloud |
| Lock Mac | `lock` | lock |
| Settings | `slider.horizontal.3` | sliders |

## The stitch: how a connection is shown

Every place that shows the link between the devices (the menu bar header, the sidebar device card, the Android home) uses the same line under the device name.

| Line | Label | Meaning |
|---|---|---|
| Solid teal | **Connected · Same Wi‑Fi** | Direct, fastest; big files go this way |
| Dashed teal | **Connected · Through Seam relay** | Anywhere, end-to-end encrypted |
| Dotted grey | **Nearby** | Bluetooth sees the device; waking up to connect |
| Broken grey | **Offline** | "Will send when your phone is back" |

Offline is never drawn as an error: no red, and no "failed". Red is only for things the user must act on, such as a revoked pairing.

## Motion

How every screen moves, and the finish it needs before it's done, is in [motion.md](motion.md).

## Copy and voice

- Plain verbs: "Send to phone", "Ring phone", "Open on phone". Don't say "transmit" or "push".
- Use the device's own name ("Pixel 9", "MacBook Pro"), not "your device".
- Say what will happen, not what went wrong: "Will send when your phone is back".
- Security in one short line where it matters ("End-to-end encrypted"), never as a wall of text.
- Sentence case everywhere. No exclamation marks, no emoji.
- Mockups use invented names and placeholders like `[DOWNLOAD URL]`, and no real numbers.

## Accessibility

- Text contrast is at least 4.5:1, including 11–12 px captions. `accentInk` exists because teal on `accentSoft` fails otherwise.
- Targets are at least 44 pt on the Mac popover and at least 48 dp on Android.
- Every icon-only button has a label (`.accessibilityLabel`, `contentDescription`).
- The stitch is never the only signal: the text label always says the same thing.
- Status colours also differ in lightness, not only in hue.
- Support Dynamic Type / font scale, Reduce Motion (see [motion.md](motion.md)) and Reduce Transparency on the Mac (the system swaps glass for solid).

## Implementation

| Platform | Where | Contents |
|---|---|---|
| Mac | `apps/mac/Packages/SeamDesign` | `Color` assets for the tokens (light/dark in the asset catalogue), `SeamStitch` view, the app icon, symbol constants. No custom button or toggle styles. |
| Android | `apps/android/core/designsystem` | `SeamTheme` (dynamic colour with teal fallback), `SeamShapes` (cookie, grouped list corners), Roboto Flex `FontFamily` with variation settings, `Stitch` composable, icon set as vector drawables |

Roboto Flex and JetBrains Mono are bundled (OFL licence). The Mac uses the system fonts only.
