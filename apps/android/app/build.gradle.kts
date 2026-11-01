// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.seam.android.application)
    alias(libs.plugins.seam.android.compose)
}

android {
    namespace = "com.avetero.seam"
    defaultConfig {
        applicationId = "com.avetero.seam"
        versionCode = 8
        versionName = "0.2.4"
        // The updater installs only APKs signed by this certificate (release/android-certificate.sha256)
        val pin = rootProject.file("../../release/android-certificate.sha256").readText().trim()
        require(Regex("[0-9a-f]{64}").matches(pin)) { "release/android-certificate.sha256 must be one SHA-256" }
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$pin\"")
    }
    buildFeatures { buildConfig = true }
    // The screen renders (ScreensTest) need the app's resources on the JVM
    testOptions.unitTests.isIncludeAndroidResources = true
}

// ScreensTest renders every screen state to app/build/screens/ for comparing with the approved
// prototype; only with -Pscreens, so ordinary test runs stay quick
tasks.withType<Test>().configureEach {
    if (project.hasProperty("screens")) {
        systemProperty("seam.screens", rootDir.resolve("app/build/screens").path)
        systemProperty("roborazzi.test.record", "true")
        outputs.upToDateWhen { false }
    }
}

dependencies {
    implementation(project(":core:session"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:status"))
    implementation(project(":feature:clipboard"))
    implementation(project(":feature:notifications"))
    implementation(libs.activity.compose)
    // material3 used to bring these in; 1.5 no longer does
    implementation(libs.compose.material.icons)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
    // The real org.json for UpdateFeedTest (Android's is a stub on the JVM)
    testImplementation(libs.json)
}
