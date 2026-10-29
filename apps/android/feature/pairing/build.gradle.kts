// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.seam.android.library)
    alias(libs.plugins.seam.android.compose)
}

android {
    namespace = "com.avetero.seam.feature.pairing"
}

dependencies {
    implementation(project(":core:session"))
    // Board A02's own viewfinder. ZXing decodes on the phone and has no network code; ML Kit was
    // ruled out because it brings Google's telemetry (SR-11-01).
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(libs.junit)
}
