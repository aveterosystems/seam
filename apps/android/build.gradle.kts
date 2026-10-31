// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Never applied (AGP 9 builds Kotlin itself); on the classpath so it compiles with this Kotlin
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.protobuf) apply false
}
