// SPDX-License-Identifier: AGPL-3.0-only
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            defaultConfig.targetSdk = 35
            // Only the ABIs core:crypto builds the Rust core for; JNA alone would add x86/MIPS without it
            defaultConfig.ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            // play: Play Store build, no SMS. full: sideloaded from GitHub Releases, adds SMS and
            // automatic clipboard (see docs/platforms/android-limits.md).
            flavorDimensions += "store"
            productFlavors {
                create("play") { dimension = "store" }
                create("full") { dimension = "store"; applicationIdSuffix = ".full" }
            }
        }
    }
}
