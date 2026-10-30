// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.seam.android.application)
    alias(libs.plugins.seam.android.compose)
}

android {
    namespace = "com.avetero.seam"
    defaultConfig {
        applicationId = "com.avetero.seam"
        versionCode = 4
        versionName = "0.2.0"
        // The updater installs only APKs signed by this certificate (release/android-certificate.sha256)
        val pin = rootProject.file("../../release/android-certificate.sha256").readText().trim()
        require(Regex("[0-9a-f]{64}").matches(pin)) { "release/android-certificate.sha256 must be one SHA-256" }
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$pin\"")
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":core:session"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:status"))
    implementation(project(":feature:clipboard"))
    implementation(project(":feature:notifications"))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    // The real org.json for UpdateFeedTest (Android's is a stub on the JVM)
    testImplementation(libs.json)
}
