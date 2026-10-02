package com.telefam.devices

/** Platform identity used to label a linked device ("Android Pixel 8", "iOS iPhone"). */
expect object PlatformInfo {
    val name: String
    val model: String
}
