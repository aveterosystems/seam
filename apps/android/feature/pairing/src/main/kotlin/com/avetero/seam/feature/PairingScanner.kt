// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature

import android.app.Activity
import com.avetero.seam.session.PairingQr
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface ScanResult {
    data class Code(val qr: PairingQr) : ScanResult
    /** Scanned something that isn't a Seam pairing code. */
    data object NotSeam : ScanResult
    data object Cancelled : ScanResult
    data class Unavailable(val reason: String) : ScanResult
}

/** Opens the system QR scanner and reads a Mac's pairing code. */
object PairingScanner {
    suspend fun scan(activity: Activity): ScanResult = suspendCancellableCoroutine { cont ->
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { barcode ->
                val qr = barcode.rawValue?.let(PairingQr::parse)
                cont.resume(if (qr != null) ScanResult.Code(qr) else ScanResult.NotSeam)
            }
            .addOnCanceledListener { cont.resume(ScanResult.Cancelled) }
            .addOnFailureListener { cont.resume(ScanResult.Unavailable(it.message ?: "scanner unavailable")) }
    }
}
