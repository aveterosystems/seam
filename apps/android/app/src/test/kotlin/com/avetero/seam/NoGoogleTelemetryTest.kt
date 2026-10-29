// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import org.junit.Assert.fail
import org.junit.Test

/**
 * SR-11-01: Google sees nothing but empty pushes (threat model), so no library may bring ML Kit or
 * Google's telemetry transport into the app. This runs against the app's own classpath.
 */
class NoGoogleTelemetryTest {
    @Test fun noMlKitOrDatatransportOnTheClasspath() {
        for (name in listOf(
            "com.google.android.datatransport.TransportFactory",
            "com.google.android.datatransport.runtime.TransportRuntime",
            "com.google.mlkit.common.MlKitException",
            "com.google.firebase.FirebaseApp",
        )) {
            val found = runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
            if (found) fail("$name is on the app classpath")
        }
    }
}
