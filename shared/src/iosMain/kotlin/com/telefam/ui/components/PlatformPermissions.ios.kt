package com.telefam.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.authorizationStatusForMediaType
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Foundation.NSURL
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

private fun statusString(authorized: Boolean): String = if (authorized) "GRANTED" else "DENIED"

@Composable
actual fun rememberPermissionStatus(permission: DevicePermission): String {
    var status by remember(permission) {
        mutableStateOf(
            when (permission) {
                DevicePermission.CAMERA -> statusString(
                    AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized
                )
                DevicePermission.MICROPHONE -> statusString(
                    AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted
                )
                DevicePermission.PHOTOS -> statusString(
                    PHPhotoLibrary.authorizationStatus() == PHAuthorizationStatusAuthorized
                )
                DevicePermission.LOCATION -> statusString(
                    CLLocationManager.authorizationStatus().let {
                        it == kCLAuthorizationStatusAuthorizedAlways || it == kCLAuthorizationStatusAuthorizedWhenInUse
                    }
                )
            }
        )
    }
    return status
}

@Composable
actual fun rememberOpenAppSettings(): () -> Unit = {
    NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
        UIApplication.sharedApplication.openURL(it)
    }
}
