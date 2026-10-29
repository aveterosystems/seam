// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature

import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.avetero.seam.session.PairingQr
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

sealed interface ScanResult {
    /** [frame]: the camera's last picture, held on screen behind the sheet once the camera is off. */
    data class Code(val qr: PairingQr, val frame: ImageBitmap?) : ScanResult
    /** Read a QR code that isn't a Seam pairing code. */
    data object NotSeam : ScanResult
}

/**
 * The live camera, reading QR codes on the phone. [active] false stops reading (the camera stays on
 * behind the sheet). The camera closes when this leaves the screen. Needs the CAMERA permission.
 */
@Composable
fun QrViewfinder(active: Boolean, allowCleartext: Boolean, onResult: (ScanResult) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val reading = rememberUpdatedState(active)
    val report = rememberUpdatedState(onResult)
    val view = remember { arrayOfNulls<PreviewView>(1) }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            setImageAnalysisAnalyzer(
                ContextCompat.getMainExecutor(context),
                MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, ContextCompat.getMainExecutor(context)) { r ->
                    if (!reading.value) return@MlKitAnalyzer
                    val raw = r.getValue(scanner)?.firstOrNull()?.rawValue ?: return@MlKitAnalyzer
                    val qr = PairingQr.parse(raw, allowCleartext)
                    report.value(if (qr != null) ScanResult.Code(qr, view[0]?.bitmap?.asImageBitmap()) else ScanResult.NotSeam)
                },
            )
        }
    }
    DisposableEffect(owner) {
        controller.bindToLifecycle(owner)
        onDispose {
            controller.unbind()
            scanner.close()
        }
    }
    AndroidView(
        factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; this.controller = controller; view[0] = this } },
        modifier = modifier,
    )
}
