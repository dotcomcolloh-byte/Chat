package com.telefam.verification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.*
import platform.AVFoundation.*
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImageView
import platform.UIKit.UIViewController
import platform.Vision.*
import platform.VisionKit.VNDocumentCameraViewController
import platform.VisionKit.VNDocumentCameraViewControllerDelegateProtocol
import platform.VisionKit.VNDocumentCameraScan
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_async

/**
 * Real liveness camera on iOS: AVFoundation front-camera session analysed
 * frame-by-frame by the Vision face-landmarks request. Head yaw/pitch come from
 * VNFaceObservation.yaw / .pitch — measured angles, never simulated. A still is
 * captured opportunistically when the face is centered.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun FaceTrackingCamera(
    onFaceAngles: (yawDeg: Float, pitchDeg: Float) -> Unit,
    onCaptureStill: (jpegBytes: ByteArray) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier
) {
    val coordinator = remember {
        IosLivenessCoordinator(onFaceAngles, onCaptureStill, onError)
    }
    DisposableEffect(Unit) {
        coordinator.start()
        onDispose { coordinator.stop() }
    }
    UIKitView(factory = { coordinator.previewView }, modifier = modifier)
}

@OptIn(ExperimentalForeignApi::class)
private class IosLivenessCoordinator(
    val onFaceAngles: (Float, Float) -> Unit,
    val onCaptureStill: (ByteArray) -> Unit,
    val onError: (String) -> Unit
) : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    val previewView = UIImageView()
    private val session = AVCaptureSession()
    private var lastCapture = 0.0

    fun start() {
        session.beginConfiguration()
        session.sessionPreset = AVCaptureSessionPresetHigh
        val device = AVCaptureDevice.defaultDeviceWithDeviceType(
            AVCaptureDeviceTypeBuiltInWideAngleCamera, AVMediaTypeVideo, AVCaptureDevicePositionFront
        ) ?: run { onError("no front camera"); return }
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: return
        if (session.canAddInput(input)) session.addInput(input)
        val output = AVCaptureVideoDataOutput()
        output.setSampleBufferDelegate(this, dispatch_get_main_queue())
        if (session.canAddOutput(output)) session.addOutput(output)
        session.commitConfiguration()
        session.startRunning()
        // Attach the preview layer over the interop view.
        val layer = AVCaptureVideoPreviewLayer.sessionLayerWithSession(session)
        layer.frame = CGRectMake(0.0, 0.0, 400.0, 700.0)
        previewView.layer.addSublayer(layer)
    }

    fun stop() = session.stopRunning()

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: platform.CoreMedia.CMSampleBufferRef?,
        fromConnection: AVCaptureConnection
    ) {
        val buffer = didOutputSampleBuffer ?: return
        val pixelBuffer = CMSampleBufferGetImageBuffer(buffer) ?: return
        val request = VNDetectFaceLandmarksRequest()
        val handler = VNImageRequestHandler(cVPixelBuffer = pixelBuffer, options = emptyMap<Any?, Any?>())
        handler.performRequests(listOf(request), null)
        val face = (request.results as? List<*>)?.filterIsInstance<VNFaceObservation>()?.maxByOrNull { it.boundingBox.size.width } ?: return
        val yaw = face.yaw?.floatValue?.let { (it * 180.0 / kotlin.math.PI).toFloat() } ?: 0f
        val pitch = face.pitch?.floatValue?.let { (it * 180.0 / kotlin.math.PI).toFloat() } ?: 0f
        onFaceAngles(yaw, pitch)
        // Opportunistic still when centered.
        val now = NSDate().timeIntervalSince1970
        if (kotlin.math.abs(yaw) < 6f && kotlin.math.abs(pitch) < 6f && now - lastCapture > 2.0) {
            lastCapture = now
            val ciImage = platform.CoreImage.CIImage(cVPixelBuffer = pixelBuffer)
            val ctx = platform.CoreImage.CIContext()
            val cg = ctx.createCGImage(ciImage, ciImage.extent)
            val ui = UIImage.imageWithCGImage(cg)
            val data = UIImageJPEGRepresentation(ui, 0.92) ?: return
            onCaptureStill(data.toByteArray())
        }
    }

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also {
        memcpy(it.refTo(0), bytes, length)
    }
}

/**
 * Real ID capture on iOS: VisionKit's VNDocumentCameraViewController — live edge
 * detection with automatic capture once all edges are verified in frame.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun DocumentCamera(
    onDocumentCaptured: (jpegBytes: ByteArray) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier
) {
    val presenter = remember {
        object : NSObject(), VNDocumentCameraViewControllerDelegateProtocol {
            override fun documentCameraViewController(
                controller: VNDocumentCameraViewController,
                didFinishWithScan: VNDocumentCameraScan
            ) {
                controller.dismissViewControllerAnimated(true, null)
                if (didFinishWithScan.pageCount < 1u) { onError("no document captured"); return }
                val image = didFinishWithScan.imageOfPageAtIndex(0u)
                val data = UIImageJPEGRepresentation(image, 0.92)
                if (data == null) { onError("capture decode failed"); return }
                onDocumentCaptured(ByteArray(data.length.toInt()).also { memcpy(it.refTo(0), data.bytes, data.length) })
            }
            override fun documentCameraViewControllerDidCancel(controller: VNDocumentCameraViewController) {
                controller.dismissViewControllerAnimated(true, null)
            }
            override fun documentCameraViewController(
                controller: VNDocumentCameraViewController,
                didFailWithError: platform.Foundation.NSError
            ) {
                controller.dismissViewControllerAnimated(true, null)
                onError(didFailWithError.localizedDescription)
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!VNDocumentCameraViewController.isSupported()) { onError("document scanning not supported"); return@LaunchedEffect }
        val vc = VNDocumentCameraViewController()
        vc.delegate = presenter
        // Present from the top-most controller in the active window scene.
        val root = platform.UIKit.UIApplication.sharedApplication.keyWindow?.rootViewController
        var top: UIViewController? = root
        while (top?.presentedViewController != null) top = top.presentedViewController
        top?.presentViewController(vc, true, null) ?: onError("no presenter")
    }
}
