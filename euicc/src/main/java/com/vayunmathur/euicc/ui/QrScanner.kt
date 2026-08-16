package com.vayunmathur.euicc.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.OptIn as AndroidOptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberPermissionRequest
import java.util.concurrent.Executors

/**
 * Full-screen QR scanner for eSIM activation codes. Requests the camera
 * permission, shows a CameraX preview, and calls [onResult] with the first
 * decoded activation code (a string containing `$`, optionally `LPA:`-prefixed).
 */
@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestCamera = rememberPermissionRequest(Manifest.permission.CAMERA) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) requestCamera()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (hasPermission) {
            CameraScanner(onResult = onResult)
        } else {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Camera permission is required to scan a QR code.")
            }
        }
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun CameraScanner(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var delivered by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analyzer ->
                analyzer.setAnalyzer(executor, QrCodeAnalyzer { text ->
                    if (!delivered && looksLikeActivationCode(text)) {
                        delivered = true
                        ContextCompat.getMainExecutor(context).execute { onResult(text) }
                    }
                })
            }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun looksLikeActivationCode(text: String): Boolean =
    text.startsWith("LPA:", ignoreCase = true) || text.contains('$')

/** ImageAnalysis analyzer that decodes QR codes with ZXing. */
private class QrCodeAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader()

    @AndroidOptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val source = PlanarYUVLuminanceSource(
            bytes,
            plane.rowStride,
            imageProxy.height,
            0, 0,
            imageProxy.width,
            imageProxy.height,
            false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        try {
            onDecoded(reader.decodeWithState(bitmap).text)
        } catch (_: NotFoundException) {
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }
}
