package com.telefam.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSData
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.*
import platform.darwin.NSObject
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Real iOS implementation using PHPickerViewController (the modern, permission-less
 * Apple Photos picker) and UIImage/UIGraphicsImageRenderer for downsample + JPEG
 * re-compression. Presented over the current key window's root view controller.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberImagePickerCropCompress(
    maxDimension: Int,
    jpegQuality: Float,
    onResult: (ProcessedImage?) -> Unit
): () -> Unit {
    val delegate = remember {
        object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, completion = null)
                val result = didFinishPicking.firstOrNull() as? PHPickerResult
                val provider = result?.itemProvider
                if (provider == null || !provider.canLoadObjectOfClass(UIImage)) {
                    onResult(null); return
                }
                provider.loadObjectOfClass(UIImage) { image, _ ->
                    val uiImage = image as? UIImage
                    if (uiImage == null) { onResult(null); return@loadObjectOfClass }

                    val scale = minOf(1.0, maxDimension.toDouble() / maxOf(uiImage.size.useContents { width }, uiImage.size.useContents { height }))
                    val targetSize = CGSizeMake(uiImage.size.useContents { width } * scale, uiImage.size.useContents { height } * scale)

                    UIGraphicsBeginImageContextWithOptions(targetSize, false, 1.0)
                    uiImage.drawInRect(CGRectMake(0.0, 0.0, targetSize.useContents { width }, targetSize.useContents { height }))
                    val resized = UIGraphicsGetImageFromCurrentImageContext()
                    UIGraphicsEndImageContext()

                    val jpegData: NSData? = UIImageJPEGRepresentation(resized ?: uiImage, jpegQuality.toDouble())
                    if (jpegData == null) { onResult(null); return@loadObjectOfClass }

                    val bytes = ByteArray(jpegData.length.toInt())
                    memcpyNSDataToByteArray(jpegData, bytes)

                    onResult(
                        ProcessedImage(
                            bytes = bytes,
                            mimeType = "image/jpeg",
                            width = (resized ?: uiImage).size.useContents { width }.toInt(),
                            height = (resized ?: uiImage).size.useContents { height }.toInt()
                        )
                    )
                }
            }
        }
    }

    return {
        val config = PHPickerConfiguration()
        config.selectionLimit = 1
        val picker = PHPickerViewController(configuration = config)
        picker.delegate = delegate
        UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(picker, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun memcpyNSDataToByteArray(data: NSData, dest: ByteArray) {
    dest.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
    }
}
