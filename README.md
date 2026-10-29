# Seam

Seam links an Android phone to a Mac the way an iPhone links to a Mac: notifications and texts on the Mac, clipboard both ways, files in either direction, battery at a glance and "ring my phone". Everything is end-to-end encrypted: keys stay in each device's security hardware, and the relay server only ever passes along sealed messages it can't read. There are no accounts; pairing is one QR scan.

**Status:** early development.

## Layout

| Folder | What's in it |
|---|---|
| `apps/mac/` | The Mac app (Swift 6, SwiftUI, menu bar, macOS 14+) |
| `apps/android/` | The Android app (Kotlin, Jetpack Compose, Android 12+) |
| `server/` | The relay (Cloudflare Worker) that passes sealed messages between paired devices |
| `protocol/` | The Protobuf schema all three share |
| `scripts/` | Codegen and release helpers |

## Adding a feature

Each feature is one Protobuf message in `protocol/`, one module per app (`apps/mac/Packages/Features/<Name>Feature`, `apps/android/feature/<name>`) and one line in each app's `FeatureRegistry`. New features advertise a capability string, so they stay off until both devices support them.
