package com.telefam.e2ee

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.base64Encoding
import platform.Foundation.NSDataBase64EncodingOptions

@OptIn(ExperimentalForeignApi::class)
actual fun base64Encode(bytes: ByteArray): String {
    val data = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
    return data.base64Encoding()
}

@OptIn(ExperimentalForeignApi::class)
actual fun base64Decode(value: String): ByteArray {
    val data = NSData.create(base64Encoding = value) ?: return ByteArray(0)
    val out = ByteArray(data.length.toInt())
    out.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
    }
    return out
}
