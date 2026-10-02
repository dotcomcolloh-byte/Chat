package com.telefam.verification

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Real liveness camera: CameraX front-camera stream analysed frame-by-frame by
 * ML Kit's on-device face detector. Head yaw/pitch come from the detector's
 * headEulerAngleY/X — actual measured head pose, never simulated.
 * The still selfie is captured from the live stream via ImageCapture.
 */
@SuppressLint("UnsafeOptInUsageError")
@OptIn(ExperimentalGetImage::class)
@Composable
actual fun FaceTrackingCamera(
    onFaceAngles: (yawDeg: Float, pitchDeg: Float) -> Unit,
    onCaptureStill: (jpegBytes: ByteArray) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }

    DisposableEffect(Unit) {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // real-blink/eyes data for liveness
                .setMinFaceSize(0.25f)
                .build()
        )
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            runCatching {
                val provider = cameraProviderFuture.get()
                val preview = androidx.camera.core.Preview.Builder().build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(executor) { proxy: ImageProxy ->
                            val mediaImage = proxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                                detector.process(image)
                                    .addOnSuccessListener { faces ->
                                        faces.maxByOrNull { it.boundingBox.width() }?.let { face ->
                                            onFaceAngles(face.headEulerAngleY, face.headEulerAngleX)
                                            // Opportunistic still: when the face is centered with open eyes,
                                            // grab one real frame from the live stream for the review pipeline.
                                            val now = System.currentTimeMillis()
                                            val neutral = kotlin.math.abs(face.headEulerAngleY) < 6f && kotlin.math.abs(face.headEulerAngleX) < 6f
                                            val eyesOpen = (face.leftEyeOpenProbability ?: 1f) > 0.8f && (face.rightEyeOpenProbability ?: 1f) > 0.8f
                                            if (neutral && eyesOpen && now - lastCaptureMs > 2000) {
                                                lastCaptureMs = now
                                                takeStill(imageCapture, executor, onCaptureStill, onError)
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            } else proxy.close()
                        }
                    }
                provider.unbindAll()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis, imageCapture)
            }.onFailure { onError(it.message ?: "camera failed") }
        }, ContextCompat.getMainExecutor(context))
        onDispose { detector.close(); executor.shutdown() }
    }

    AndroidView(factory = { PreviewView(it).also { previewView = it } }, modifier = modifier)
}

private lateinit var previewView: PreviewView
private var lastCaptureMs = 0L

private fun takeStill(
    imageCapture: ImageCapture,
    executor: java.util.concurrent.Executor,
    onCaptureStill: (ByteArray) -> Unit,
    onError: (String) -> Unit
) {
    imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            image.close()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: run { onError("capture decode failed"); return }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            onCaptureStill(out.toByteArray())
        }
        override fun onError(exception: ImageCaptureException) {
            onError(exception.message ?: "capture failed")
        }
    })
}

/**
 * Real document capture: ML Kit Document Scanner — live edge detection, automatic
 * perspective correction, and it only returns when a full document with all edges
 * has been verified in frame. Front and back are captured with separate scans.
 */
@Composable
actual fun DocumentCamera(
    onDocumentCaptured: (jpegBytes: ByteArray) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL) // live edge detection + auto capture
                .setPageLimit(1)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .build()
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val page = scanResult?.pages?.firstOrNull()
        if (page == null) { onError("no document captured"); return@rememberLauncherForActivityResult }
        runCatching {
            context.contentResolver.openInputStream(page.imageUri)!!.use { it.readBytes() }
        }.fold(onSuccess = onDocumentCaptured, onFailure = { onError(it.message ?: "read failed") })
    }

    // Launch the real scanning UI immediately; `modifier` area shows a hint behind it.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity ?: run { onError("no activity"); return@LaunchedEffect }
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { onError(it.message ?: "scanner unavailable") }
    }
}
