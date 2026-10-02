package com.telefam.data.api

import com.telefam.shared.BuildConfig

/** Populated from gradle.properties / CI env at build time — see androidApp/build.gradle.kts. */
actual object ApiConfig {
    actual val baseUrl: String = BuildConfig.API_BASE_URL
    actual val giphyApiKey: String = BuildConfig.GIPHY_API_KEY
}
