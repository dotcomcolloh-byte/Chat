package com.telefam.data.api

import platform.Foundation.NSBundle

/**
 * Reads API_BASE_URL from Info.plist, which in turn is populated from an
 * .xcconfig value (Debug.xcconfig / Release.xcconfig) so it stays environment-driven,
 * never hardcoded in source. See iosApp/Config/*.xcconfig.
 */
actual object ApiConfig {
    actual val baseUrl: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("API_BASE_URL") as? String
            ?: error("API_BASE_URL missing from Info.plist — set it in your .xcconfig")
    actual val giphyApiKey: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("GIPHY_API_KEY") as? String ?: "" // empty hides the GIF/sticker picker
}
