// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.seam.android.library)
}

android {
    namespace = "com.avetero.seam.feature.status"
}

dependencies {
    implementation(project(":core:session"))
}
