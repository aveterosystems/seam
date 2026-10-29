// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.seam.android.application)
    alias(libs.plugins.seam.android.compose)
}

android {
    namespace = "com.avetero.seam"
    defaultConfig {
        applicationId = "com.avetero.seam"
        versionCode = 2
        versionName = "0.1.0"
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":core:session"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:status"))
    implementation(project(":feature:clipboard"))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(libs.junit)
}
