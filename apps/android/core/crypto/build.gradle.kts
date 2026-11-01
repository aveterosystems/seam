// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.seam.android.library)
}

// The Rust crypto core lives at the repo root; this module packages it and its generated Kotlin bindings.
val coreDir = rootDir.resolve("../../core")
val jniOut = layout.buildDirectory.dir("rustJniLibs")
val cargo = System.getenv("CARGO")
    ?: File(System.getProperty("user.home"), ".cargo/bin/cargo").takeIf { it.exists() }?.path
    ?: "cargo"

android {
    namespace = "com.avetero.seam.crypto"
    ndkVersion = "29.0.14206865"
    sourceSets["main"].jniLibs.directories.add(jniOut.get().asFile.path) // the merge task depends on cargoNdk below
}

fun Exec.rustInputs() {
    workingDir = coreDir
    // build.rs compiles protocol/seam/v1 into the core: a proto change must rebuild it, or the
    // core silently drops payload fields it doesn't know
    inputs.files(fileTree(coreDir) { include("seam-*/src/**", "seam-*/build.rs", "seam-*/proto/**", "seam-*/Cargo.toml", "Cargo.lock") })
    inputs.dir(coreDir.resolve("../protocol/seam/v1"))
}

// libseam_ffi.so for each ABI the app ships (needs the NDK, cargo-ndk and the rustup Android targets)
val cargoNdk = tasks.register<Exec>("cargoNdk") {
    rustInputs()
    outputs.dir(jniOut)
    environment("ANDROID_NDK_HOME", androidComponents.sdkComponents.ndkDirectory.get().asFile.path)
    commandLine(
        cargo, "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", jniOut.get().asFile.path, "build", "--release", "-p", "seam-ffi",
    )
}

// A Mac/Linux build of the same library, so JVM unit tests exercise the real Rust code
val cargoHost = tasks.register<Exec>("cargoHost") {
    rustInputs()
    outputs.dir(coreDir.resolve("target/debug"))
    commandLine(cargo, "build", "-p", "seam-ffi")
}

// Only packaging needs the per-ABI build; unit tests use cargoHost instead
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoNdk) }
tasks.withType<Test>().configureEach {
    dependsOn(cargoHost)
    systemProperty("jna.library.path", coreDir.resolve("target/debug").path)
}

dependencies {
    implementation(libs.jna) { artifact { type = "aar" } }
    testImplementation(libs.jna)
    testImplementation(libs.junit)
}
