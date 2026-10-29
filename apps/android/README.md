# Seam for Android

The Android app, in Kotlin and Jetpack Compose (minSdk 31). It's a multi-module Gradle build: `app`, shared `core/*` modules (protocol, crypto, transport, session, database, datastore, designsystem, service, testing) and one `feature/*` module per feature. Convention plugins live in `build-logic/` and all dependency versions in `gradle/libs.versions.toml`. There are two flavours: `play` for the Play Store and `full`, which adds SMS.
