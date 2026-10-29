// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.seam.android.library)
}

android {
    namespace = "com.avetero.seam.feature.clipboard"
}

dependencies {
    implementation(project(":core:session"))
    testImplementation(libs.junit)
}
