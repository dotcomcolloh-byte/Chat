package com.telefam.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

private var pickerCounter = 0L

private fun topController(): UIViewController? {
    val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull() as? UIWindowScene
    var controller = scene?.windows?.firstOrNull()?.rootViewController
    while (controller?.presentedViewController != null) controller = controller?.presentedViewController
    return controller
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { out ->
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
}

private fun persist(bytes: NSData, suggestedName: String?): PickedFile? {
    val name = suggestedName ?: "file"
    val target = "${AppFiles.baseDir}/picked_${++pickerCounter}_$name"
    return if (writeLocalFile(target, bytes.toByteArray())) {
        PickedFile(target, name, null, bytes.length.toLong())
    } else null
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberMediaPicker(kind: PickKind, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val photoDelegate = remember {
        object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, null)
                val result = didFinishPicking.firstOrNull() as? PHPickerResult
                if (result == null) { onPicked(null); return@override }
                val typeId = if (kind == PickKind.VIDEO) "public.movie" else "public.image"
                result.itemProvider.loadFileRepresentationForTypeIdentifier(typeId) { url, _ ->
                    val bytes = url?.let { NSData.dataWithContentsOfURL(it) }
                    val out = bytes?.let { persist(it, url.lastPathComponent) }
                    dispatch_async(dispatch_get_main_queue()) { onPicked(out) }
                }
            }
        }
    }
    val docDelegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                controller.dismissViewControllerAnimated(true, null)
                val url = didPickDocumentsAtURLs.firstOrNull() as? platform.Foundation.NSURL
                val bytes = url?.let { NSData.dataWithContentsOfURL(it) }
                onPicked(bytes?.let { persist(it, url.lastPathComponent) })
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                controller.dismissViewControllerAnimated(true, null)
                onPicked(null)
            }
        }
    }
    return {
        when (kind) {
            PickKind.IMAGE, PickKind.VIDEO -> {
                val config = PHPickerConfiguration()
                config.filter = if (kind == PickKind.VIDEO) PHPickerFilter.videosFilter else PHPickerFilter.imagesFilter
                val picker = PHPickerViewController(configuration = config)
                picker.delegate = photoDelegate
                topController()?.presentViewController(picker, true, null) ?: onPicked(null)
            }
            PickKind.AUDIO, PickKind.DOCUMENT -> {
                val types = if (kind == PickKind.AUDIO) listOf(UTTypeAudio) else listOf(UTTypeData)
                val picker = UIDocumentPickerViewController(forContentTypes = types)
                picker.delegate = docDelegate
                topController()?.presentViewController(picker, true, null) ?: onPicked(null)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberCameraCapture(onCaptured: (PickedFile?) -> Unit): () -> Unit {
    val delegate = remember {
        object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
            override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
                picker.dismissViewControllerAnimated(true, null)
                val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
                val data = image?.let { UIImageJPEGRepresentation(it, 0.9) }
                onCaptured(data?.let { persist(it, "camera.jpg") })
            }

            override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                picker.dismissViewControllerAnimated(true, null)
                onCaptured(null)
            }
        }
    }
    return {
        if (UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)) {
            val picker = UIImagePickerController()
            picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
            picker.delegate = delegate
            topController()?.presentViewController(picker, true, null) ?: onCaptured(null)
        } else {
            onCaptured(null) // simulator / no camera hardware
        }
    }
}
