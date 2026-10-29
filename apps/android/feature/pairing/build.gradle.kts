// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.seam.android.library)
}

android {
    namespace = "com.avetero.seam.feature.pairing"
}

dependencies {
    implementation(project(":core:session"))
    // Google's system code scanner: its own camera UI, so Seam needs no camera permission
    implementation(libs.code.scanner)
}
