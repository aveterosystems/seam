plugins {
    alias(libs.plugins.seam.android.application)
    alias(libs.plugins.seam.android.compose)
}

android {
    namespace = "com.avetero.seam"
    defaultConfig {
        applicationId = "com.avetero.seam"
        versionCode = 1
        versionName = "0.0.1"
    }
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:protocol"))
    implementation(libs.activity.compose)
}
