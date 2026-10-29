# Spike: automatic clipboard, phone → Mac

Answers one question before we build Seam: can an Android phone send what you copy to a Mac with no tapping, the way an iPhone does?

How it works:
- The Mac helper grants the test app two permissions over adb, once: read system logs, and draw over other apps.
- On every copy, Android logs that it refused the app's clipboard listener. The app watches for that line.
- The app then takes focus for a split second (a 1 px overlay, or an invisible screen as a fallback) so the read is allowed, and sends the text to the Mac.
- The Mac puts it on the clipboard.

This is throwaway code. There's no pairing or encryption: it travels over the adb tunnel (`adb reverse`) instead of the Seam protocol.

## Run it

1. On the phone, turn on **Developer options → USB debugging**, plug it in and accept the prompt. For wireless, see the helper's message if no phone is found.
2. Build the app (needs JDK 17):
   ```bash
   cd spikes/clipboard/android && ./gradlew :app:assembleDebug
   ```
3. Run the helper. It installs the app, grants the permissions and starts listening:
   ```bash
   python3 spikes/clipboard/mac/seam_clip_helper.py --apk spikes/clipboard/android/app/build/outputs/apk/debug/app-debug.apk
   ```
4. If the phone asks **"Allow access to all device logs?"**, choose Allow.
5. Copy text in a few apps (Chrome, Messages, Notes), then press ⌘V on the Mac.

## What to note

| Check | Why it matters |
|---|---|
| Does each copy reach the Mac, and from which apps? | The core question |
| `overlay` or `activity` in the helper's output | Which focus trick works on this Android version |
| The ms figure | Under ~300 ms feels instant |
| A flicker, or the keyboard closing, when you copy | How invisible the trick is |
| A "pasted from your clipboard" toast | Android 12+ may show one every time |
| Does it still work after locking the phone, a few hours idle, and a reboot? | Whether the log-access prompt and the grants come back |

Timings go to `mac/results.csv`, which holds lengths only, never the copied text.
