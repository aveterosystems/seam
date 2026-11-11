# Android screens

Boards A01–A08 on the [canvas](https://claude.ai/artifact/L2EffY2kHu6PjJy6Nkoigd), page **Android app**. Each board has a **dark** tweak (A07 defaults to dark, like the real shade). Each board is a 390×844 phone screen. Tokens and components are in [design-system.md](design-system.md).

The app is deliberately small. Most of Seam on Android is system surfaces (A07): share sheet, Quick Settings, notifications, the copy pop-up. The app itself is for status, set-up and settings.

## A01 Home (built)

**Purpose:** "my Mac is connected", and the two things to do about it. Built to the approved prototype (`docs/design/prototypes/android-app.html`, kept locally), light and dark.

**Layout, top to bottom:**
- A 56 dp bar: the Seam mark and wordmark, and **Settings** (the sliders icon).
- The update card when a newer Seam is out (GitHub build, secondaryContainer, so tertiary means only paused, "Seam 0.3 is ready", **Update**), or the once-only "Check for updates automatically?" card (the same container, **Check daily** and a text-button Not now). Home's cards all sit on secondaryContainer, apart from the list's grey rows, and their actions are white pills (the darkest surface in dark) with primary words, so Send clipboard stays the one filled button.
- The **cookie hero** (196 dp): the Mac and this phone joined by three stitches, on the 9-scallop cookie. Connected, it's primaryContainer and turns once every 40 s; paused, it morphs to the finer 12-scallop cookie in tertiaryContainer and stops; otherwise it's a still grey circle. The laptop's screen is the cookie's colour nudged toward the device ink, never the page colour. The shape morphs on the prototype's bouncy curve (0.8 s).
- The Mac's name (30 sp, a heading for TalkBack), the status chip ("● Connected" and the path's icon, Wi‑Fi or globe; a tap shows which path in a snackbar; TalkBack reads "Connected · Same Wi‑Fi"; the dot sends out a ring every 2.4 s while connected; tertiary while paused, "Paused · until 14:44"; at least 24 dp in from each page edge, so a long status wraps to two balanced lines, the dot travelling with the first line's words), and a borderless line of icons under it (14 sp medium), each with a 48 dp tap area: the shield and "Encrypted" (tap: "End-to-end encrypted: only this phone and your Mac can read what Seam sends", in the snackbar), a thin 16 dp rule, then the Mac's battery (an outline battery like the other outline glyphs, its level a bar inside, a bolt inside while charging) and free storage ("64%", "140 GB", counting to each new reading, each in warn when it needs attention (battery at 15% or below and not charging, storage 90% or more used), hidden until the Mac has sent them, and a chevron after them, which belongs to the stats alone; out of touch, the last reading keeps its onSurfaceVariant ink (outline would fall under 4.5:1 at 14 sp) and says how old it is after the stats, "as of 14:02", on screen and to TalkBack: on the same line only while it stays within 85% of the width (the stats' own line once "Encrypted" sits above them), otherwise on a line of its own, centred under the whole line (Encrypted, rule, stats and chevron) and tapping like the stats, or under the stats alone when "Encrypted" sits above them; when the line can't fit at all (large text), "Encrypted" goes centred on a line of its own above the stats and their chevron, which stay together, without the rule). Tapping the battery and storage opens a sheet titled "Mac status" (not the Mac's name, already in large type behind it) with the Mac's battery (with its power and time: "64% · Using 9.5 W · about 5 hr left", or "Charging at 30 W · full in 1 hr 10 min"), storage, CPU and memory in full as one block of read-only rows (one surface split by thin outlineVariant rules, no fill or corners per row, and a plain grey icon beside the words, no disc, since nothing there is tappable: battery, storage ("360 GB of 500 GB used · 140 GB free", ending "· almost full" at 90% or more used), CPU and memory, each with M3's linear indicator under its words (round ends, a gap before the track, the stop dot) that fills when the sheet opens, in primary, or warn for a low battery or almost-full storage), asked for afresh every 10 s while it's open, with how fresh it is under the title ("Updated just now", "Updated today at 14:02"). A Mac without a battery shows storage only.
- A button group: **Send clipboard** (primary) and **Pause** (tonal, primaryContainer), 4 : 1. Pressing one widens it and tightens its corners. Paused, Pause folds away and the main button becomes **Resume**.
- "Copied on your Mac · just now" with **Paste** (the cards' pill: white in light, a tone lighter than the card in dark), while a Mac copy is fresh; TalkBack announces it and the copied text as it unfolds. Paste turns green and says "✓ Pasted", then the card folds away.
- A grouped list: **Mac notifications ›** ("On · 6 of 10 apps", "Paused · 6 of 10 apps" while the phone is paused, or "Off"; the same line in Settings and on the page's card) and **Automatic clipboard ›**, a plain row whose line says whether it's on ("On · sends your copies", "Off · tap Send clipboard after copying"; while the phone is paused, the chip's words: "Paused · until 14:44"), the same title and line as in Settings, with the clipboard icon struck through while it's off.

**Behaviour:**
- Send clipboard turns green and says "✓ Sent to Mac" for 1.3 s, with the confirm tick, when it went; "Sending" when the connection is still starting (still primary, no tick: it doesn't claim it was sent, SR-8-04), and "Will send when MacBook Pro is back" in a snackbar when the Mac is out of reach. With clipboard sync off on the Mac, Send greys out and "Send needs clipboard sync on your Mac" unfolds right under the buttons (the Automatic clipboard row already says sync is off), centred in balanced lines.
- The hero holds its place when the "Copied on your Mac" card or the clipboard-sync footnote unfolds: they take room only from below.
- Pause opens the Pause sheet: 30 minutes, 1 hour, until tomorrow morning, until I resume, each led by a clock (a pause sign for until I resume) and saying when it ends ("Until 15:12", "At 08:00", "No end time"). Picking one gives the confirm tick. The sheet scrolls when its options don't fit, and pulls down to close only from the top. The notification's Pause button opens the same sheet. Resume says "Seam is back on".
- Automatic clipboard can't be switched from the phone alone, so it has no switch: tapping the row explains how (on the Mac, or through Accessibility).

**States:** connected (Same Wi‑Fi: solid stitches that draw themselves in; Tor: dashes flowing along), offline ("Offline · will send when it's back", broken grey stitches, each snapped in two with a clear gap in the middle, still; paused draws the same snapped stitches in the paused ink), paused, idle ("Paired · connects when there's something to sync"), and Tor's own problems in words. Not paired: the pairing intro.

## A02 Pairing (`AndPairing.dc.html`, restyled with the same parts; follows the light or dark theme)

**Purpose:** scan and confirm.

**Layout:** a full-bleed camera viewfinder with teal corner brackets and "Point at the code on your Mac". After a scan, the corner brackets close in on it (to 88%) and fade as the house sheet rises over it, so the scan visibly lands (with animations off they only fade): "Pair with MacBook Pro?", "Only if it's your Mac, the one in front of you", **Pair** and Cancel; under every step, "Keys never leave this phone, sealed by its security chip". After Pair, each step swaps in place, its title with it: "Waiting for MacBook Pro", "Keep Seam open on MacBook Pro" over three grey dots travelling in a row (the hero's connecting stitch, still under reduced motion), then "Check the code", "Make sure MacBook Pro shows the same code, then confirm there" with the 6-digit code in the large title style with tabular digits, grouped "482 913" by an en space (TalkBack reads it digit by digit, "Code 4 8 2, 9 1 3"), and "Codes don't match". The scanner's bar keeps the small title beside the back arrow, not the large title: the camera needs the height, and nothing scrolls to fold it into. The intro before scanning shares one page with Setup, so the step between them doesn't jump: Home's Seam mark and wordmark at the top, the teal cookie hero (176 dp, still) at the same place under it, "Pair with your Mac" and "Open Seam on your Mac and scan the code it shows. That's the whole setup." centred below it (the paragraph 24 dp narrower each side than the title, in balanced lines, as on Setup), then two plain lines of guidance, centred as one block with their icons lined up (a grey icon beside the words, 20 dp for the outline laptop and 18 dp for the filled Seam mark so the two weigh the same, no row cards, since nothing there is tappable) saying where the code is, in the Mac app's words: "Seam shows it on your Mac", "As soon as it first opens" (laptop) and "Not showing?", "Seam in the menu bar › Pair your phone" (the Seam mark), and **Scan code** pinned to the bottom. The guidance sits in the free height between the words and Scan code, a third of it above and two thirds below so it reads as centred (the page still scrolls when there's none), so the words and cookie stay where Setup has them.

**Behaviour:** Pair runs the Noise XX handshake and then Android's **companion device** association dialog (the system dialog, shown right after). "Codes don't match" aborts and tells the Mac. The sheet closes like any house sheet (drag down, back, a tap outside), and closing it cancels: before Pair it's Cancel, after it it's "Codes don't match". No spinner: waiting is said in words, with the travelling dots under them. The camera permission is asked in context, the first time only.

**States:** camera denied: in place of the viewfinder, a calm empty state on the page's surface with no corner brackets or tip (its words 24 dp further in than its title, in balanced lines): a camera-off glyph in a 96 dp tonal circle, "Seam needs the camera", "Only to read your Mac's code. No pictures leave this phone." and **Allow camera**; once Android stops asking, "Camera is off for Seam", "Without it, Seam can't read your Mac's code. Turn it on in Settings › Permissions › Camera, then come back." and **Open Settings**. A refused or expired code: "That pairing code didn't work. Show a new one on your Mac and scan again" (the phone can't tell the two apart). *Planned, not built:* "Pair with a code instead" (type the 6 digits shown on the Mac) and a separate "That code has expired" message; the pairing code carries the Mac's keys and address, so both need a protocol change first.

**Done when:** scan to paired takes under 10 s, including the companion dialog.

## A03 Set-up checklist (`AndPermissions.dc.html`, restyled with the same parts)

**Purpose:** one guided pass for the permissions, all optional.

**Layout:** the pairing intro's page: the same wordmark bar and the teal cookie in the same place, its three stitches drawing in, over the centred headline "You're paired" and "Seam only asks for what it uses with MacBook Pro…" (the Mac's name never splits across lines), then a grouped list, all scrolling when it doesn't fit above the one action pinned below. The free height goes between the words and the list, so the list and its footnote sit just above the action and the cookie stays where the intro has it. Built rows: **Seam's alerts** (Seam's own notifications, "Sync status and nudges"; the whole row asks, as Phone notifications' does), **Phone notifications** (notification access, "Sends them to your Mac", a phone with a badge, so it never looks like Seam's alerts), and Automatic clipboard. The board's full set:

| Row | Permission | Notes |
|---|---|---|
| Stay connected to your Mac | Companion device (done during A02) | Shows a filled tick |
| Phone notifications | Notification listener access | Primary "Allow" (filled) |
| Texts on your Mac | SMS (`full` only) | Hidden in `play` |
| Keep working in the background | Ignore battery optimisation, plus an OEM guide if needed | |
| Clipboard tile | Adds the Quick Settings tile (`requestAddTileService`) | |

A footnote, centred as the page and sitting just above the action: "Mac copies reach this phone without any of these." Then one button: a full-width text button **Skip for now** (so it never competes with a tonal Allow) until every row is allowed, then a filled **Done**.

**Behaviour:** each Allow opens the exact system screen, and the row turns into a tick on return. Once Android stops showing the notifications prompt (denied twice), Allow opens Seam's notification settings instead. Automatic clipboard is optional, so its Allow is tonal, not filled; it explains first (Accessibility, and the way past Restricted setting), then opens Accessibility.

**Done when:** each row reflects the real permission state after returning from system settings, with no stale ticks.

## A04 Share to Mac (`AndShare.dc.html`)

**Purpose:** sending from any app.

**Built (text only):** "Send to Mac" in the share sheet opens the house sheet over the source app: "Send to MacBook Pro", under it Home's status chip itself (the same chip, words and path icon, not a copy) with room above and below, kept 48 dp short of the sheet's width so at large text it wraps to two balanced lines, the text in a card (8 lines, then "And 1,204 more characters, not shown"), the fine print "To its clipboard, end-to-end encrypted" under it (the chip's pill edge and the fine print on the title's text edge, the chip's words start-aligned when they wrap), **Send** (or **Send all**), and Cancel. Send takes a tap only after half a second (SR-11-02). When it goes, the button turns green and says "✓ Sent to Mac", then the sheet closes; while the connection is still starting it says "Sending". When it's queued for later, the button says "Will send when MacBook Pro is back" (not green), then the sheet closes the same way. When it's refused, the title becomes "Can't send to MacBook Pro", the chip and the fine print go (the link's words would promise what won't happen), the reason sits in a calm raised card like the text's, with an error-coloured info icon and is the one message (8 dp more room above it, where the chip was), Send folds away, and Close (Send's own big button in its tonal colours, primaryContainer in light as Pause is, since secondaryContainer barely parts from the light sheet: full width, the 56 dp and 16 sp of the Send it replaces) is the one action. Clipboard sync off on the Mac is known before the tap, so the sheet opens already like that ("MacBook Pro has clipboard sync off. Turn it on in Seam on your Mac."); anything else it can't send unfolds that way after Send.

*Planned, not built:* Seam as a **direct share target** "MacBook Pro · Seam", first in the row; files, with the preview (stacked thumbnails, count, size) and an in-sheet progress card ("Sending to MacBook Pro · Same Wi‑Fi, direct · 5.1 of 8.3 MB", with Cancel); the transfer carrying on as a foreground-service notification after the sheet is dismissed, with the Mac's own banner on arrival; links opening on the Mac (Handoff-style).

**Done when:** tapping the target and dismissing the sheet completes the send in the background, even if the source app is closed.

## A05 Activity (`AndActivity.dc.html`)

**Purpose:** what moved, for the last 7 days.

**Layout:** a back button, the "Activity" hero headline, filter chips (All, Files, Clipboard, Replies), and grouped lists by day. Each row has a direction glyph, a title, "Sent to Mac" or "From Mac clipboard" with the time, inline progress when live, and an open arrow on received files.

**States:** empty: "Nothing yet. Things you send or receive show up here".

**Done when:** the list stays smooth at 500 rows and entries older than 7 days are deleted.

## A06 Settings (built)

**Layout:** back, and a 34 sp title that scrolls with the page and fades over the first 60 dp of scroll; past that a 20 sp title fades in beside the arrow and the bar turns surfaceContainer. Then grouped sections:
- **On your Mac:** Mac notifications ›, Automatic clipboard.
- **Connection:** Same Wi‑Fi ("Direct, at home or work"), Through Tor ("From anywhere, privately"). The last one on can't be turned off, and the footnote says what the pair means.
- **Privacy & security:** End-to-end encrypted ("Tap to see the pairing code"): tapping the row (an eye at its end) swaps those words for the pairing code ("Code 482 913"), and tapping again swaps them back; TalkBack reads the code digit by digit ("Code 4 8 2, 9 1 3"). Then **Keep passwords on this phone** (on: "Never sent to your Mac"; off: "Sent to your Mac too").
- **Updates** (GitHub build): "Check daily" (or how often it's set to), How often (a sheet), and the version row that checks now ("Checking", "✓ Up to date"), with the note about Install unknown apps (SR-17-14).
- **Your Mac:** its name, "Paired 14 Sept 2026" (the phone's medium date format; saved when pairing finishes, cleared when it's forgotten, hidden when unknown), and **Forget** as an error-tone pill (errorContainer in light; in dark a 24% error tint under error words, so it's no louder than in light), which asks first ("This phone stops syncing and deletes its keys. MacBook Pro forgets it too when it's next online. You can pair again any time.").
- **About:** the version (Play build: "Seam 0.2.3", led by the Seam mark; debug builds add the build code and flavour, and say "Debug build · updates off") and the source ("Open source", led by an open-in-new arrow and no chevron: it opens GitHub in the browser).

## Mac notifications (built)

Settings › Mac notifications, also opened from Home; the row and the page share the one name.
- **Show on your Mac**, a big primaryContainer card with its switch and the feature's one line ("On · 6 of 10 apps"); off, the rest dims and can't be changed. Without notification access it reads "Turn on access above first" (that line stays full strength while the title dims), its switch is off in Material's disabled colours (over the card, so its outline shows), and everything under the card folds away for an empty state like the pairing screen's camera one, 24 dp under the card so the two read as one: a crossed-out eye in a 96 dp tonal circle, "Seam can't see your notifications yet", and under it "Seam only reads notifications to show them on your Mac. Nothing is kept."
- Dismiss on both (a double tick; "Clear on one, gone on both"), and Quiet while in use (a moon; "Silent on your Mac while you use this phone").
- A search field, then the apps, each led by its own icon (a neutral app glyph when it has none, never the Seam mark), with a round mode chip, all one width (at least "Sender only"'s, the words centred) so their edges line up (On teal, Sender only outlined with no fill, Off grey (surfaceDim in light, so it still reads as a chip on its row); on a picked row each chip goes white, or a raised grey in dark, with its colour in the words, Sender only keeping its outline). Tapping one opens a sheet of radio cards, titled with the app's name after its icon (24 dp, the row's own); long-pressing one, or **Select**, starts picking several: a picked row's icon gives way to a filled teal tick (the icon shrinks out as the tick springs in; a checkbox to TalkBack) and the row takes a secondaryContainer fill, its corners rounding to 16 dp, everything above the list dims (to 55% in light, where the mint card also eases to the plain grey card colour so only the toolbar stays mint; 40% in dark) and can't be changed, its switches keeping their on colours under the dim so an on switch still reads on, the large title folds away and the bar turns contextual (a tone up, ✕ in place of back, "2 selected" as its title, and **All**/**None** at its end), and a full-width floating toolbar in primaryContainer, with no edge (M3 Expressive's vibrant toolbar; fading instead of rising with animations off), holds, 12 dp in at the sides and foot and 16 dp above, a caption ("Set 2 apps to", medium weight) over the three modes as one connected group of three alike buttons (white in light, a tone up from the toolbar in dark) (2 dp apart, 18 dp corners only at the group's ends so they nest in the toolbar's 30), so none reads as already chosen (faded to a bare tone and 38% words until an app is picked, the caption then "Pick apps to change"; all three one width and one height, a label on one line at default size, "Sender only" shortened to "Sender" at 1.3x text and up, wrapping to two if still too long; TalkBack hears "Set 2 apps to Sender only"); choosing one sets them all, with a confirm haptic and a snackbar saying what changed, with Undo putting each app back to its own mode. ✕ or back ends picking. While picking, the list's end grows by the toolbar's measured height, so the last row scrolls clear of it at any text size. A row a search filters out slides 30 dp toward the trailing edge as it fades and collapses. A search that matches nothing shows "No apps match “x”".
- The access row (**Phone notifications**, "Sends them to your Mac", "Allow") shows only while notification access is off. Back from Android's settings still without it, a dialog says how past Android's Restricted setting block.

## Moving between screens (built)

Forward, the new screen slides in from 30% to the right while the old drifts 18% left. Back, by gesture (predictive back) or the arrow, the screen first shrinks to 0.9, leans right and rounds its corners, then leaves. Sheets keep one 16 dp gutter for their cards, buttons and chips, as grouped lists do; their title and words sit 8 dp further in. Sheets rise from below on the prototype's spring, their parts rising in one after another; drag a sheet down or swipe back to close it. Dialogs scale up from 0.85, and confirmations of things that happened elsewhere are snackbars. With animations off, these become crossfades and the loops stop.

**Checking against the prototype:** debug builds have a screen gallery (`GalleryActivity`, debug only) that shows the real screens with the prototype's sample data, and `./gradlew :app:testFullDebugUnitTest --tests '*ScreensTest*' -Pscreens` renders every state, light and dark, to `app/build/screens/`.

## A07 System surfaces (`AndSystem.dc.html`)

**Purpose:** Seam where Android already is. Drawn on the dark shade.

| Surface | Detail |
|---|---|
| **Quick Settings tile** | "Send to Mac · Clipboard". Tapping it sends the current clip (the tile gets the focus it needs to read it), and a toast says what happened in Home's words ("✓ Sent to Mac", "Will send when MacBook Pro is back") |
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
