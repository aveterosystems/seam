# Motion and craft

The bar for every screen in every Seam app. It was set by an interactive prototype of the Mac menu bar popover; every timing and distance from it is written down here, so this doc is the whole spec. A screen is done when it feels like that prototype: alive, calm, and clear about what just changed. (The prototype is kept locally as `docs/design/prototypes/mac-popover.html`, a click-through demo for people, and isn't in the repo.)

Colours, type and shapes are in [design-system.md](design-system.md). This doc covers how things move and the finish every screen needs.

## Principles

| Word | Meaning |
|---|---|
| **Causal** | Motion explains a state change: where something came from, where it went, what's different now. Each animation answers "what just changed?" A screen at rest stays still, apart from the connection signals. |
| **Physical** | Anything that moves, grows or scales does it on a spring. Colour and opacity ease without overshoot. Linear timing is only for continuous loops. |
| **Quick** | 150–450 ms for almost everything; 700 ms at most. Input works during an animation, which can be interrupted and retargeted. Nobody waits for one to finish. |
| **In place** | The change happens where the user is already looking: a row grows a reply field, a label swaps in its slot, a tip unfolds under the line it explains. A new window or sheet only appears when the task really needs one. |
| **Small** | Short distances (6–10 pt), gentle scales (0.94–0.96 for presses and openings), light overshoot (about 7%). One emphasis per event: a ring or a bounce, not both. |
| **Consistent** | The same event moves the same way everywhere. Pick the motion from the catalogue below. A new kind of event gets a new catalogue entry first, then the code. |
| **Native first** | System components keep their own motion: menus, toggles, sheets, popovers, and Material 3 Expressive's motion scheme on Android. Custom motion is only for Seam's own pieces: tiles, the stitch, rows and status. |

## Timing tokens

Spatial means position, size or scale. Effects means opacity or colour.

| Token | Value | Use | SwiftUI | Compose |
|---|---|---|---|---|
| `spring` | 420 ms: peaks at 25% with 7.7% overshoot, settled by about 45% | Default spatial: open, expand, insert, press release | `Motion.css(0.42)` = `.spring(response: 0.388 × 0.42, dampingFraction: 0.632)` | `MaterialTheme.motionScheme.defaultSpatialSpec()` |
| `springFast` | 300 ms, the same curve | Presses, chevrons, small icons | `Motion.css(0.3)` | `fastSpatialSpec()` |
| `fade` | 300–400 ms, CSS `ease` (0.25, 0.1, 0.25, 1) | Colour and opacity changes, theme switch | `Motion.ease(0.35)` | `defaultEffectsSpec()` |
| `exit` | 180 ms, ease-in | Content leaving its slot before new content arrives | `.easeIn(duration: 0.18)` | `fastEffectsSpec()` |
| `settle` | 500–700 ms, strong ease-out `(0.2, 0.8, 0.2, 1)` | Line drawing, counting numbers | `.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.7)` | `tween(700, easing = CubicBezierEasing(.2f, .8f, .2f, 1f))` |
| `stagger` | 35 ms per section, starting at 60 ms | Sections arriving when a surface opens | `.delay(0.06 + 0.035 * i)` | `delayMillis = 60 + 35 * i` |

Define these once per platform: Mac in `apps/mac/Seam/UI/SeamStyle.swift`, Android in `Theme.kt`. Screens use the named token, never a bare duration.

The springs are the prototype's own: its `--spring` is a CSS `linear()` curve, and `Motion.css(d)` is that curve fitted as a SwiftUI spring (damping 0.632, response 0.388 × d), so every duration in the prototype's CSS can be used as written. Transitions the CSS leaves untimed use its default `ease`, which is `Motion.ease(d)`. Checked frame by frame against the prototypes.

## Catalogue

Each entry gives the event, what moves, and how it's built.

### 1. A surface opens (popover, window, sheet)
- The surface scales 0.94 → 1, drops 6 pt and fades in on `spring`, growing from where it was opened (the popover from the menu bar icon).
- Its sections rise 6 pt and fade in on `stagger`, top to bottom, finishing in under 350 ms.
- The Same Wi‑Fi stitch draws itself left to right on `settle` as the header lands.
- SwiftUI: an `appeared` flag set in `.onAppear`; each section uses `.opacity` and `.offset(y:)` with `.animation(spring.delay(…), value: appeared)`. Compose: `AnimatedVisibility` with `fadeIn() + slideInVertically { it / 8 }` and a staggered delay.

### 2. A toggle changes (tile, module, switch)
- Press: scale to 0.96 while held, then spring back (`springFast`).
- The fill crossfades between teal and neutral (`fade`).
- The symbol bounces (scale dips to 0.7, then springs back). Swapping symbols, such as `bell` and `bell.slash`, morphs one into the other.
- The subtitle says the new state in words ("On", "Off", "Paused · 15:00") using the value swap (entry 3).
- SwiftUI: one shared `PressableStyle` button style in `Components.swift`, plus `.symbolEffect(.bounce, value: on)` and `.contentTransition(.symbolEffect(.replace))`. Compose: `animateColorAsState`, a press scale, and the Expressive shape morph from design-system.md.

### 3. A value changes in place (status text, subtitles, labels)
- The old text rises 8 pt and fades out (`exit`). The new text rises 8 pt from below into the same slot (`spring`).
- The slot keeps a fixed height, so nothing around it moves.
- SwiftUI: `.id(text)` with `.transition(.push(from: .bottom))`, or `.contentTransition(.interpolate)` when the text only changes slightly. Compose: `AnimatedContent` using `(slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())`.

### 4. A number changes (battery, counts)
- The number counts from the old value to the new one on `settle`, using tabular digits so its width stays put.
- SwiftUI: `.contentTransition(.numericText(value:))` with `.monospacedDigit()`. Compose: `animateIntAsState` with `fontFeatureSettings = "tnum"`.

### 5. Something arrives (notification, clip, message)
- The new row goes in at the top. Its height grows from zero, it fades in and slides down 10 pt (`spring`), and the rows below are pushed down smoothly.
- Its app icon pops in from 0.3 scale, 100 ms behind the row.
- If the list is over its limit, the last row leaves at the same moment (entry 6).
- Whatever represents the feature reacts once: the bell rings (a ±18° wiggle that dies away over 600 ms), and the menu bar icon pops (scale 1.35 at 40% of a `spring`).
- SwiftUI: `ForEach` over the data, with `.transition(.asymmetric(insertion: .push(from: .top), removal: …))` and `.animation(spring, value: ids)` on the container. Compose: `LazyColumn` with `Modifier.animateItem()`.

### 6. Something leaves (dismissed, sent, expired)
- The row slides 30 pt toward the trailing edge and fades, while its height collapses (`spring`, about 420 ms).
- SwiftUI: removal `.move(edge: .trailing).combined(with: .opacity)`. Compose: `animateItem()` fade-out plus a horizontal slide.

### 7. Something expands in place (reply field, explanation, queue bar, banner)
- Height grows from zero to its content on `spring` while the content fades in.
- Keyboard focus moves into a field once it has opened, after about 250 ms. Escape closes it again.
- Banners such as updates, warnings and errors appear in one slot at the top of the surface and unfold the same way.
- SwiftUI: `if expanded { … .transition(.opacity.combined(with: .move(edge: .top))) }` inside `withAnimation(spring)`. Compose: `AnimatedVisibility(enter = expandVertically() + fadeIn())`.

### 8. An action succeeds
- The action's own label confirms it: "Copy" becomes "✓ Copied" in the `ok` colour for 1.3 s, then goes back. A sent reply shows "✓ Sent" for 1.1 s, then its row leaves.
- When the result happens somewhere else (a page opens in the browser, a file downloads), a small pill rises 10 pt above the footer for 1.4 s.
- Success is shown by the control itself, with no alert and no sound.

### 9. A menu opens
- Use the system `Menu`, which has its own animation.
- For a Seam-drawn dropdown: it scales 0.85 → 1 and moves 6 pt from the corner it's attached to (`spring`), and its chevron turns 180°.

### 10. The connection (the only motion at rest)
| State | Motion |
|---|---|
| Connected | The status dot sends out a soft ring: scale 1 → 2.8 while fading, every 2.4 s |
| Same Wi‑Fi | Solid stitch, drawn left to right on `settle` when the connection forms or the surface opens |
| Through Tor | Dashed stitch whose dashes flow along continuously, 11 pt per second |
| Offline | Broken grey stitch, completely still. The device glyph fades from teal to grey (500 ms) |
| Items waiting | The clock icon in the queue bar turns once every 6 s |

The line styles crossfade over 400 ms when the state changes. Offline is calm: nothing shakes and nothing turns red.

### 11. Battery
- The number counts (entry 4). The charging bolt springs in and out with a scale.
- At 15% or below while not charging, the pill fades to `warn`.

### 12. Hover and focus
- Rows: a background fill fades in over 150 ms.
- Actions that only appear on hover (Reply, ✕) slide in 6 pt and fade in over 200 ms. They also appear when the row has keyboard focus.
- Focus ring: 2 pt teal, drawn on the focused control itself.

## The finish every screen needs

**Before a UI change is done:**

- **Every state is designed:** loading or waiting ("Waiting for phone"), empty ("Nothing on your phone right now"), offline, paused, error, and the low and high ends of every value. Nothing is dimmed without a reason in words beside it.
- **State is in words:** colour and motion repeat what a label already says.
- **Layout is stable:** swapped text sits in a fixed-height slot, digits are tabular, and space is reserved so that what changes never pushes what didn't.
- **Motion is driven by state:** a change in the data drives the animation, so fast repeated clicks still end in the right state. Timers only control how long a confirmation stays on screen.
- **Reduce Motion is handled:** movement becomes a crossfade, loops stop (the pulse, the dash flow, the clock), and the stitch is drawn without animating. SwiftUI: `@Environment(\.accessibilityReduceMotion)`. Android: honour the system animator scale and stop loops when it's 0.
- **The keyboard works:** Tab reaches every control, Space or Return activates it, and Escape closes whatever expanded.
- **VoiceOver and TalkBack work:** labels carry the state, and an arrival is announced (`AccessibilityNotification.Announcement`, `announceForAccessibility`).
- **Both themes are checked**, using the contrast tokens in design-system.md.
- **It's smooth:** animate transforms and opacity. The popover shows its first frame in under 100 ms.
- **It has been watched:** run the app, trigger every state from the catalogue, and look at it in light, dark, and with Reduce Motion on. Reading the code doesn't count as seeing it.

## Where motion lives in code

Tokens and shared animated pieces live in one place per platform, and screens compose them, the same way they compose components:

| Platform | Tokens | Shared animated views |
|---|---|---|
| Mac | `apps/mac/Seam/UI/SeamStyle.swift` | `apps/mac/Seam/UI/Components.swift`: `PressableStyle`, the value swap, the animated `Stitch`, the row transitions |
| Android | `apps/android/app/.../Theme.kt`: `Motion`, which carries the approved Android prototype's own curves (its CSS `--spring` and `--bouncy` `linear()` easings, `--emph`, and CSS `ease`), and a `motionScheme` built from them so Material's components move the same way | `Components.kt`, `Sheets.kt`, `Pages.kt`: the value swap, unfold, rows, sheets, dialog, snackbar, page transitions and predictive back |

On Android the approved prototype (`docs/design/prototypes/android-app.html`) gives each element its own duration, and the app uses those exactly; the tokens above are the defaults where it doesn't say. With the system's animations turned off, the hero stops turning, the status ring and Tor's dashes stop, and page changes and swapped text become crossfades.

When a screen needs motion that isn't here yet, add it to the shared file and the catalogue above, then use it.
