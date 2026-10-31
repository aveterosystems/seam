// SPDX-License-Identifier: AGPL-3.0-only
import java.time.Duration
plugins {
    alias(libs.plugins.seam.android.library)
}

android {
    namespace = "com.avetero.seam.session"
}

// Session tests pair and talk through the real Rust core, built for this machine by core:crypto
val coreDir = rootDir.resolve("../../core")
tasks.withType<Test>().configureEach {
    // A test stuck in an endless retry fails here instead of hanging CI
    timeout.set(Duration.ofMinutes(5))
    dependsOn(":core:crypto:cargoHost")
    systemProperty("jna.library.path", coreDir.resolve("target/debug").path)
}

dependencies {
    api(project(":core:crypto"))
    api(project(":core:protocol"))
    api(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.jna)
    testImplementation(libs.coroutines.test)
}
