plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.avetero.seam.spike"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.avetero.seam.spike"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-spike"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
