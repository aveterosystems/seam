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
    sourceSets["main"].jniLibs.srcDir(jniOut)
}

fun Exec.rustInputs() {
    workingDir = coreDir
    inputs.files(fileTree(coreDir) { include("seam-*/src/**", "seam-*/Cargo.toml", "Cargo.lock") })
}

// libseam_ffi.so for each ABI the app ships (needs the NDK, cargo-ndk and the rustup Android targets)
val cargoNdk by tasks.registering(Exec::class) {
    rustInputs()
    outputs.dir(jniOut)
    environment("ANDROID_NDK_HOME", android.ndkDirectory.path)
    commandLine(
        cargo, "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", jniOut.get().asFile.path, "build", "--release", "-p", "seam-ffi",
    )
}

// A Mac/Linux build of the same library, so JVM unit tests exercise the real Rust code
val cargoHost by tasks.registering(Exec::class) {
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
