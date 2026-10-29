// SPDX-License-Identifier: AGPL-3.0-only
import com.google.protobuf.gradle.proto

plugins {
    alias(libs.plugins.seam.android.library)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.avetero.seam.protocol"
    // The one schema shared by all three codebases
    sourceSets["main"].proto { srcDir(rootDir.resolve("../../protocol")) }
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("seam.testdata", rootDir.resolve("../../protocol/testdata").path)
}

dependencies {
    api(libs.protobuf.kotlin.lite)
    testImplementation(libs.junit)
}
