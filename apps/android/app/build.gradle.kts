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

    // Release signing only where the key is given (the release workflow, from GitHub secrets).
    // Without it a release build stays unsigned; the key never enters the repo.
    val keystore = providers.environmentVariable("SEAM_ANDROID_KEYSTORE").orNull
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("SEAM_ANDROID_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("SEAM_ANDROID_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("SEAM_ANDROID_KEY_PASSWORD").get()
            enableV1Signing = false // Android 8+ only: v2/v3 signatures cover the whole file
            enableV2Signing = true
            enableV3Signing = true
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
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
