package com.telefam.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.metadataObjects
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIView
import platform.darwin.NSObject

/**
 * Real QR scanner: AVFoundation back-camera capture session whose metadata output
 * decodes QR codes fully on-device. The first decoded payload is delivered once;
 * the caller disables/re-enables the scanner between scans.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QrScannerCamera(
    enabled: Boolean,
    onDetected: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier
) {
    val state = remember {
        object {
            var session: AVCaptureSession? = null
            var delivered = false
            // Retained so the metadata delegate isn't collected while the session runs.
            var delegate: platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol? = null
        }
    }

    DisposableEffect(enabled) {
        if (!enabled) state.delivered = false
        onDispose { }
    }

    UIKitView(
        factory = {
            val view = UIView()
            try {
                val session = AVCaptureSession()
                val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
                    ?: throw IllegalStateException("No camera available")
                val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null)
                    ?: throw IllegalStateException("Camera unavailable")
                if (session.canAddInput(input)) session.addInput(input)

                val output = AVCaptureMetadataOutput()
                if (session.canAddOutput(output)) session.addOutput(output)
                val delegate = object : NSObject(), platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol {
                    override fun captureOutput(
                        output: platform.AVFoundation.AVCaptureOutput,
                        didOutputMetadataObjects: List<*>,
                        fromConnection: platform.AVFoundation.AVCaptureConnection
                    ) {
                        if (state.delivered) return
                        val obj = didOutputMetadataObjects.firstOrNull() as? platform.AVFoundation.AVMetadataMachineReadableCodeObject
                        val raw = obj?.stringValue
                        if (!raw.isNullOrBlank()) {
                            state.delivered = true
                            onDetected(raw)
                        }
                    }
                }
                state.delegate = delegate
                output.setMetadataObjectsDelegate(delegate, NSOperationQueue.mainQueue.underlyingQueue)
                output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)

                val layer = AVCaptureVideoPreviewLayer.layerWithSession(session)
                layer.videoGravity = AVLayerVideoGravityResizeAspectFill
                layer.frame = view.bounds
                view.layer.addSublayer(layer)

                session.startRunning()
                state.session = session
            } catch (e: Throwable) {
                onError(e.message ?: "Camera unavailable")
            }
            view
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            state.session?.stopRunning()
            state.session = null
            state.delegate = null
        }
    }
}
