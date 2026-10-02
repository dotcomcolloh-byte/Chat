package com.telefam.devices

import platform.UIKit.UIDevice

actual object PlatformInfo {
    actual val name: String = "iOS"
    actual val model: String = UIDevice.currentDevice.model
}
