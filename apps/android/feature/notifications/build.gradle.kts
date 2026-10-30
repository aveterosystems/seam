// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.seam.android.library)
}

android {
    namespace = "com.avetero.seam.feature.notifications"
}

dependencies {
    implementation(project(":core:session"))
    testImplementation(libs.junit)
    // The real org.json for the settings tests (Android's is a stub on the JVM)
    testImplementation(libs.json)
}
