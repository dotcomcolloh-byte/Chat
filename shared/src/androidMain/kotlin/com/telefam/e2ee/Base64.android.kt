package com.telefam.e2ee

actual fun base64Encode(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
actual fun base64Decode(value: String): ByteArray = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
