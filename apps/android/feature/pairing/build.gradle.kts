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
    // Board A02's own viewfinder. The barcode model is bundled: frames never leave the phone,
    // and scanning works without Google Play services.
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.mlkit.vision)
    implementation(libs.barcode.scanning)
    implementation(libs.lifecycle.runtime.compose)
}
