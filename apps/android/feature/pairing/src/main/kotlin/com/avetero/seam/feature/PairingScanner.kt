// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature

import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.avetero.seam.session.PairingQr
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ScanResult {
    data class Code(val qr: PairingQr) : ScanResult
    /** Read a QR code that isn't a Seam pairing code. */
    data object NotSeam : ScanResult
}

/** Reads a QR code from a camera frame's brightness plane (Y of YUV), or null if there isn't one. */
object QrDecoder {
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? = try {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: ReaderException) {
        null
    }
}

/**
 * The live camera, reading QR codes on the phone. [active] false pauses reading (the camera stays on
 * behind the sheet). After a pairing code is read, nothing else is reported until [active] goes false
 * and back (SR-11-15). The camera closes when this leaves the screen. Needs the CAMERA permission.
 */
@Composable
fun QrViewfinder(active: Boolean, allowCleartext: Boolean, onResult: (ScanResult) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val reading = rememberUpdatedState(active)
    val report = rememberUpdatedState(onResult)
    val latched = remember { AtomicBoolean(false) }
    LaunchedEffect(active) { if (active) latched.set(false) }
    val worker = remember { Executors.newSingleThreadExecutor() }
    val controller = remember {
        val main = Handler(Looper.getMainLooper())
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            setImageAnalysisAnalyzer(worker) { image ->
                image.use {
                    if (!reading.value || latched.get()) return@use
                    val plane = it.planes[0]
                    val bytes = ByteArray(plane.buffer.remaining()).also { b -> plane.buffer.get(b) }
                    val raw = QrDecoder.decode(bytes, plane.rowStride, it.width, it.height) ?: return@use
                    val qr = PairingQr.parse(raw, allowCleartext)
                    if (qr == null) main.post { report.value(ScanResult.NotSeam) }
                    else if (latched.compareAndSet(false, true)) main.post { if (reading.value) report.value(ScanResult.Code(qr)) }
                }
            }
        }
    }
    DisposableEffect(owner) {
        controller.bindToLifecycle(owner)
        onDispose {
            controller.unbind()
            worker.shutdown()
        }
    }
    AndroidView(
        factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; this.controller = controller } },
        modifier = modifier,
    )
}
