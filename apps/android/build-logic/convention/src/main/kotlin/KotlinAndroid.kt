// SPDX-License-Identifier: AGPL-3.0-only
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/** Settings every Android module shares. AGP's built-in Kotlin targets the same JVM as Java. */
internal fun Project.configureKotlinAndroid(android: CommonExtension) {
    android.apply {
        compileSdk = 37 // material3 1.5 (Expressive) needs it; targetSdk is set separately
        defaultConfig.minSdk = 31 // Android 12: CompanionDeviceManager presence
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
    }
}
